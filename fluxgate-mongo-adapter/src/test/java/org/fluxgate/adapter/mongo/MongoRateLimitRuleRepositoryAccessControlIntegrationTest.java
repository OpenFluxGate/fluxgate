package org.fluxgate.adapter.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleConverter;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.support.MongoContainerSupport;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.config.RuleMatcher;
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

  /**
   * Wraps the collection so that {@code beforeWrite} runs once, right before the first
   * single-document write ({@code replaceOne} / {@code updateOne}). This interleaves a concurrent
   * writer deterministically between a {@code save()}'s reads and its write.
   */
  @SuppressWarnings("unchecked")
  private MongoCollection<Document> interleavingCollection(Runnable beforeWrite) {
    AtomicBoolean fired = new AtomicBoolean();
    return (MongoCollection<Document>)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {MongoCollection.class},
            (proxy, method, args) -> {
              String name = method.getName();
              if ((name.equals("replaceOne") || name.equals("updateOne"))
                  && fired.compareAndSet(false, true)) {
                beforeWrite.run();
              }
              try {
                return method.invoke(collection, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  @Test
  @DisplayName("an ACL change landing between save()'s read and write is not reverted")
  void save_concurrentAccessControlChange_isNotReverted() {
    seedRuleSetWithAccessControl();

    MongoRateLimitRuleRepository racingRepository =
        new MongoRateLimitRuleRepository(
            interleavingCollection(
                () ->
                    repository.saveAccessControl(
                        RULE_SET_ID,
                        List.of("192.168.0.0/16"),
                        List.of("10.0.0.0/8", "203.0.113.7"),
                        Set.of("user:admin"),
                        Set.of("user:blocked"))));

    racingRepository.save(rule("rule-1", 50));

    Document stored = collection.find(Filters.eq("id", "rule-1")).first();
    assertThat(stored).isNotNull();
    assertThat(stored.getList("deniedIps", String.class))
        .containsExactlyInAnyOrder("10.0.0.0/8", "203.0.113.7");
    assertThat(repository.findAccessControlByRuleSetId(RULE_SET_ID).getDeniedIps())
        .matches(ips -> ips.contains("203.0.113.7"));
    assertThat(repository.findById("rule-1").orElseThrow().getBands().get(0).getCapacity())
        .isEqualTo(50L);
  }

  /** Inserts a rule document of the rule set that carries no access-control fields. */
  private void insertRawRuleWithoutAccessControl(String id) {
    RateLimitRuleDocument doc = RateLimitRuleConverter.toDocument(rule(id, 5));
    collection.insertOne(RateLimitRuleMongoConverter.toBson(doc));
  }

  @Test
  @DisplayName("access control is found even when the first document of the rule set lacks it")
  void findAccessControl_firstDocumentWithoutAcl_returnsAcl() {
    insertRawRuleWithoutAccessControl("rule-0");
    repository.save(rule("rule-1", 100));
    repository.saveAccessControl(
        RULE_SET_ID, null, List.of("10.0.0.0/8"), null, Set.of("user:blocked"));
    // saveAccessControl updates every document; strip it from rule-0 again to model a stale doc
    collection.updateOne(
        Filters.eq("id", "rule-0"),
        new Document("$unset", new Document("deniedIps", "").append("deniedKeys", "")));

    AccessControl ac = repository.findAccessControlByRuleSetId(RULE_SET_ID);

    assertThat(ac.getDeniedKeys()).containsExactly("user:blocked");
    assertThat(ac.getDeniedIps().contains("10.1.2.3")).isTrue();
  }

  @Test
  @DisplayName("a new rule copies the access control from a document that actually has it")
  void save_newRule_copiesAclFromDocumentThatHasIt() {
    insertRawRuleWithoutAccessControl("rule-0");
    repository.save(rule("rule-1", 100));
    repository.saveAccessControl(
        RULE_SET_ID,
        List.of("192.168.0.0/16"),
        List.of("10.0.0.0/8"),
        Set.of("user:admin"),
        Set.of("user:blocked"));
    collection.updateOne(
        Filters.eq("id", "rule-0"),
        new Document(
            "$unset",
            new Document("allowedIps", "")
                .append("deniedIps", "")
                .append("allowedKeys", "")
                .append("deniedKeys", "")));

    repository.save(rule("rule-2", 10));

    Document stored = collection.find(Filters.eq("id", "rule-2")).first();
    assertThat(stored).isNotNull();
    assertAclFields(stored);
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("deprecated upsert(RateLimitRuleDocument) keeps the rule set's access control")
  void legacyUpsert_preservesAccessControl() {
    seedRuleSetWithAccessControl();

    repository.upsert(RateLimitRuleConverter.toDocument(rule("rule-1", 25)));

    Document stored = collection.find(Filters.eq("id", "rule-1")).first();
    assertThat(stored).isNotNull();
    assertAclFields(stored);
    assertThat(repository.findById("rule-1").orElseThrow().getBands().get(0).getCapacity())
        .isEqualTo(25L);
  }

  @Test
  @DisplayName("saving a rule removes rule fields it no longer has")
  void save_removesClearedRuleFields() {
    RateLimitRule withMatcher =
        RateLimitRule.builder("rule-1")
            .name("rule-1")
            .scope(LimitScope.PER_USER)
            .keyStrategyId("userId")
            .ruleSetId(RULE_SET_ID)
            .matcher(RuleMatcher.builder().addPathPattern("/api/**").build())
            .attribute("tier", "gold")
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
            .build();
    repository.save(withMatcher);
    assertThat(collection.find(Filters.eq("id", "rule-1")).first().containsKey("pathPatterns"))
        .isTrue();

    repository.save(rule("rule-1", 100));

    Document stored = collection.find(Filters.eq("id", "rule-1")).first();
    assertThat(stored.containsKey("pathPatterns")).isFalse();
    assertThat(stored.containsKey("attributes")).isFalse();
    assertThat(collection.countDocuments(Filters.eq("id", "rule-1"))).isEqualTo(1L);
  }

  private static RateLimitRule ruleIn(String id, String ruleSetId) {
    return RateLimitRule.builder(id)
        .name(id)
        .scope(LimitScope.PER_USER)
        .keyStrategyId("userId")
        .ruleSetId(ruleSetId)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
        .build();
  }

  @Test
  @DisplayName("moving a rule to another rule set adopts that set's access control")
  void save_ruleSetIdChanges_adoptsNewRuleSetAccessControl() {
    seedRuleSetWithAccessControl();
    repository.save(ruleIn("other-1", "other-set"));
    repository.saveAccessControl("other-set", List.of("172.16.0.0/12"), null, null, null);

    repository.save(ruleIn("rule-1", "other-set"));

    Document moved = collection.find(Filters.eq("id", "rule-1")).first();
    assertThat(moved.getString("ruleSetId")).isEqualTo("other-set");
    assertThat(moved.getList("allowedIps", String.class)).containsExactly("172.16.0.0/12");
    assertThat(moved.containsKey("deniedIps")).isFalse();
    assertThat(moved.containsKey("allowedKeys")).isFalse();
    assertThat(moved.containsKey("deniedKeys")).isFalse();
  }

  @Test
  @DisplayName("moving a rule to a rule set without access control drops the old lists")
  void save_ruleSetIdChanges_toSetWithoutAcl_unsetsAccessControl() {
    seedRuleSetWithAccessControl();
    repository.save(ruleIn("other-1", "other-set"));

    repository.save(ruleIn("rule-1", "other-set"));

    Document moved = collection.find(Filters.eq("id", "rule-1")).first();
    for (String field : List.of("allowedIps", "deniedIps", "allowedKeys", "deniedKeys")) {
      assertThat(moved.containsKey(field)).as(field).isFalse();
    }
  }
}
