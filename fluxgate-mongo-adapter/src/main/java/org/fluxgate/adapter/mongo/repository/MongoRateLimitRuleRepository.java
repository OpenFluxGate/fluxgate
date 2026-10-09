package org.fluxgate.adapter.mongo.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleConverter;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.spi.RateLimitRuleRepository;

/**
 * MongoDB implementation of {@link RateLimitRuleRepository}.
 *
 * <p>Stores rate limit rules in MongoDB collection.
 */
public class MongoRateLimitRuleRepository implements RateLimitRuleRepository {

  private final MongoCollection<Document> collection;

  public MongoRateLimitRuleRepository(MongoCollection<Document> collection) {
    this.collection = Objects.requireNonNull(collection, "collection must not be null");
  }

  @Override
  public List<RateLimitRule> findByRuleSetId(String ruleSetId) {
    List<RateLimitRule> result = new ArrayList<>();
    for (Document doc : collection.find(Filters.eq("ruleSetId", ruleSetId))) {
      RateLimitRuleDocument ruleDoc = RateLimitRuleMongoConverter.fromBson(doc);
      result.add(RateLimitRuleConverter.toDomain(ruleDoc));
    }
    return result;
  }

  @Override
  public Optional<RateLimitRule> findById(String id) {
    Document doc = collection.find(Filters.eq("id", id)).first();
    if (doc == null) {
      return Optional.empty();
    }
    RateLimitRuleDocument ruleDoc = RateLimitRuleMongoConverter.fromBson(doc);
    return Optional.of(RateLimitRuleConverter.toDomain(ruleDoc));
  }

  /** BSON fields holding the rule-set-level access control, embedded on every rule document. */
  private static final List<String> ACCESS_CONTROL_FIELDS =
      Arrays.asList("allowedIps", "deniedIps", "allowedKeys", "deniedKeys");

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

  /** Matches rule documents that carry at least one access-control field. */
  private static Bson hasAccessControl() {
    List<Bson> exists = new ArrayList<>();
    for (String field : ACCESS_CONTROL_FIELDS) {
      exists.add(Filters.exists(field));
    }
    return Filters.or(exists);
  }

  /**
   * Saves (upserts) a rule.
   *
   * <p>The domain rule carries no access control (it is rule-set level). An existing document is
   * updated with {@code $set}/{@code $unset} of the rule fields only, so its access-control fields
   * are never rewritten — a concurrent {@link #saveAccessControl} cannot be reverted by a stale
   * read. A newly inserted document copies the access control of a document of the same rule set
   * that has one ({@code $setOnInsert}). A document whose {@code ruleSetId} changes adopts the new
   * rule set's access control instead ({@code $set}, or {@code $unset} when the new rule set has
   * none), so it never keeps the old rule set's lists.
   *
   * <p><b>Known limitation:</b> the access control is read from a sibling document and written in a
   * separate operation. If {@link #saveAccessControl} updates the rule set between that read and
   * the write of a <em>new</em> (or moved) document, the document keeps the previous access control
   * until the next {@code saveAccessControl} call. Callers that change access control and rules
   * concurrently should call {@code saveAccessControl} again after saving the rules.
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
    Document setOnInsert = new Document();
    Document unset = new Document();
    for (String field : OPTIONAL_RULE_FIELDS) {
      if (!bson.containsKey(field)) {
        unset.put(field, "");
      }
    }
    Document source = accessControlSource(ruleDoc.getRuleSetId());
    Document existing =
        collection
            .find(Filters.eq("id", ruleDoc.getId()))
            .projection(new Document("ruleSetId", 1))
            .first();
    boolean movedToOtherRuleSet =
        existing != null && !Objects.equals(existing.get("ruleSetId"), ruleDoc.getRuleSetId());
    for (String field : ACCESS_CONTROL_FIELDS) {
      if (set.containsKey(field)) {
        continue; // explicitly carried by the document
      }
      Object value = source == null ? null : source.get(field);
      if (movedToOtherRuleSet) {
        // the document still holds the OLD rule set's access control: adopt the new one's
        if (value != null) {
          set.put(field, value);
        } else {
          unset.put(field, "");
        }
      } else if (value != null) {
        setOnInsert.put(field, value);
      }
    }

    Document update = new Document("$set", set);
    if (!unset.isEmpty()) {
      update.append("$unset", unset);
    }
    if (!setOnInsert.isEmpty()) {
      update.append("$setOnInsert", setOnInsert);
    }
    collection.updateOne(
        Filters.eq("id", ruleDoc.getId()), update, new UpdateOptions().upsert(true));
  }

  /** Returns a document of the rule set that carries access-control fields, or {@code null}. */
  private Document accessControlSource(String ruleSetId) {
    if (ruleSetId == null) {
      return null;
    }
    return collection
        .find(Filters.and(Filters.eq("ruleSetId", ruleSetId), hasAccessControl()))
        .limit(1)
        .first();
  }

