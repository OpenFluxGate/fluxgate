package org.fluxgate.adapter.mongo.policy;

import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.core.config.RateLimitRule;

/**
 * Publishes complete immutable policy snapshots with one authoritative pointer CAS. Draft
 * collections are consulted only to verify legacy bootstrap eligibility. Active readers use
 * immutable snapshots. Orphan snapshots from failed CAS are invisible. Removing rules preserves
 * counters; reintroducing an historical rule requires explicit reset. Existing bands retain
 * identity and incompatible structural changes require reset.
 *
 * <p>Published rule-set IDs, rule IDs, and band labels use {@code [A-Za-z0-9_.-]+} so Redis key
 * sanitization cannot merge distinct counters. Migrating legacy unsafe identities requires new safe
 * identities and an explicit counter reset. ACL belongs exclusively to the snapshot.
 *
 * <p>Indexed operation and historical-identity candidates require checksum-checked binary ancestry
 * proofs. Failed-CAS snapshots cannot be replayed or appear in published history. Reader
 * construction never creates indexes; publication requires index-creation authority.
 */
public final class MongoPolicyRepository {
  private static final int MAX_HISTORY = 100;
  private static final long MAX_LUA_INTEGER = 9_007_199_254_740_991L;
  private static final Set<String> ACL_FIELDS =
      Set.of("allowedIps", "deniedIps", "allowedKeys", "deniedKeys");
  private final MongoCollection<Document> pointers;
  private final MongoCollection<Document> revisions;
  private final MongoCollection<Document> legacyRules;

  public MongoPolicyRepository(MongoDatabase database, String rulesCollection) {
    Objects.requireNonNull(database, "database");
    requireText(rulesCollection, "rulesCollection");
    pointers = collection(database, rulesCollection + "_policies");
    revisions = collection(database, rulesCollection + "_revisions");
    legacyRules = collection(database, rulesCollection);
  }

  private boolean indexesReady;

  private synchronized void ensurePublicationIndexes() {
    if (indexesReady) return;
    revisions.createIndex(
        com.mongodb.client.model.Indexes.compoundIndex(
            com.mongodb.client.model.Indexes.ascending("ruleSetId", "operationId", "revision")));
    revisions.createIndex(
        com.mongodb.client.model.Indexes.compoundIndex(
            com.mongodb.client.model.Indexes.ascending("ruleSetId", "rules.id", "revision")));
    revisions.createIndex(
        com.mongodb.client.model.Indexes.compoundIndex(
            com.mongodb.client.model.Indexes.ascending("ruleSetId", "revision")));
    indexesReady = true;
  }

  private static MongoCollection<Document> collection(MongoDatabase database, String name) {
    return database
        .getCollection(name)
        .withReadPreference(ReadPreference.primary())
        .withReadConcern(ReadConcern.MAJORITY)
        .withWriteConcern(WriteConcern.MAJORITY);
  }

  public Optional<Document> findActive(String id) {
    requireText(id, "ruleSetId");
    Document pointer = pointers.find(Filters.eq("_id", id)).first();
    if (pointer == null) return Optional.empty();
    Document snapshot = load(pointer.getString("snapshotId"), id);
    if (!Objects.equals(pointer.get("revision"), snapshot.get("revision"))
        || !Objects.equals(pointer.get("counterEpoch"), snapshot.get("counterEpoch")))
      throw new IllegalStateException("Active pointer and snapshot disagree");
    return Optional.of(snapshot);
  }

  public List<Document> history(String id, int limit) {
    if (limit < 1 || limit > MAX_HISTORY)
      throw new IllegalArgumentException("history limit must be 1..100");
    List<Document> result = new ArrayList<>();
    Document current = findActive(id).orElse(null);
    while (current != null && result.size() < limit) {
      result.add(current);
      current = previous(current, id);
    }
    return Collections.unmodifiableList(result);
  }

