package org.fluxgate.adapter.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.support.MongoContainerSupport;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests proving that the rule-set-level access control stored on every rule document
 * survives {@link MongoRateLimitRuleRepository#save(RateLimitRule)}.
 */
class MongoRateLimitRuleRepositoryAccessControlIntegrationTest {

  private static final String RULE_SET_ID = "acl-rule-set";

  private MongoClient client;
  private MongoCollection<Document> collection;
  private MongoRateLimitRuleRepository repository;

  @BeforeEach
  void setUp() {
    client = MongoClients.create(MongoContainerSupport.mongoUri());
    collection =
        client
            .getDatabase(MongoContainerSupport.databaseName())
            .getCollection(MongoContainerSupport.uniqueCollectionName("rate_limit_rules"));
    repository = new MongoRateLimitRuleRepository(collection);
  }

  @AfterEach
  void tearDown() {
    if (collection != null) {
      collection.drop();
    }
    if (client != null) {
      client.close();
    }
  }

  private static RateLimitRule rule(String id, long capacity) {
    return RateLimitRule.builder(id)
        .name(id)
        .scope(LimitScope.PER_USER)
        .keyStrategyId("userId")
        .ruleSetId(RULE_SET_ID)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), capacity).build())
        .build();
  }

  private void seedRuleSetWithAccessControl() {
    repository.save(rule("rule-1", 100));
    repository.saveAccessControl(
        RULE_SET_ID,
        List.of("192.168.0.0/16"),
        List.of("10.0.0.0/8"),
        Set.of("user:admin"),
        Set.of("user:blocked"));
  }

  private static void assertAclFields(Document doc) {
    assertThat(doc.getList("allowedIps", String.class)).containsExactly("192.168.0.0/16");
    assertThat(doc.getList("deniedIps", String.class)).containsExactly("10.0.0.0/8");
    assertThat(doc.getList("allowedKeys", String.class)).containsExactly("user:admin");
    assertThat(doc.getList("deniedKeys", String.class)).containsExactly("user:blocked");
  }

  @Test
  @DisplayName("updating an existing rule keeps the rule set's access control")
  void save_existingRule_preservesAccessControl() {
    seedRuleSetWithAccessControl();

    repository.save(rule("rule-1", 50));

    Document stored = collection.find(Filters.eq("id", "rule-1")).first();
    assertThat(stored).isNotNull();
    assertAclFields(stored);
    assertThat(repository.findById("rule-1").orElseThrow().getBands().get(0).getCapacity())
        .isEqualTo(50L);

    AccessControl ac = repository.findAccessControlByRuleSetId(RULE_SET_ID);
    assertThat(ac.getAllowedKeys()).containsExactly("user:admin");
    assertThat(ac.getDeniedKeys()).containsExactly("user:blocked");
    assertThat(ac.getDeniedIps().contains("10.1.2.3")).isTrue();
    assertThat(ac.getAllowedIps().contains("192.168.1.1")).isTrue();
  }

  @Test
  @DisplayName("a new rule added to an existing rule set carries its access control")
  void save_newRuleInExistingRuleSet_embedsAccessControl() {
    seedRuleSetWithAccessControl();

    repository.save(rule("rule-2", 10));

    Document stored = collection.find(Filters.eq("id", "rule-2")).first();
    assertThat(stored).isNotNull();
    assertAclFields(stored);
  }

  @Test
  @DisplayName("a rule in a rule set without access control gets no access control fields")
  void save_ruleSetWithoutAccessControl_hasNoAclFields() {
    repository.save(rule("rule-1", 100));
    repository.save(rule("rule-2", 10));

    Document stored = collection.find(Filters.eq("id", "rule-2")).first();
    assertThat(stored).isNotNull();
    assertThat(stored.containsKey("allowedIps")).isFalse();
    assertThat(stored.containsKey("deniedKeys")).isFalse();
    assertThat(repository.findAccessControlByRuleSetId(RULE_SET_ID).isEmpty()).isTrue();
  }
}
