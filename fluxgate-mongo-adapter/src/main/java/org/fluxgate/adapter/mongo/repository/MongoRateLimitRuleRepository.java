package org.fluxgate.adapter.mongo.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;

import java.util.ArrayList;
import java.util.List;

public class MongoRateLimitRuleRepository {

    private final MongoCollection<Document> collection;

    public MongoRateLimitRuleRepository(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    public List<RateLimitRuleDocument> findByRuleSetId(String ruleSetId) {
        List<RateLimitRuleDocument> result = new ArrayList<>();
        for (Document doc : collection.find(Filters.eq("ruleSetId", ruleSetId))) {
            result.add(RateLimitRuleMongoConverter.fromBson(doc));
        }
        return result;
    }

    public void upsert(RateLimitRuleDocument rule) {
        Document doc = RateLimitRuleMongoConverter.toBson(rule);
        collection.replaceOne(
                Filters.eq("id", rule.getId()),
                doc,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );
    }

    public void deleteById(String id) {
        collection.deleteOne(Filters.eq("id", id));
    }
}