  @Override
  public boolean deleteById(String id) {
    DeleteResult result = collection.deleteOne(Filters.eq("id", id));
    return result.getDeletedCount() > 0;
  }

  @Override
  public List<RateLimitRule> findAll() {
    List<RateLimitRule> result = new ArrayList<>();
    for (Document doc : collection.find()) {
      RateLimitRuleDocument ruleDoc = RateLimitRuleMongoConverter.fromBson(doc);
      result.add(RateLimitRuleConverter.toDomain(ruleDoc));
    }
    return result;
  }

  @Override
  public int deleteByRuleSetId(String ruleSetId) {
    DeleteResult result = collection.deleteMany(Filters.eq("ruleSetId", ruleSetId));
    return (int) result.getDeletedCount();
  }

  /**
   * Returns the rule-set-level {@link AccessControl} for the given rule set id.
   *
   * <p>The access control is stored redundantly on every rule document for the rule set (see {@link
   * RateLimitRuleDocument}). This method reads a document of the rule set that carries
   * access-control fields and extracts the embedded lists, so a document without them (e.g. a 0.3.x
   * document) does not hide the rule set's access control. Returns {@link AccessControl#EMPTY} when
   * no document of the rule set has access-control data.
   *
   * @param ruleSetId the rule set id to look up
   * @return the access control (never null)
   * @since 0.4.0
   */
  public AccessControl findAccessControlByRuleSetId(String ruleSetId) {
    Document source = accessControlSource(ruleSetId);
    if (source == null) {
      return AccessControl.EMPTY;
    }
    RateLimitRuleDocument doc = RateLimitRuleMongoConverter.fromBson(source);
    return RateLimitRuleConverter.toAccessControl(doc);
  }

  /**
   * Saves the rule-set-level access control by embedding it into every rule document for the rule
   * set. Creates no new documents; only updates existing ones.
   *
   * <p>Call this after saving rules to ensure access control survives a round-trip.
   *
   * @param ruleSetId the rule set id
   * @param allowedIps allowed IP CIDRs (may be null)
   * @param deniedIps denied IP CIDRs (may be null)
   * @param allowedKeys allowed resolved key values (may be null)
   * @param deniedKeys denied resolved key values (may be null)
   * @since 0.4.0
   */
  public void saveAccessControl(
      String ruleSetId,
      List<String> allowedIps,
      List<String> deniedIps,
      java.util.Set<String> allowedKeys,
      java.util.Set<String> deniedKeys) {
    org.bson.conversions.Bson updateDoc =
        buildAccessControlUpdate(allowedIps, deniedIps, allowedKeys, deniedKeys);
    if (updateDoc != null) {
      collection.updateMany(Filters.eq("ruleSetId", ruleSetId), updateDoc);
    }
  }

  private org.bson.conversions.Bson buildAccessControlUpdate(
      List<String> allowedIps,
      List<String> deniedIps,
      java.util.Set<String> allowedKeys,
      java.util.Set<String> deniedKeys) {
    List<org.bson.conversions.Bson> updates = new ArrayList<>();
    setOrUnset(
        updates,
        "allowedIps",
        allowedIps != null && !allowedIps.isEmpty() ? new ArrayList<>(allowedIps) : null);
    setOrUnset(
        updates,
        "deniedIps",
        deniedIps != null && !deniedIps.isEmpty() ? new ArrayList<>(deniedIps) : null);
    setOrUnset(
        updates,
        "allowedKeys",
        allowedKeys != null && !allowedKeys.isEmpty() ? new ArrayList<>(allowedKeys) : null);
    setOrUnset(
        updates,
        "deniedKeys",
        deniedKeys != null && !deniedKeys.isEmpty() ? new ArrayList<>(deniedKeys) : null);
    if (updates.isEmpty()) {
      return null;
    }
    return com.mongodb.client.model.Updates.combine(updates);
  }

  private void setOrUnset(List<org.bson.conversions.Bson> updates, String field, Object value) {
    if (value != null) {
      updates.add(com.mongodb.client.model.Updates.set(field, value));
    } else {
      updates.add(com.mongodb.client.model.Updates.unset(field));
    }
  }

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
   * <p>Like {@link #save(RateLimitRule)}, this never wipes the rule set's access control: absent
   * access-control fields leave the stored ones untouched (and are copied from the rule set on
   * insert); access-control lists present on {@code rule} are written as given.
   *
   * @deprecated Use {@link #save(RateLimitRule)} instead
   */
  @Deprecated
  public void upsert(RateLimitRuleDocument rule) {
    upsertRuleDocument(rule);
  }
}
