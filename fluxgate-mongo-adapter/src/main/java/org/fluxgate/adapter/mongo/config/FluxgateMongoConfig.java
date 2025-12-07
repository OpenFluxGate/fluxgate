package org.fluxgate.adapter.mongo.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

public final class FluxgateMongoConfig {

  private final MongoDatabase database;
  private final String ruleCollectionName;
  private final String eventCollectionName;

  public FluxgateMongoConfig(
      MongoClient client,
      String databaseName,
      String ruleCollectionName,
      String eventCollectionName) {
    this.database = client.getDatabase(databaseName);
    this.ruleCollectionName = ruleCollectionName;
    this.eventCollectionName = eventCollectionName;
  }

  public MongoCollection<Document> ruleCollection() {
    return database.getCollection(ruleCollectionName);
  }

  public MongoCollection<Document> eventCollection() {
    return database.getCollection(eventCollectionName);
  }

  public MongoRateLimitRuleRepository ruleRepository() {
    return new MongoRateLimitRuleRepository(ruleCollection());
  }

  public RateLimitRuleSetProvider ruleSetProvider(KeyResolver keyResolver) {
    return new MongoRuleSetProvider(ruleRepository(), keyResolver);
  }
}