  public Document publish(
      String ruleSetId,
      long expectedRevision,
      List<Document> rules,
      Document accessControl,
      boolean resetCounters,
      String resetReason,
      String operationId,
      String actor) {
    return publishInternal(
        ruleSetId,
        expectedRevision,
        rules,
        accessControl,
        resetCounters,
        resetReason,
        operationId,
        actor,
        null);
  }

  public Document rollback(
      String ruleSetId,
      long expectedRevision,
      long targetRevision,
      boolean resetCounters,
      String resetReason,
      String operationId,
      String actor) {
    ensurePublicationIndexes();
    if (targetRevision <= 0) throw new IllegalArgumentException("targetRevision must be positive");
    Document active = findActive(ruleSetId).orElse(null);
    Document target = publishedCandidate(ruleSetId, Filters.eq("revision", targetRevision), active);
    if (target == null)
      throw new IllegalArgumentException("Target revision is not published history");
    return publishInternal(
        ruleSetId,
        expectedRevision,
        target.getList("rules", Document.class),
        target.get("accessControl", Document.class),
        resetCounters,
        resetReason,
        operationId,
        actor,
        targetRevision);
  }

  private Document publishInternal(
      String id,
      long expected,
      List<Document> inputRules,
      Document acl,
      boolean reset,
      String reason,
      String op,
      String actor,
      Long rollbackTarget) {
    ensurePublicationIndexes();
    safeIdentity(id, "ruleSetId");
    requireText(op, "operationId");
    requireText(actor, "actor");
    if (expected < 0) throw new IllegalArgumentException("expectedRevision must be nonnegative");
    if (reset) requireText(reason, "resetReason");
    Objects.requireNonNull(inputRules, "rules");
    if (inputRules.isEmpty())
      throw new IllegalArgumentException("Published policies require at least one rule");
    Document request =
        new Document("ruleSetId", id)
            .append("expectedRevision", expected)
            .append("rules", inputRules)
            .append("accessControl", acl == null ? new Document() : acl)
            .append("resetCounters", reset)
            .append("resetReason", reason)
            .append("actor", actor)
            .append("rollbackTarget", rollbackTarget);
    String digest = hash(request);
    Document active = findActive(id).orElse(null);
    Document replay = publishedCandidate(id, Filters.eq("operationId", op), active);
    if (replay != null) {
      if (!digest.equals(replay.getString("digest")))
        throw new IllegalStateException("Operation id payload conflict");
      return replay;
    }
    Set<String> historicalIds = new HashSet<>();
    Set<String> activeIds = new HashSet<>();
    if (active != null)
      for (Document rule : active.getList("rules", Document.class))
        activeIds.add(rule.getString("id"));
    if (!reset)
      for (Document rule : inputRules) {
        String ruleId = rule.getString("id");
        if (!activeIds.contains(ruleId)
            && publishedCandidate(id, Filters.eq("rules.id", ruleId), active) != null)
          historicalIds.add(ruleId);
      }
    long currentRevision = active == null ? 0 : number(active, "revision");
    if (currentRevision >= MAX_LUA_INTEGER)
      throw new IllegalStateException("Policy revision exceeds Redis integer range");
    if (expected != currentRevision)
      throw new IllegalStateException("Active policy revision conflict");
    List<Document> frozen = validateAndFreeze(id, inputRules, active, historicalIds, reset);
    if (active == null && !reset) validateLegacyBootstrap(id, frozen);
    Document accessControl = copy(acl == null ? new Document() : acl);
    validateAcl(accessControl);
    String epoch =
        reset
            ? UUID.randomUUID().toString()
            : active == null ? "legacy" : active.getString("counterEpoch");
    String snapshotId = UUID.randomUUID().toString();
    Document snapshot =
        new Document("_id", snapshotId)
            .append("snapshotId", snapshotId)
            .append("schemaVersion", 1)
            .append("ruleSetId", id)
            .append("revision", Math.addExact(currentRevision, 1))
            .append("counterEpoch", epoch)
            .append("rules", frozen)
            .append("accessControl", accessControl)
            .append("actor", actor)
            .append("operationId", op)
            .append("digest", digest)
            .append("previousSnapshotId", active == null ? null : active.getString("snapshotId"))
            .append("ancestorSnapshotIds", buildAncestors(active, id))
            .append("publishedAt", Instant.now().toString())
            .append("resetReason", reset ? reason : null)
            .append("rollbackTarget", rollbackTarget);
    snapshot.append("checksum", hash(snapshot));
    revisions.insertOne(snapshot);
    Document pointer =
        new Document("_id", id)
            .append("revision", snapshot.get("revision"))
            .append("counterEpoch", epoch)
            .append("snapshotId", snapshotId);
    try {
      if (active == null) pointers.insertOne(pointer);
      else if (pointers
              .replaceOne(
                  Filters.and(
                      Filters.eq("_id", id),
                      Filters.eq("revision", expected),
                      Filters.eq("snapshotId", active.getString("snapshotId"))),
                  pointer,
                  new ReplaceOptions().upsert(false))
              .getMatchedCount()
          != 1) {
        return replayAfterConflict(id, op, digest);
      }
    } catch (MongoWriteException ex) {
      if (ex.getError().getCode() != 11000) throw ex;
      return replayAfterConflict(id, op, digest);
    }
    return copy(snapshot);
  }

