package org.fluxgate.adapter.mongo.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.DeleteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleConverter;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
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
    this.collection = collection;
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
