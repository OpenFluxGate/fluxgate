package org.fluxgate.adapter.mongo.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link FluxgateMongoConfig}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("FluxgateMongoConfig Tests")
class FluxgateMongoConfigTest {

  @Mock private MongoClient mongoClient;
  @Mock private MongoDatabase mongoDatabase;
  @Mock private MongoCollection<Document> ruleCollection;
  @Mock private MongoCollection<Document> eventCollection;

  private FluxgateMongoConfig config;

  @BeforeEach
  void setUp() {
    when(mongoClient.getDatabase("testdb")).thenReturn(mongoDatabase);
    config = new FluxgateMongoConfig(mongoClient, "testdb", "rules", "events");
  }

  @Test
  @DisplayName("should return rule collection")
  void ruleCollection_shouldReturnRuleCollection() {
    // given
    when(mongoDatabase.getCollection("rules")).thenReturn(ruleCollection);

    // when
    MongoCollection<Document> result = config.ruleCollection();

    // then
    assertSame(ruleCollection, result);
    verify(mongoDatabase).getCollection("rules");
  }

  @Test
  @DisplayName("should return event collection")
  void eventCollection_shouldReturnEventCollection() {
    // given
    when(mongoDatabase.getCollection("events")).thenReturn(eventCollection);

    // when
    MongoCollection<Document> result = config.eventCollection();

    // then
    assertSame(eventCollection, result);
    verify(mongoDatabase).getCollection("events");
  }

  @Test
  @DisplayName("should create rule repository")
  void ruleRepository_shouldCreateRuleRepository() {
    // given
    when(mongoDatabase.getCollection("rules")).thenReturn(ruleCollection);

    // when
    MongoRateLimitRuleRepository repository = config.ruleRepository();

    // then
    assertNotNull(repository);
  }

  @Test
  @DisplayName("should create rule set provider")
  void ruleSetProvider_shouldCreateRuleSetProvider() {
    // given
    when(mongoDatabase.getCollection("rules")).thenReturn(ruleCollection);
    KeyResolver keyResolver = (context, rule) -> new RateLimitKey(context.getClientIp());

    // when
    RateLimitRuleSetProvider provider = config.ruleSetProvider(keyResolver);

    // then
    assertNotNull(provider);
  }
}