  private Document replayAfterConflict(String id, String op, String digest) {
    Document replay =
        publishedCandidate(id, Filters.eq("operationId", op), findActive(id).orElse(null));
    if (replay != null) {
      if (!digest.equals(replay.getString("digest")))
        throw new IllegalStateException("Operation id payload conflict");
      return replay;
    }
    throw new IllegalStateException("Active policy revision conflict");
  }

  /**
   * Indexed candidates include failed-CAS orphans; ancestry, not revision alone, proves
   * publication.
   */
  private Document publishedCandidate(
      String id, org.bson.conversions.Bson filter, Document active) {
    if (active == null) return null;
    for (Document raw :
        revisions
            .find(
                Filters.and(
                    Filters.eq("ruleSetId", id),
                    filter,
                    Filters.lte("revision", number(active, "revision"))))
            .sort(com.mongodb.client.model.Sorts.descending("revision"))) {
      Document candidate = load(raw.getString("snapshotId"), id);
      if (isAncestor(active, candidate, id)) return candidate;
    }
    return null;
  }

  private boolean isAncestor(Document active, Document candidate, String id) {
    Document cursor = active;
    long target = number(candidate, "revision");
    while (number(cursor, "revision") > target) {
      long distance = number(cursor, "revision") - target;
      int level = 63 - Long.numberOfLeadingZeros(distance);
      List<String> jumps = cursor.getList("ancestorSnapshotIds", String.class);
      if (jumps == null || level >= jumps.size()) {
        cursor = previous(cursor, id);
      } else {
        Document next = load(jumps.get(level), id);
        if (number(next, "revision") != number(cursor, "revision") - (1L << level))
          throw new IllegalStateException("Broken publication ancestry");
        cursor = next;
      }
      if (cursor == null) throw new IllegalStateException("Broken publication ancestry");
    }
    return cursor.getString("snapshotId").equals(candidate.getString("snapshotId"));
  }

  private List<String> buildAncestors(Document active, String id) {
    List<String> result = new ArrayList<>();
    if (active == null) return result;
    result.add(active.getString("snapshotId"));
    Document cursor = active;
    for (int level = 1; (1L << level) <= number(active, "revision"); level++) {
      List<String> prior = cursor.getList("ancestorSnapshotIds", String.class);
      if (prior == null || prior.size() < level)
        break; // Older schema-1 snapshots use checked parent fallback.
      Document next = load(prior.get(level - 1), id);
      if (number(next, "revision") != number(cursor, "revision") - (1L << (level - 1)))
        throw new IllegalStateException("Broken publication ancestry");
      result.add(next.getString("snapshotId"));
      cursor = next;
    }
    return result;
  }

