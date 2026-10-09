package org.fluxgate.adapter.mongo.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
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

  @Override
  public void save(RateLimitRule rule) {
    RateLimitRuleDocument ruleDoc = RateLimitRuleConverter.toDocument(rule);
    Document doc = RateLimitRuleMongoConverter.toBson(ruleDoc);
    collection.replaceOne(Filters.eq("id", rule.getId()), doc, new ReplaceOptions().upsert(true));
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
   * RateLimitRuleDocument}). This method reads the first document and extracts the embedded
   * access-control lists. Returns {@link AccessControl#EMPTY} when the rule set has no rules or
   * when the first document carries no access-control data (0.3.x documents).
   *
   * @param ruleSetId the rule set id to look up
   * @return the access control (never null)
   * @since 0.4.0
   */
  public AccessControl findAccessControlByRuleSetId(String ruleSetId) {
    Document first = collection.find(Filters.eq("ruleSetId", ruleSetId)).limit(1).first();
    if (first == null) {
      return AccessControl.EMPTY;
    }
    RateLimitRuleDocument doc = RateLimitRuleMongoConverter.fromBson(first);
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
   * @deprecated Use {@link #save(RateLimitRule)} instead
   */
  @Deprecated
  public void upsert(RateLimitRuleDocument rule) {
    Document doc = RateLimitRuleMongoConverter.toBson(rule);
    collection.replaceOne(Filters.eq("id", rule.getId()), doc, new ReplaceOptions().upsert(true));
  }
}
