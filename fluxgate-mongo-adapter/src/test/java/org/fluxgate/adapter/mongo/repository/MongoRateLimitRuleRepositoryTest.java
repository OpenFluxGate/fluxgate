package org.fluxgate.adapter.mongo.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link MongoRateLimitRuleRepository}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("MongoRateLimitRuleRepository Tests")
class MongoRateLimitRuleRepositoryTest {

  @Mock private MongoCollection<Document> collection;
  @Mock private FindIterable<Document> findIterable;
  @Mock private MongoCursor<Document> mongoCursor;
  @Mock private DeleteResult deleteResult;

  private MongoRateLimitRuleRepository repository;

  @BeforeEach
  void setUp() {
    repository = new MongoRateLimitRuleRepository(collection);
  }

  @Test
  @DisplayName("constructor should throw NullPointerException when collection is null")
  void constructor_shouldThrowWhenCollectionIsNull() {
    assertThrows(NullPointerException.class, () -> new MongoRateLimitRuleRepository(null));
  }

  @Nested
  @DisplayName("findByRuleSetId Tests")
  class FindByRuleSetIdTests {

    @Test
    @DisplayName("should return empty list when no rules found")
    void findByRuleSetId_shouldReturnEmptyListWhenNoRulesFound() {
      // given
      when(collection.find(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.iterator()).thenReturn(mongoCursor);
      when(mongoCursor.hasNext()).thenReturn(false);

      // when
      List<RateLimitRule> result = repository.findByRuleSetId("nonexistent");

      // then
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should return rules when found")
    void findByRuleSetId_shouldReturnRulesWhenFound() {
      // given
      RateLimitRuleDocument ruleDoc = createTestRuleDocument("rule-1", "test-ruleset");
      Document bsonDoc = RateLimitRuleMongoConverter.toBson(ruleDoc);

      when(collection.find(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.iterator()).thenReturn(mongoCursor);
      when(mongoCursor.hasNext()).thenReturn(true, false);
      when(mongoCursor.next()).thenReturn(bsonDoc);

      // when
      List<RateLimitRule> result = repository.findByRuleSetId("test-ruleset");

      // then
      assertEquals(1, result.size());
      assertEquals("rule-1", result.get(0).getId());
    }
  }

  @Nested
  @DisplayName("findById Tests")
  class FindByIdTests {

    @Test
    @DisplayName("should return empty when rule not found")
    void findById_shouldReturnEmptyWhenRuleNotFound() {
      when(collection.find(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.first()).thenReturn(null);

      Optional<RateLimitRule> result = repository.findById("test-ruleset", "nonexistent");

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should return rule when found")
    void findById_shouldReturnRuleWhenFound() {
      RateLimitRuleDocument ruleDoc = createTestRuleDocument("rule-1", "test-ruleset");
      Document bsonDoc = RateLimitRuleMongoConverter.toBson(ruleDoc);

      when(collection.find(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.first()).thenReturn(bsonDoc);

      Optional<RateLimitRule> result = repository.findById("test-ruleset", "rule-1");

      assertTrue(result.isPresent());
      assertEquals("rule-1", result.get().getId());
    }
  }

  @Nested
  @DisplayName("save Tests")
  class SaveTests {

    private RateLimitRule testRule() {
      return RateLimitRule.builder("rule-1")
          .name("Test Rule")
          .scope(LimitScope.PER_IP)
          .keyStrategyId("ip")
          .ruleSetId("test-ruleset")
          .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
          .build();
    }

    @Test
    @DisplayName("an existing rule is updated in place without reading the access control")
    void save_existingRule_updatesWithoutReadingAccessControl() {
      when(collection.updateOne(any(Bson.class), any(Bson.class)))
          .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

      repository.save(testRule());

      org.mockito.ArgumentCaptor<Bson> filter = org.mockito.ArgumentCaptor.forClass(Bson.class);
      verify(collection).updateOne(filter.capture(), any(Bson.class));
      String rendered = filter.getValue().toBsonDocument().toJson();
      assertTrue(rendered.contains("\"ruleSetId\": \"test-ruleset\""), rendered);
      assertTrue(rendered.contains("\"id\": \"rule-1\""), rendered);
      verify(collection, never()).find(any(Bson.class));
      verify(collection, never())
          .updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
    }

    @Test
    @DisplayName("a new rule is upserted with the access-control marker on insert")
    void save_newRule_upsertsWithMarker() {
      when(collection.updateOne(any(Bson.class), any(Bson.class)))
          .thenReturn(UpdateResult.acknowledged(0L, 0L, null));
      when(collection.find(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.projection(any(Bson.class))).thenReturn(findIterable);
      when(findIterable.iterator()).thenReturn(mongoCursor);
      when(mongoCursor.hasNext()).thenReturn(false);

      repository.save(testRule());

      org.mockito.ArgumentCaptor<Bson> update = org.mockito.ArgumentCaptor.forClass(Bson.class);
      verify(collection).updateOne(any(Bson.class), update.capture(), any(UpdateOptions.class));
      Document setOnInsert = ((Document) update.getValue()).get("$setOnInsert", Document.class);
      assertTrue(setOnInsert.containsKey(MongoRateLimitRuleRepository.ACCESS_CONTROL_MARKER));
    }
  }

  @Nested
  @DisplayName("access-control merge Tests")
  class MergeTests {

    private Document copy(String field, String... values) {
      return new Document(field, List.of(values));
    }

    @Test
    @DisplayName("counts distinct copies correctly when the merge changes an earlier copy's lists")
    void merge_countsDistinctCopies() {
      MongoRateLimitRuleRepository.MergedAccessControl merged =
          MongoRateLimitRuleRepository.MergedAccessControl.of(
              List.of(copy("deniedKeys", "a"), copy("deniedKeys", "b"), copy("deniedKeys", "a")));

      assertEquals(3, merged.documents);
      assertEquals(2, merged.distinctCopies);
      assertEquals(List.of("a", "b"), merged.lists.get("deniedKeys"));
    }

    @Test
    @DisplayName("allow lists intersect, a copy without the list (marker only) counts as empty")
    void merge_missingAllowListCountsAsEmpty() {
      MongoRateLimitRuleRepository.MergedAccessControl merged =
          MongoRateLimitRuleRepository.MergedAccessControl.of(
              List.of(
                  copy("allowedKeys", "a", "b"),
                  copy("allowedKeys", "b"),
                  new Document(MongoRateLimitRuleRepository.ACCESS_CONTROL_MARKER, "t")));

      assertFalse(merged.lists.containsKey("allowedKeys"));
      assertEquals(List.of("allowedKeys"), merged.disjointAllowFields);
      assertEquals(3, merged.distinctCopies);
    }

    @Test
    @DisplayName("identical copies are one distinct copy")
    void merge_identicalCopies() {
      MongoRateLimitRuleRepository.MergedAccessControl merged =
          MongoRateLimitRuleRepository.MergedAccessControl.of(
              List.of(copy("allowedKeys", "a"), copy("allowedKeys", "a")));

      assertEquals(1, merged.distinctCopies);
      assertEquals(List.of("a"), merged.lists.get("allowedKeys"));
      assertTrue(merged.disjointAllowFields.isEmpty());
    }
  }

  @Nested
  @DisplayName("deleteById Tests")
  class DeleteByIdTests {

    @Test
    @DisplayName("should return true when rule deleted")
    void deleteById_shouldReturnTrueWhenRuleDeleted() {
      when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);
      when(deleteResult.getDeletedCount()).thenReturn(1L);

      assertTrue(repository.deleteById("test-ruleset", "rule-1"));
    }

    @Test
    @DisplayName("should return false when rule not found")
    void deleteById_shouldReturnFalseWhenRuleNotFound() {
      when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);
      when(deleteResult.getDeletedCount()).thenReturn(0L);

      assertFalse(repository.deleteById("test-ruleset", "nonexistent"));
    }
  }

  @Nested
  @DisplayName("findAll Tests")
  class FindAllTests {

    @Test
    @DisplayName("should return empty list when no rules exist")
    void findAll_shouldReturnEmptyListWhenNoRulesExist() {
      // given
      when(collection.find()).thenReturn(findIterable);
      when(findIterable.iterator()).thenReturn(mongoCursor);
      when(mongoCursor.hasNext()).thenReturn(false);

      // when
      List<RateLimitRule> result = repository.findAll();

      // then
      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should return all rules")
    void findAll_shouldReturnAllRules() {
      // given
      RateLimitRuleDocument ruleDoc1 = createTestRuleDocument("rule-1", "ruleset-1");
      RateLimitRuleDocument ruleDoc2 = createTestRuleDocument("rule-2", "ruleset-2");
      Document bsonDoc1 = RateLimitRuleMongoConverter.toBson(ruleDoc1);
      Document bsonDoc2 = RateLimitRuleMongoConverter.toBson(ruleDoc2);

      when(collection.find()).thenReturn(findIterable);
      when(findIterable.iterator()).thenReturn(mongoCursor);
      when(mongoCursor.hasNext()).thenReturn(true, true, false);
      when(mongoCursor.next()).thenReturn(bsonDoc1, bsonDoc2);

      // when
      List<RateLimitRule> result = repository.findAll();

      // then
      assertEquals(2, result.size());
    }
  }

  @Nested
  @DisplayName("deleteByRuleSetId Tests")
  class DeleteByRuleSetIdTests {

    @Test
    @DisplayName("should return deleted count")
    void deleteByRuleSetId_shouldReturnDeletedCount() {
      // given
      when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
      when(deleteResult.getDeletedCount()).thenReturn(3L);

      // when
      int result = repository.deleteByRuleSetId("test-ruleset");

      // then
      assertEquals(3, result);
    }

    @Test
    @DisplayName("should return zero when no rules deleted")
    void deleteByRuleSetId_shouldReturnZeroWhenNoRulesDeleted() {
      // given
      when(collection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
      when(deleteResult.getDeletedCount()).thenReturn(0L);

      // when
      int result = repository.deleteByRuleSetId("nonexistent");

      // then
      assertEquals(0, result);
    }
  }

  private RateLimitRuleDocument createTestRuleDocument(String id, String ruleSetId) {
    RateLimitBandDocument band = new RateLimitBandDocument(60L, 100L, "test-band");
    return new RateLimitRuleDocument(
        id,
        "Test Rule",
        true,
        LimitScope.PER_IP,
        "ip",
        OnLimitExceedPolicy.REJECT_REQUEST,
        List.of(band),
        ruleSetId);
  }
}