  private List<Document> validateAndFreeze(
      String id, List<Document> rules, Document active, Set<String> historicalIds, boolean reset) {
    Map<String, Document> prior = new HashMap<>();
    if (active != null)
      for (Document r : active.getList("rules", Document.class)) prior.put(r.getString("id"), r);
    List<Document> result = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Document raw : rules) {
      Document rule = copy(Objects.requireNonNull(raw, "rule"));
      String ruleId = rule.getString("id");
      if ("WAIT_FOR_REFILL".equals(rule.getString("onLimitExceedPolicy")))
        throw new IllegalArgumentException(
            "Gateway published policies do not support WAIT_FOR_REFILL; use REJECT");
      safeIdentity(ruleId, "rule id");
      for (String field : rule.keySet()) {
        if (ACL_FIELDS.contains(field) || field.equals("accessControl"))
          throw new IllegalArgumentException(
              "Rule-level access control is forbidden; use snapshot accessControl");
      }
      if (!ids.add(ruleId)) throw new IllegalArgumentException("Duplicate rule id: " + ruleId);
      if (!id.equals(rule.getString("ruleSetId")))
        throw new IllegalArgumentException("Rule set mismatch");
      Document attrs = rule.get("attributes", Document.class);
      if (attrs != null
          && (attrs.containsKey("fluxgate.counterEpoch")
              || attrs.containsKey("fluxgate.counterRevision")))
        throw new IllegalArgumentException("Reserved counter attributes");
      Document old = prior.get(ruleId);
      if (!reset && old == null && historicalIds.contains(ruleId))
        throw new IllegalArgumentException("Reintroduced rule requires reset: " + ruleId);
      normalizeBandNumbers(rule);
      List<Document> bands = rule.getList("bands", Document.class);
      if (bands == null || bands.isEmpty()) throw new IllegalArgumentException("Rule needs bands");
      List<Document> oldBands =
          old == null ? Collections.emptyList() : old.getList("bands", Document.class);
      RateLimitRule domain;
      try {
        domain = RateLimitRuleMongoConverter.toDomain(RateLimitRuleMongoConverter.fromBson(rule));
      } catch (RuntimeException ex) {
        throw new IllegalArgumentException("Invalid rule: " + ruleId, ex);
      }
      validateRedisBounds(domain);
      if (!reset && old != null && bands.size() != oldBands.size())
        throw new IllegalArgumentException("Band identity change requires reset");
      Set<String> labels = new HashSet<>();
      for (int i = 0; i < bands.size(); i++) {
        Document band = bands.get(i);
        String label = band.getString("label");
        if (label == null || label.trim().isEmpty()) {
          label =
              old != null && i < oldBands.size()
                  ? oldBands.get(i).getString("label")
                  : domain.getBands().get(i).getKeyLabel();
          band.put("label", label);
        }
        String algorithm = band.getString("algorithm");
        if (algorithm != null) {
          try {
            org.fluxgate.core.config.RateLimitAlgorithm.valueOf(algorithm);
          } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown algorithm", ex);
          }
        }
        String zone = band.getString("zoneId");
        if (zone != null) java.time.ZoneId.of(zone);
        safeIdentity(label, "band label");
        if (!labels.add(label)) throw new IllegalArgumentException("Duplicate band label");
      }
      if (!reset && old != null && !compatible(old, rule))
        throw new IllegalArgumentException("Structural policy change requires reset: " + ruleId);
      result.add(rule);
    }
    return result;
  }

  /** Legacy bootstrap requires quiesced draft writers and legacy consumers during migration. */
  private void validateLegacyBootstrap(String id, List<Document> rules) {
    Map<String, Document> legacy = new HashMap<>();
    for (Document raw : legacyRules.find(Filters.eq("ruleSetId", id)))
      legacy.put(raw.getString("id"), raw);
    if (legacy.isEmpty() || legacy.size() != rules.size())
      throw new IllegalArgumentException(
          "Initial publication requires explicit reset unless bootstrapping identical legacy counters");
    for (Document rule : rules) {
      Document previous = legacy.get(rule.getString("id"));
      if (previous == null || !compatible(previous, rule))
        throw new IllegalArgumentException(
            "Legacy counter structure differs; explicit reset required");
      RateLimitRule a =
          RateLimitRuleMongoConverter.toDomain(RateLimitRuleMongoConverter.fromBson(previous));
      RateLimitRule b =
          RateLimitRuleMongoConverter.toDomain(RateLimitRuleMongoConverter.fromBson(rule));
      for (int i = 0; i < a.getBands().size(); i++) {
        if (a.getBands().get(i).getCapacity() != b.getBands().get(i).getCapacity())
          throw new IllegalArgumentException("Legacy capacity differs; explicit reset required");
      }
    }
  }

  private static void safeIdentity(String value, String field) {
    requireText(value, field);
    if (!value.matches("[A-Za-z0-9_.-]+"))
      throw new IllegalArgumentException(
          field
              + " must use [A-Za-z0-9_.-]+; legacy unsafe identities require reset and new safe IDs");
  }

  private static void validateRedisBounds(RateLimitRule rule) {
    for (org.fluxgate.core.config.RateLimitBand band : rule.getBands()) {
      long micros;
      try {
        band.getWindow()
            .toNanos(); // Result headers and execution paths also convert to nanoseconds.
        micros =
            Math.addExact(
                Math.multiplyExact(band.getWindow().getSeconds(), 1_000_000L),
                band.getWindow().getNano() / 1000);
      } catch (ArithmeticException ex) {
        throw new IllegalArgumentException("Band duration overflows Redis execution", ex);
      }
      if (micros <= 0 || micros > MAX_LUA_INTEGER || band.getCapacity() > MAX_LUA_INTEGER)
        throw new IllegalArgumentException("Band exceeds exact Redis integer range");
      if (band.getAlgorithm() == org.fluxgate.core.config.RateLimitAlgorithm.TOKEN_BUCKET
          && micros > 9_007_199_254_740_992L / band.getCapacity())
        throw new IllegalArgumentException("TOKEN_BUCKET capacity x window_micros exceeds 2^53");
    }
  }

  private static void normalizeBandNumbers(Document rule) {
    List<Document> bands = rule.getList("bands", Document.class);
    if (bands == null) return;
    for (Document band : bands) {
      for (String field : Arrays.asList("capacity", "windowSeconds")) {
        Object value = band.get(field);
        if (!(value instanceof Integer) && !(value instanceof Long))
          throw new IllegalArgumentException(field + " must be an integer");
        band.put(field, ((Number) value).longValue());
      }
    }
  }

  private static boolean compatible(Document before, Document after) {
    normalizeBandNumbers(before);
    normalizeBandNumbers(after);
    RateLimitRule a =
        RateLimitRuleMongoConverter.toDomain(RateLimitRuleMongoConverter.fromBson(before));
    RateLimitRule b =
        RateLimitRuleMongoConverter.toDomain(RateLimitRuleMongoConverter.fromBson(after));
    if (a.getScope() != b.getScope() || !Objects.equals(a.getKeyStrategyId(), b.getKeyStrategyId()))
      return false;
    if (a.getBands().size() != b.getBands().size()) return false;
    for (int i = 0; i < a.getBands().size(); i++) {
      org.fluxgate.core.config.RateLimitBand x = a.getBands().get(i), y = b.getBands().get(i);
      if (!x.getKeyLabel().equals(y.getKeyLabel())
          || x.getAlgorithm() != y.getAlgorithm()
          || !x.getWindow().equals(y.getWindow())
          || x.getQuotaPeriod() != y.getQuotaPeriod()
          || !x.getZoneId().equals(y.getZoneId())
          || x.getSlidingWindowBuckets() != y.getSlidingWindowBuckets()) return false;
    }
    return true;
  }

  private static void validateAcl(Document acl) {
    for (String field : acl.keySet())
      if (!ACL_FIELDS.contains(field))
        throw new IllegalArgumentException("Unknown accessControl field: " + field);
    for (String field : ACL_FIELDS) {
      Object value = acl.get(field);
      if (value == null) continue;
      if (!(value instanceof List))
        throw new IllegalArgumentException("ACL must contain string lists");
      for (Object entry : (List<?>) value) {
        if (!(entry instanceof String) || ((String) entry).trim().isEmpty())
          throw new IllegalArgumentException("Invalid ACL entry");
        if (field.endsWith("Ips")) validateCidr((String) entry);
      }
    }
  }

  private static void validateCidr(String cidr) {
    if (org.fluxgate.core.match.CidrSet.of(Collections.singletonList(cidr)).isEmpty()) {
      throw new IllegalArgumentException("Invalid CIDR: " + cidr);
    }
  }

  private Document previous(Document current, String id) {
    String previous = current.getString("previousSnapshotId");
    if (previous == null) return null;
    Document result = load(previous, id);
    if (number(result, "revision") != number(current, "revision") - 1)
      throw new IllegalStateException("Broken publication history");
    return result;
  }

  private Document load(String snapshotId, String id) {
    requireText(snapshotId, "snapshotId");
    Document result = revisions.find(Filters.eq("_id", snapshotId)).first();
    if (result == null) throw new IllegalStateException("Missing active policy snapshot");
    if (!(result.get("schemaVersion") instanceof Number) || number(result, "schemaVersion") != 1)
      throw new IllegalStateException("Unsupported policy snapshot schemaVersion");
    Document checked = copy(result);
    String checksum = checked.getString("checksum");
    checked.remove("checksum");
    if (!Objects.equals(checksum, hash(checked))
        || !id.equals(result.getString("ruleSetId"))
        || !snapshotId.equals(result.getString("snapshotId")))
      throw new IllegalStateException("Policy snapshot integrity failure");
    List<String> ancestors = result.getList("ancestorSnapshotIds", String.class);
    if (ancestors != null
        && !ancestors.isEmpty()
        && !Objects.equals(ancestors.get(0), result.getString("previousSnapshotId")))
      throw new IllegalStateException("Broken publication ancestry");
    return result;
  }

  private static long number(Document doc, String field) {
    return ((Number) doc.get(field)).longValue();
  }

  private static void requireText(String value, String field) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException(field + " is required");
  }

  private static Document copy(Document doc) {
    return Document.parse(
        doc.toJson(
            org.bson.json.JsonWriterSettings.builder()
                .outputMode(org.bson.json.JsonMode.EXTENDED)
                .build()));
  }

  private static String hash(Document doc) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical(doc).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : bytes) result.append(String.format("%02x", b & 255));
      return result.toString();
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String canonical(Object value) {
    if (value instanceof Map) {
      TreeMap<String, Object> sorted = new TreeMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> sorted.put((String) k, v));
      StringBuilder out = new StringBuilder("{");
      sorted.forEach(
          (k, v) ->
              out.append(new Document("k", k).toJson())
                  .append(':')
                  .append(canonical(v))
                  .append(';'));
      return out.append('}').toString();
    }
    if (value instanceof List) {
      StringBuilder out = new StringBuilder("[");
      for (Object item : (List<?>) value) out.append(canonical(item)).append(';');
      return out.append(']').toString();
    }
    return new Document("v", value).toJson();
  }
}
