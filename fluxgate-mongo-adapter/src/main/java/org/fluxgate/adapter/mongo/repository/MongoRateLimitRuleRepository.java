package org.fluxgate.adapter.mongo.repository;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleConverter;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.spi.RuleSetAccessControlSource;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MongoDB implementation of {@link RateLimitRuleRepository}.
 *
 * <p><b>Identity.</b> A rule is identified by the pair {@code (ruleSetId, id)}, which is what the
 * unique index created by {@link #ensureIndexes()} enforces: the same rule id may exist in several
 * rule sets. {@link #save(RateLimitRule)}, {@link #findById(String, String)} and {@link
 * #deleteById(String, String)} all address that pair, so saving rule {@code r1} into rule set
 * {@code B} never touches rule {@code r1} of rule set {@code A}. Moving a rule between rule sets is
 * the explicit {@link #moveRule(String, String, String)}. The id-only {@link #findById(String)} and
 * {@link #deleteById(String)} required by {@link RateLimitRuleRepository} are deprecated and refuse
 * to act when the id is ambiguous.
 *
 * <p><b>Malformed documents.</b> A document that cannot be converted (wrong BSON type, unknown
 * algorithm, invalid zone id, ...) is skipped with a WARN by the list reads, so one bad rule does
 * not take its whole rule set down; {@link #getSkippedDocumentCount()} counts them.
 *
 * <p><b>Access control.</b> The rule-set-level access control is stored on the rule documents of
 * the rule set (the 0.4 layout, readable by 0.3.x which ignores the extra fields). Every write of
 * it ({@link #saveAccessControl}, {@link #moveRule}, the insert of a new rule) also sets the marker
 * field {@value #ACCESS_CONTROL_MARKER}, so a copy whose lists were all cleared still counts.
 * {@link #findAccessControlByRuleSetId(String)} reads every document of the rule set that carries
 * the marker or a non-empty access-control list (a document with neither - written by 0.3.x or by
 * hand - holds no access control and is ignored) and merges them deterministically, failing closed:
 * a deny list is the union of that list over the copies, an allow list is the intersection of that
 * list over the copies, a copy without that list counting as empty. Copies only diverge when a
 * {@link #saveAccessControl} was interrupted half-way; the merge then keeps every deny entry and
 * only the allow entries both generations agree on, and is logged at WARN until the next {@code
 * saveAccessControl} repairs it. An interrupted clear therefore yields an empty allow list, never
 * the revoked one. When the copies of an allow list share no entry (including a copy that has
 * none), the merged allow list is empty: allow lists only grant a bypass of rate limiting, so an
 * empty one grants none (rate limiting and the deny lists still apply). That case is logged at WARN
 * with the rule set id.
 */
public class MongoRateLimitRuleRepository
    implements RateLimitRuleRepository, RuleSetAccessControlSource {

  private static final Logger log = LoggerFactory.getLogger(MongoRateLimitRuleRepository.class);

  /** Name of the unique {@code (ruleSetId, id)} index, shared with the Spring Boot starters. */
  public static final String UNIQUE_RULE_INDEX = "ruleSetId_1_id_1_unique";

  /** Name of the index serving id-only lookups. */
  public static final String ID_INDEX = "id_1";

  /**
   * Field set on every rule document whose access-control lists were written by this repository
   * (the time of that write). A document carrying it takes part in the access-control merge even
   * when all its lists are empty.
   *
   * <p>Only its presence matters; the value is informational. {@link #saveAccessControl} and {@link
   * #moveRule} set it to the server time ({@code $currentDate}); the insert of a new rule sets it
   * to the client time, because {@code $currentDate} cannot be used in {@code $setOnInsert}. Values
   * of different documents are therefore not strictly comparable.
   *
   * @since 0.4.0
   */
  public static final String ACCESS_CONTROL_MARKER = "aclUpdatedAt";

  /** MongoDB's DuplicateKey error code. */
  private static final int DUPLICATE_KEY = 11000;

  /** Number of duplicate pairs reported when the unique index cannot be built. */
  private static final int MAX_REPORTED_DUPLICATES = 10;

  /** BSON fields holding the rule-set-level access control, embedded on rule documents. */
  private static final List<String> ACCESS_CONTROL_FIELDS =
      Arrays.asList("allowedIps", "deniedIps", "allowedKeys", "deniedKeys");

  /** Allow lists: merged by intersection across divergent copies, so a merge never widens them. */
  private static final Set<String> ALLOW_FIELDS =
      new LinkedHashSet<>(Arrays.asList("allowedIps", "allowedKeys"));

  /**
   * Optional rule fields that {@link RateLimitRuleMongoConverter#toBson(RateLimitRuleDocument)}
   * omits when empty; they are unset on update so a cleared value does not linger.
   */
  private static final List<String> OPTIONAL_RULE_FIELDS =
      Arrays.asList(
          "attributes",
          "methods",
          "pathPatterns",
          "excludePathPatterns",
          "headerEquals",
          "headerPresent");

  private final MongoCollection<Document> collection;

  private final AtomicLong skippedDocuments = new AtomicLong();

  public MongoRateLimitRuleRepository(MongoCollection<Document> collection) {
    this.collection = Objects.requireNonNull(collection, "collection must not be null");
  }

  private static Bson ruleKey(String ruleSetId, String id) {
    return Filters.and(Filters.eq("ruleSetId", ruleSetId), Filters.eq("id", id));
  }

  // ===================================================================================== indexes

  /**
   * Creates the indexes the repository relies on, idempotently.
   *
   * <ul>
   *   <li>{@value #UNIQUE_RULE_INDEX}: unique {@code (ruleSetId, id)} - rule set loads and the
   *       identity of every write;
   *   <li>{@value #ID_INDEX}: {@code id} - the deprecated id-only lookups.
   * </ul>
   *
   * <p>The legacy unique {@code id_1} index requires an explicit maintenance migration through
   * {@link #migrateLegacyGlobalIdConstraint()}. Startup never removes an index or silently keeps
   * global id uniqueness, which would violate the rule-set-scoped identity contract.
   *
   * <p>Fails loudly instead of logging: without the unique index concurrent saves can create
   * duplicate rules, and an existing duplicate means a rule set is already ambiguous.
   *
   * @throws IllegalStateException when an index cannot be created; for duplicates the message lists
   *     up to ten duplicated {@code (ruleSetId, id)} pairs to clean up first
   * @since 0.4.0
   */
  public void ensureIndexes() {
    validateScopedIdConstraints();
    try {
      collection.createIndex(
          Indexes.ascending("ruleSetId", "id"),
          new IndexOptions().unique(true).name(UNIQUE_RULE_INDEX));
    } catch (MongoException e) {
      // DuplicateKeyException (driver 4.x+) or a MongoCommandException with code 11000
      if (e.getCode() == DUPLICATE_KEY) {
        throw new IllegalStateException(
            "Cannot create the unique FluxGate rule index "
                + UNIQUE_RULE_INDEX
                + ": the collection already holds rules with the same (ruleSetId, id): "
                + findDuplicateRuleKeys()
                + ". Delete or rename the duplicates, then start again.",
            e);
      }
      throw new IllegalStateException(
          "Cannot create the unique FluxGate rule index "
              + UNIQUE_RULE_INDEX
              + " (a conflicting index may exist): "
              + e.getMessage(),
          e);
    }
    try {
      collection.createIndex(Indexes.ascending("id"), new IndexOptions().name(ID_INDEX));
    } catch (MongoException e) {
      throw new IllegalStateException(
          "Cannot create the FluxGate rule index " + ID_INDEX + ": " + e.getMessage(), e);
    }
  }

  /**
   * Explicitly migrates the known legacy global-id constraint to rule-set-scoped identity.
   *
   * <p>Run only during an exclusive DDL maintenance window with all old-version writers stopped.
   * Concurrent index replacement cannot be guarded atomically by MongoDB's dropIndex command. This
   * operation never runs at startup and never changes rule documents or ACL data. It creates and
   * verifies the exact compound unique constraint BEFORE removing only the known plain unique id_1
   * index, then creates the nonunique id lookup. Unknown/custom definitions fail closed.
   *
   * <p>A failed DDL call propagates. Retry is idempotent: after the legacy drop, the compound
   * constraint continues to protect identities even if creating the lookup failed. Old-version
   * rollback is not compatible once different rule sets contain the same id.
   */
  public void migrateLegacyGlobalIdConstraint() {
    validateGlobalIdConstraints(true);
    Document idIndex = namedIndex(ID_INDEX);
    if (idIndex != null
        && !isPlainIndex(idIndex, new Document("id", 1), true)
        && !isPlainIndex(idIndex, new Document("id", 1), false)) {
      throw new IllegalStateException("Refusing migration: unknown/custom id_1 index definition");
    }
    Document compoundKey = new Document("ruleSetId", 1).append("id", 1);
    Document compound = namedIndex(UNIQUE_RULE_INDEX);
    if (compound != null && !isPlainIndex(compound, compoundKey, true)) {
      throw new IllegalStateException(
          "Refusing migration: unknown/custom compound rule index definition");
    }
    collection.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions().unique(true).name(UNIQUE_RULE_INDEX));
    requirePlainIndex(UNIQUE_RULE_INDEX, compoundKey, true);
    // Recheck immediately before deletion; callers must still exclude concurrent DDL writers.
    idIndex = namedIndex(ID_INDEX);
    if (idIndex != null) {
      if (isPlainIndex(idIndex, new Document("id", 1), true)) {
        collection.dropIndex(ID_INDEX);
      } else if (!isPlainIndex(idIndex, new Document("id", 1), false)) {
        throw new IllegalStateException("Refusing migration: id_1 changed during maintenance");
      }
    }
    collection.createIndex(Indexes.ascending("id"), new IndexOptions().name(ID_INDEX));
    requirePlainIndex(UNIQUE_RULE_INDEX, compoundKey, true);
    requirePlainIndex(ID_INDEX, new Document("id", 1), false);
    validateScopedIdConstraints();
  }

  /** Read-only startup guard: no id-only unique constraint may override scoped rule identity. */
  public void validateScopedIdConstraints() {
    validateGlobalIdConstraints(false);
  }

  private void validateGlobalIdConstraints(boolean allowKnownLegacy) {
    for (Document index : collection.listIndexes()) {
      Document key = index.get("key", Document.class);
      if (!Boolean.TRUE.equals(index.getBoolean("unique"))
          || key == null
          || key.size() != 1
          || !key.containsKey("id")) continue;
      if (ID_INDEX.equals(index.getString("name"))
          && isPlainIndex(index, new Document("id", 1), true)) {
        if (allowKnownLegacy) continue;
        throw new IllegalStateException(
            "Legacy global-id index id_1 requires explicit migration: "
                + "stop old writers and concurrent DDL, then call migrateLegacyGlobalIdConstraint()");
      }
      throw new IllegalStateException(
          "Unknown/custom global-id index requires manual operator resolution; "
              + "FluxGate never removes custom constraints automatically");
    }
  }

  private Document namedIndex(String name) {
    for (Document index : collection.listIndexes()) {
      if (name.equals(index.getString("name"))) return index;
    }
    return null;
  }

  private void requirePlainIndex(String name, Document key, boolean unique) {
    if (!isPlainIndex(namedIndex(name), key, unique)) {
      throw new IllegalStateException("Cannot verify the exact FluxGate index " + name);
    }
  }

  private boolean isPlainIndex(Document index, Document key, boolean unique) {
    if (index == null || !(index.get("key") instanceof Document)) return false;
    Document actualKey = index.get("key", Document.class);
    if (!new ArrayList<>(actualKey.keySet()).equals(new ArrayList<>(key.keySet()))
        || !key.equals(actualKey)
        || Boolean.TRUE.equals(index.getBoolean("unique")) != unique) return false;
    // Index version/name/namespace are server metadata. No custom functional option is accepted.
    Set<String> known =
        new HashSet<>(
            Arrays.asList("v", "key", "name", "ns", "unique", "sparse", "hidden", "background"));
    if (!known.containsAll(index.keySet())) return false;
    if (Boolean.TRUE.equals(index.getBoolean("sparse"))
        || Boolean.TRUE.equals(index.getBoolean("hidden"))
        || Boolean.TRUE.equals(index.getBoolean("background"))) return false;
    if (index.containsKey("ns")
        && !collection.getNamespace().getFullName().equals(index.getString("ns"))) return false;
    return true;
  }

  private List<String> findDuplicateRuleKeys() {
    List<String> duplicates = new ArrayList<>();
    try {
      for (Document group :
          collection.aggregate(
              Arrays.asList(
                  Aggregates.group(
                      new Document("ruleSetId", "$ruleSetId").append("id", "$id"),
                      Accumulators.sum("count", 1)),
                  Aggregates.match(Filters.gt("count", 1)),
                  Aggregates.limit(MAX_REPORTED_DUPLICATES)))) {
        Document key = group.get("_id", Document.class);
        duplicates.add(
            "(" + key.get("ruleSetId") + ", " + key.get("id") + ") x" + group.get("count"));
      }
    } catch (RuntimeException e) {
      duplicates.add("<could not list duplicates: " + e.getMessage() + ">");
    }
    return duplicates;
  }

  // ======================================================================================= reads

  @Override
  public List<RateLimitRule> findByRuleSetId(String ruleSetId) {
    List<RateLimitRule> result = new ArrayList<>();
    for (Document doc : collection.find(Filters.eq("ruleSetId", ruleSetId))) {
      RateLimitRule rule = toDomainOrSkip(doc);
      if (rule != null) {
        result.add(rule);
      }
    }
    return result;
  }

  /**
   * Finds a rule by its identity.
   *
   * @param ruleSetId the rule set the rule belongs to
   * @param id the rule id
   * @return the rule, or empty when that rule set has no rule with this id
   * @throws org.fluxgate.adapter.mongo.converter.InvalidRuleDocumentException when the stored
   *     document is malformed
   * @since 0.4.0
   */
  public Optional<RateLimitRule> findById(String ruleSetId, String id) {
    Document doc = collection.find(ruleKey(ruleSetId, id)).first();
    return doc == null ? Optional.empty() : Optional.of(toDomain(doc));
  }

  /**
   * Finds a rule by id alone.
   *
   * <p>Rule ids are only unique within a rule set. This method returns the rule when exactly one
   * rule set holds the id, and refuses to guess otherwise.
   *
   * @throws IllegalStateException when several rule sets contain a rule with this id
   * @deprecated use {@link #findById(String, String)}
   */
  @Deprecated
  @Override
  public Optional<RateLimitRule> findById(String id) {
    List<Document> docs = collection.find(Filters.eq("id", id)).limit(2).into(new ArrayList<>());
    if (docs.isEmpty()) {
      return Optional.empty();
    }
    if (docs.size() > 1) {
      throw ambiguousId(id);
    }
    return Optional.of(toDomain(docs.get(0)));
  }

  /**
   * Whether any rule set holds a rule with this id.
   *
   * <p>Unlike {@link #findById(String)} and {@link #deleteById(String)}, this does not throw when
   * the id exists in several rule sets: it is a yes/no question and returns {@code true}.
   *
   * @deprecated use {@link #existsById(String, String)}
   */
  @Deprecated
  @Override
  public boolean existsById(String id) {
    return collection.countDocuments(Filters.eq("id", id), new CountOptions().limit(1)) > 0;
  }

  /**
   * Whether the rule set holds a rule with this id.
   *
   * @param ruleSetId the rule set
   * @param id the rule id
   * @return true when the rule exists
   * @since 0.4.0
   */
  public boolean existsById(String ruleSetId, String id) {
    return collection.countDocuments(ruleKey(ruleSetId, id), new CountOptions().limit(1)) > 0;
  }

  @Override
  public List<RateLimitRule> findAll() {
    List<RateLimitRule> result = new ArrayList<>();
    for (Document doc : collection.find()) {
      RateLimitRule rule = toDomainOrSkip(doc);
      if (rule != null) {
        result.add(rule);
      }
    }
    return result;
  }

  /**
   * Returns how many malformed documents the list reads have skipped since this repository was
   * created.
   *
   * @return the skipped document count
   * @since 0.4.0
   */
  public long getSkippedDocumentCount() {
    return skippedDocuments.get();
  }

  private static RateLimitRule toDomain(Document doc) {
    return RateLimitRuleConverter.toDomain(RateLimitRuleMongoConverter.fromBson(doc));
  }

  /** Converts a document, or logs and counts it and returns null when it is malformed. */
  private RateLimitRule toDomainOrSkip(Document doc) {
    try {
      return toDomain(doc);
    } catch (RuntimeException e) {
      skippedDocuments.incrementAndGet();
      log.warn(
          "Skipping malformed FluxGate rule document (_id={}, ruleSetId={}, id={}): {}. The rest of"
              + " the rule set is loaded without it; fix or delete the document.",
          doc.get("_id"),
          printable(doc.get("ruleSetId")),
          printable(doc.get("id")),
          e.getMessage());
      return null;
    }
  }

  private static String printable(Object value) {
    if (value == null) {
      return "null";
    }
    String text = value.toString().replaceAll("\\p{Cntrl}", "?");
    return text.length() > 128 ? text.substring(0, 128) + "..." : text;
  }

  private static IllegalStateException ambiguousId(String id) {
    return new IllegalStateException(
        "Rule id '"
            + id
            + "' exists in more than one rule set; address the rule by (ruleSetId, id) instead");
  }

  // ====================================================================================== writes

  /**
   * Saves (upserts) a rule under its {@code (ruleSetId, id)} identity.
   *
   * <p>A rule without a rule set id is stored in rule set {@code "default"}. Saving a rule with a
   * rule set id other than the one it is stored under inserts a <em>new</em> rule into that rule
   * set and leaves the original alone; use {@link #moveRule(String, String, String)} to move it.
   *
   * <p>The domain rule carries no access control (it is rule-set level). An existing document is
   * updated with {@code $set}/{@code $unset} of the rule fields only, so its access-control fields
   * are never rewritten - a concurrent {@link #saveAccessControl} cannot be reverted by a stale
   * read. Only when no document matched is the rule set's access control read and copied onto the
   * new document ({@code $setOnInsert}, with the {@value #ACCESS_CONTROL_MARKER} marker).
   *
   * <p>Two concurrent first saves of the same rule race on the unique index; the loser's {@code
   * E11000} is retried once as the same upsert, so it updates the winner's document or, when that
   * document was deleted in between, inserts its own.
   *
   * <p><b>Race with {@code saveAccessControl}:</b> the access control copied onto a <em>new</em>
   * document is read in a separate operation. If {@link #saveAccessControl} runs between that read
   * and the insert, the new document carries the previous lists while the others carry the new
   * ones. The merged read in {@link #findAccessControlByRuleSetId(String)} then fails closed -
   * allow lists are old &cap; new, deny lists old &cup; new - and is logged at WARN until the next
   * {@code saveAccessControl} rewrites every copy.
   */
  @Override
  public void save(RateLimitRule rule) {
    upsertRuleDocument(RateLimitRuleConverter.toDocument(rule));
  }

  private void upsertRuleDocument(RateLimitRuleDocument ruleDoc) {
    Document bson = RateLimitRuleMongoConverter.toBson(ruleDoc);

    // rule fields, plus any access-control lists the document explicitly carries (legacy upsert);
    // access-control fields it omits are left untouched on an existing document
    Document set = new Document(bson);
    Document unset = new Document();
    for (String field : OPTIONAL_RULE_FIELDS) {
      if (!bson.containsKey(field)) {
        unset.put(field, "");
      }
    }
    Document update = new Document("$set", set);
    if (!unset.isEmpty()) {
      update.append("$unset", unset);
    }
    Bson filter = ruleKey(ruleDoc.getRuleSetId(), ruleDoc.getId());

    // existing document: rule fields only, the rule set's access control is not read at all
    if (collection.updateOne(filter, update).getMatchedCount() > 0) {
      return;
    }

    // new document: copy the rule set's access control on insert
    Document setOnInsert = new Document();
    Map<String, List<String>> ruleSetAcl = mergedAccessControl(ruleDoc.getRuleSetId());
    for (String field : ACCESS_CONTROL_FIELDS) {
      if (!set.containsKey(field) && ruleSetAcl.containsKey(field)) {
        setOnInsert.put(field, ruleSetAcl.get(field));
      }
    }
    // $currentDate cannot be used in $setOnInsert, so the marker of a new document is client time
    setOnInsert.put(ACCESS_CONTROL_MARKER, new Date());
    Document insert = new Document(update).append("$setOnInsert", setOnInsert);
    UpdateOptions upsert = new UpdateOptions().upsert(true);
    try {
      collection.updateOne(filter, insert, upsert);
    } catch (MongoWriteException e) {
      if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
        throw e;
      }
      // A concurrent save inserted the same (ruleSetId, id) first: retry the upsert once, which
      // updates that document, or inserts ours if it was deleted again in the meantime.
      log.debug(
          "Concurrent insert of rule ({}, {}); retrying the upsert once",
          ruleDoc.getRuleSetId(),
          ruleDoc.getId());
      collection.updateOne(filter, insert, upsert);
    }
  }

  /**
   * Moves a rule to another rule set.
   *
   * <p>The moved document adopts the target rule set's access control (and drops the source's), and
   * carries the {@value #ACCESS_CONTROL_MARKER} marker.
   *
   * @param id the rule id
   * @param fromRuleSetId the rule set the rule is in now
   * @param toRuleSetId the rule set to move it to
   * @return true when the rule was moved, false when {@code fromRuleSetId} has no such rule
   * @throws IllegalStateException when {@code toRuleSetId} already contains a rule with this id
   * @since 0.4.0
   */
  public boolean moveRule(String id, String fromRuleSetId, String toRuleSetId) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(fromRuleSetId, "fromRuleSetId must not be null");
    Objects.requireNonNull(toRuleSetId, "toRuleSetId must not be null");
    if (fromRuleSetId.equals(toRuleSetId)) {
      return existsById(fromRuleSetId, id);
    }
    if (existsById(toRuleSetId, id)) {
      throw targetOccupied(id, toRuleSetId);
    }

    Map<String, List<String>> targetAcl = mergedAccessControl(toRuleSetId);
    List<Bson> updates = new ArrayList<>();
    updates.add(Updates.set("ruleSetId", toRuleSetId));
    for (String field : ACCESS_CONTROL_FIELDS) {
      List<String> value = targetAcl.get(field);
      updates.add(value != null ? Updates.set(field, value) : Updates.unset(field));
    }
    updates.add(Updates.currentDate(ACCESS_CONTROL_MARKER));
    try {
      return collection
              .updateOne(ruleKey(fromRuleSetId, id), Updates.combine(updates))
              .getMatchedCount()
          > 0;
    } catch (MongoWriteException e) {
      if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
        throw targetOccupied(id, toRuleSetId);
      }
      throw e;
    }
  }

  private static IllegalStateException targetOccupied(String id, String toRuleSetId) {
    return new IllegalStateException(
        "Rule set '" + toRuleSetId + "' already contains a rule with id '" + id + "'");
  }

  /**
   * Deletes one rule.
   *
   * @param ruleSetId the rule set the rule belongs to
   * @param id the rule id
   * @return true when the rule was deleted, false when it did not exist
   * @since 0.4.0
   */
  public boolean deleteById(String ruleSetId, String id) {
    return collection.deleteOne(ruleKey(ruleSetId, id)).getDeletedCount() > 0;
  }

  /**
   * Deletes a rule by id alone.
   *
   * <p>Deletes the rule only when exactly one rule set holds the id; it never picks one of several.
   *
   * @throws IllegalStateException when several rule sets contain a rule with this id
   * @deprecated use {@link #deleteById(String, String)}
   */
  @Deprecated
  @Override
  public boolean deleteById(String id) {
    List<Document> docs =
        collection
            .find(Filters.eq("id", id))
            .projection(Projections.include("_id"))
            .limit(2)
            .into(new ArrayList<>());
    if (docs.isEmpty()) {
      return false;
    }
    if (docs.size() > 1) {
      throw ambiguousId(id);
    }
    DeleteResult result = collection.deleteOne(Filters.eq("_id", docs.get(0).get("_id")));
    return result.getDeletedCount() > 0;
  }

  @Override
  public int deleteByRuleSetId(String ruleSetId) {
    DeleteResult result = collection.deleteMany(Filters.eq("ruleSetId", ruleSetId));
    return (int) result.getDeletedCount();
  }

  // ============================================================================= access control

  /** Matches documents whose {@code field} is present and neither null nor an empty array. */
  private static Bson nonEmpty(String field) {
    return Filters.nin(field, null, new ArrayList<>());
  }

  /**
   * Reads and merges the access-control lists of a rule set (deny lists by union, allow lists by
   * intersection over the copies, a copy without the list counting as empty). Copies are the
   * documents carrying the {@value #ACCESS_CONTROL_MARKER} marker or a non-empty list.
   *
   * @return field name to sorted, de-duplicated non-empty list; absent fields have no entry. An
   *     allow list whose copies share no entry (or one copy lacks it) has no entry either (it
   *     grants no bypass), and is logged at WARN.
   */
  private Map<String, List<String>> mergedAccessControl(String ruleSetId) {
    if (ruleSetId == null) {
      return new LinkedHashMap<>();
    }
    List<Bson> participates = new ArrayList<>();
    participates.add(Filters.exists(ACCESS_CONTROL_MARKER));
    for (String field : ACCESS_CONTROL_FIELDS) {
      participates.add(nonEmpty(field));
    }
    MergedAccessControl result =
        MergedAccessControl.of(
            collection
                .find(Filters.and(Filters.eq("ruleSetId", ruleSetId), Filters.or(participates)))
                .projection(Projections.include(ACCESS_CONTROL_FIELDS)));
    if (result.distinctCopies > 1) {
      log.warn(
          "The {} rule documents of rule set '{}' carry {} different access-control lists (an "
              + "interrupted saveAccessControl?). Using the union of each deny list and the "
              + "intersection of each allow list; call saveAccessControl again to make them "
              + "consistent.",
          result.documents,
          printable(ruleSetId),
          result.distinctCopies);
    }
    if (!result.disjointAllowFields.isEmpty()) {
      log.warn(
          "The rule documents of rule set '{}' carry allow lists {} with no entry in common (or "
              + "missing from a copy); the merged allow list is empty, so it grants no bypass. "
              + "Call saveAccessControl to make the rule set's access control consistent.",
          printable(ruleSetId),
          result.disjointAllowFields);
    }
    return result.lists;
  }

  /** The fail-closed merge of the access-control copies of one rule set. */
  static final class MergedAccessControl {

    /** Field name to sorted, de-duplicated non-empty list. */
    final Map<String, List<String>> lists = new LinkedHashMap<>();

    /** Number of copies merged. */
    int documents;

    /** Number of distinct copies (more than one means the copies diverge). */
    int distinctCopies;

    /** Allow lists some copy has but whose intersection is empty. */
    final List<String> disjointAllowFields = new ArrayList<>();

    /**
     * Merges the given copies: union per deny list, intersection per allow list, a copy without a
     * list counting as empty.
     */
    static MergedAccessControl of(Iterable<Document> copies) {
      MergedAccessControl result = new MergedAccessControl();
      Map<String, TreeSet<String>> combined = new LinkedHashMap<>();
      Set<String> allowListSeen = new LinkedHashSet<>();
      Set<List<Set<String>>> distinct = new LinkedHashSet<>();
      for (Document doc : copies) {
        result.documents++;
        List<Set<String>> copy = new ArrayList<>();
        for (String field : ACCESS_CONTROL_FIELDS) {
          TreeSet<String> values = new TreeSet<>();
          Object raw = doc.get(field);
          if (raw instanceof List) {
            for (Object element : (List<?>) raw) {
              if (element instanceof String) {
                values.add((String) element);
              }
            }
          }
          copy.add(values);
          boolean allow = ALLOW_FIELDS.contains(field);
          if (values.isEmpty() && !allow) {
            continue;
          }
          if (!values.isEmpty() && allow) {
            allowListSeen.add(field);
          }
          // combined gets its own set: values is part of copy, which must not change any more
          TreeSet<String> current = combined.get(field);
          if (current == null) {
            combined.put(field, new TreeSet<>(values));
          } else if (allow) {
            current.retainAll(values);
          } else {
            current.addAll(values);
          }
        }
        distinct.add(copy);
      }
      result.distinctCopies = distinct.size();
      combined.forEach(
          (field, values) -> {
            if (!values.isEmpty()) {
              result.lists.put(field, new ArrayList<>(values));
            } else if (allowListSeen.contains(field)) {
              result.disjointAllowFields.add(field);
            }
          });
      return result;
    }
  }

  /**
   * Returns the rule-set-level {@link AccessControl} for the given rule set id.
   *
   * <p>Every document of the rule set that carries the {@value #ACCESS_CONTROL_MARKER} marker or a
   * non-empty access-control list is a copy; the copies are merged (union per deny list,
   * intersection per allow list, a missing list counting as empty, see the class documentation), so
   * the result does not depend on which document the server returns first. Returns {@link
   * AccessControl#EMPTY} when the merge leaves no list.
   *
   * @param ruleSetId the rule set id to look up
   * @return the access control (never null)
   * @throws IllegalArgumentException when a stored CIDR cannot be parsed
   * @since 0.4.0
   */
  @Override
  public AccessControl findAccessControlByRuleSetId(String ruleSetId) {
    Map<String, List<String>> merged = mergedAccessControl(ruleSetId);
    if (merged.isEmpty()) {
      return AccessControl.EMPTY;
    }
    return RateLimitRuleConverter.toAccessControl(
        merged.getOrDefault("allowedIps", new ArrayList<>()),
        merged.getOrDefault("deniedIps", new ArrayList<>()),
        new LinkedHashSet<>(merged.getOrDefault("allowedKeys", new ArrayList<>())),
        new LinkedHashSet<>(merged.getOrDefault("deniedKeys", new ArrayList<>())));
  }

  /**
   * Saves the rule-set-level access control by embedding it into every rule document for the rule
   * set. Creates no new documents; only updates existing ones. Null or empty lists are removed, and
   * every document gets the {@value #ACCESS_CONTROL_MARKER} marker, so a document whose lists were
   * all cleared still takes part in the merge (an interrupted clear cannot resurrect the old allow
   * list).
   *
   * <p>Save the rules first: access control of a rule set without rules has nowhere to live, and is
   * reported instead of being dropped silently.
   *
   * @param ruleSetId the rule set id
   * @param allowedIps allowed IP CIDRs (may be null)
   * @param deniedIps denied IP CIDRs (may be null)
   * @param allowedKeys allowed resolved key values (may be null)
   * @param deniedKeys denied resolved key values (may be null)
   * @throws IllegalStateException when the rule set has no rule documents
   * @since 0.4.0
   */
  public void saveAccessControl(
      String ruleSetId,
      List<String> allowedIps,
      List<String> deniedIps,
      Set<String> allowedKeys,
      Set<String> deniedKeys) {
    Bson updateDoc = buildAccessControlUpdate(allowedIps, deniedIps, allowedKeys, deniedKeys);
    UpdateResult result = collection.updateMany(Filters.eq("ruleSetId", ruleSetId), updateDoc);
    if (result.getMatchedCount() == 0) {
      throw new IllegalStateException(
          "Rule set '"
              + ruleSetId
              + "' has no rule documents, so its access control could not be stored. Save at "
              + "least one rule of the rule set first.");
    }
  }

  private Bson buildAccessControlUpdate(
      List<String> allowedIps,
      List<String> deniedIps,
      Set<String> allowedKeys,
      Set<String> deniedKeys) {
    List<Bson> updates = new ArrayList<>();
    setOrUnset(updates, "allowedIps", sortedOrNull(allowedIps));
    setOrUnset(updates, "deniedIps", sortedOrNull(deniedIps));
    setOrUnset(updates, "allowedKeys", sortedOrNull(allowedKeys));
    setOrUnset(updates, "deniedKeys", sortedOrNull(deniedKeys));
    updates.add(Updates.currentDate(ACCESS_CONTROL_MARKER));
    return Updates.combine(updates);
  }

  private static List<String> sortedOrNull(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    return new ArrayList<>(new TreeSet<>(values));
  }

  private void setOrUnset(List<Bson> updates, String field, Object value) {
    if (value != null) {
      updates.add(Updates.set(field, value));
    } else {
      updates.add(Updates.unset(field));
    }
  }

  // ====================================================================================== legacy

  /**
   * Legacy method for backwards compatibility.
   *
   * @deprecated Use {@link #findByRuleSetId(String)} instead
   */
  @Deprecated
  public List<RateLimitRuleDocument> findDocumentsByRuleSetId(String ruleSetId) {
    List<RateLimitRuleDocument> result = new ArrayList<>();
    for (Document doc : collection.find(Filters.eq("ruleSetId", ruleSetId))) {
      result.add(RateLimitRuleMongoConverter.fromBson(doc));
    }
    return result;
  }

  /**
   * Legacy method for backwards compatibility.
   *
   * <p>Like {@link #save(RateLimitRule)}, this addresses the document by {@code (ruleSetId, id)}
   * and never wipes the rule set's access control: absent access-control fields leave the stored
   * ones untouched (and are copied from the rule set on insert); access-control lists present on
   * {@code rule} are written as given.
   *
   * @deprecated Use {@link #save(RateLimitRule)} instead
   */
  @Deprecated
  public void upsert(RateLimitRuleDocument rule) {
    upsertRuleDocument(rule);
  }
}
