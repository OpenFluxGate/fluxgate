package org.fluxgate.adapter.mongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.BsonDocument;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleConverter;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.adapter.mongo.support.MongoContainerSupport;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for rule identity, malformed documents, access-control determinism and indexes
 * of {@link MongoRateLimitRuleRepository}.
 *
 * <p>A rule is identified by {@code (ruleSetId, id)}. Before 0.4 every read and write used {@code
 * id} alone while the unique index covered the pair, so saving rule {@code r1} into rule set B
 * silently moved rule {@code r1} out of rule set A.
 */
class MongoRateLimitRuleRepositoryIdentityIntegrationTest {

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

  private static RateLimitRule rule(String ruleSetId, String id, long capacity) {
    return RateLimitRule.builder(id)
        .name(id)
        .scope(LimitScope.PER_USER)
        .keyStrategyId("userId")
        .ruleSetId(ruleSetId)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), capacity).build())
        .build();
  }

  private static long capacityOf(RateLimitRule rule) {
    return rule.getBands().get(0).getCapacity();
  }

  // ================================================================================= identity

  @Test
  @DisplayName("saving the same rule id into a second rule set leaves the first rule set alone")
  void save_sameIdInTwoRuleSets_keepsBoth() {
    repository.save(rule("A", "r1", 10));
    repository.save(rule("B", "r1", 20));

    assertThat(collection.countDocuments(Filters.eq("id", "r1"))).isEqualTo(2L);
    assertThat(repository.findByRuleSetId("A"))
        .extracting(RateLimitRule::getId)
        .containsExactly("r1");
    assertThat(capacityOf(repository.findById("A", "r1").orElseThrow())).isEqualTo(10L);
    assertThat(capacityOf(repository.findById("B", "r1").orElseThrow())).isEqualTo(20L);
  }

  @Test
  @DisplayName("updating a rule in one rule set does not touch the same id in another")
  void save_updateInOneRuleSet_onlyChangesThatRule() {
    repository.save(rule("A", "r1", 10));
    repository.save(rule("B", "r1", 20));

    repository.save(rule("B", "r1", 25));

    assertThat(capacityOf(repository.findById("A", "r1").orElseThrow())).isEqualTo(10L);
    assertThat(capacityOf(repository.findById("B", "r1").orElseThrow())).isEqualTo(25L);
    assertThat(collection.countDocuments(Filters.eq("id", "r1"))).isEqualTo(2L);
  }

  @Test
  @DisplayName("deleteById(ruleSetId, id) deletes only the rule of that rule set")
  void deleteById_composite_onlyDeletesThatRule() {
    repository.save(rule("A", "r1", 10));
    repository.save(rule("B", "r1", 20));

    assertThat(repository.deleteById("B", "r1")).isTrue();
    assertThat(repository.deleteById("B", "r1")).isFalse();

    assertThat(repository.findById("A", "r1")).isPresent();
    assertThat(repository.findById("B", "r1")).isEmpty();
  }

  @Test
  @SuppressWarnings("deprecation")
  @DisplayName("the deprecated id-only methods refuse to act on an ambiguous id")
  void deprecatedIdOnly_ambiguousId_refuses() {
    repository.save(rule("A", "r1", 10));
    repository.save(rule("B", "r1", 20));
    repository.save(rule("A", "unique", 30));

    assertThatThrownBy(() -> repository.findById("r1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("r1");
    assertThatThrownBy(() -> repository.deleteById("r1")).isInstanceOf(IllegalStateException.class);
    assertThat(collection.countDocuments(Filters.eq("id", "r1"))).isEqualTo(2L);

    assertThat(repository.existsById("r1")).isTrue();
    assertThat(capacityOf(repository.findById("unique").orElseThrow())).isEqualTo(30L);
    assertThat(repository.deleteById("unique")).isTrue();
    assertThat(repository.existsById("unique")).isFalse();
  }

  @Test
  @DisplayName("moveRule moves the rule and refuses to overwrite one in the target rule set")
  void moveRule_movesAndRefusesToOverwrite() {
    repository.save(rule("A", "r1", 10));
    repository.save(rule("A", "r2", 11));
    repository.save(rule("B", "r2", 20));

    assertThat(repository.moveRule("r1", "A", "B")).isTrue();
    assertThat(repository.findById("A", "r1")).isEmpty();
    assertThat(capacityOf(repository.findById("B", "r1").orElseThrow())).isEqualTo(10L);

    assertThatThrownBy(() -> repository.moveRule("r2", "A", "B"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("r2");
    assertThat(capacityOf(repository.findById("B", "r2").orElseThrow())).isEqualTo(20L);
    assertThat(repository.moveRule("missing", "A", "B")).isFalse();
  }

  // ============================================================================ concurrent save

  @Test
  @DisplayName("concurrent first saves of the same rule produce one document and no error")
  void save_concurrentInserts_singleDocument() throws Exception {
    repository.ensureIndexes();
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Void>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        long capacity = 100 + i;
        Callable<Void> task =
            () -> {
              start.await();
              repository.save(rule("A", "hot", capacity));
              return null;
            };
        futures.add(pool.submit(task));
      }
      start.countDown();
      for (Future<Void> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(collection.countDocuments(Filters.eq("id", "hot"))).isEqualTo(1L);
  }

  @Test
  @DisplayName("an E11000 from a racing insert is retried once as an upsert")
  @SuppressWarnings("unchecked")
  void save_duplicateKeyOnInsert_isRetried() {
    AtomicBoolean thrown = new AtomicBoolean();
    MongoCollection<Document> racing =
        (MongoCollection<Document>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {MongoCollection.class},
                (proxy, method, args) -> {
                  // the upsert (3 args) loses to an "other" writer's insert of the same rule
                  if (method.getName().equals("updateOne")
                      && args.length == 3
                      && thrown.compareAndSet(false, true)) {
                    repository.save(rule("A", "r1", 10));
                    throw new MongoWriteException(
                        new WriteError(11000, "E11000 duplicate key error", new BsonDocument()),
                        new ServerAddress(),
                        Collections.emptySet());
                  }
                  try {
                    return method.invoke(collection, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });

    new MongoRateLimitRuleRepository(racing).save(rule("A", "r1", 42));

    assertThat(thrown).isTrue();
    assertThat(capacityOf(repository.findById("A", "r1").orElseThrow())).isEqualTo(42L);
    assertThat(collection.countDocuments(Filters.eq("id", "r1"))).isEqualTo(1L);
  }

  @Test
  @DisplayName("an E11000 retry still inserts the rule when the racing insert was deleted again")
  @SuppressWarnings("unchecked")
  void save_duplicateKeyOnInsert_racingRuleDeleted_isInserted() {
    repository.save(rule("A", "sibling", 1));
    repository.saveAccessControl("A", null, null, null, Set.of("user:blocked"));
    AtomicBoolean thrown = new AtomicBoolean();
    MongoCollection<Document> racing =
        (MongoCollection<Document>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {MongoCollection.class},
                (proxy, method, args) -> {
                  // the upsert loses to an "other" writer whose rule is deleted before the retry
                  if (method.getName().equals("updateOne")
                      && args.length == 3
                      && thrown.compareAndSet(false, true)) {
                    repository.save(rule("A", "r1", 10));
                    repository.deleteById("A", "r1");
                    throw new MongoWriteException(
                        new WriteError(11000, "E11000 duplicate key error", new BsonDocument()),
                        new ServerAddress(),
                        Collections.emptySet());
                  }
                  try {
                    return method.invoke(collection, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });

    new MongoRateLimitRuleRepository(racing).save(rule("A", "r1", 42));

    assertThat(thrown).isTrue();
    assertThat(capacityOf(repository.findById("A", "r1").orElseThrow())).isEqualTo(42L);
    Document stored =
        collection.find(Filters.and(Filters.eq("ruleSetId", "A"), Filters.eq("id", "r1"))).first();
    assertThat(stored.getList("deniedKeys", String.class)).containsExactly("user:blocked");
    assertThat(stored.containsKey(MongoRateLimitRuleRepository.ACCESS_CONTROL_MARKER)).isTrue();
  }

  // ================================================================================== indexes

  @Test
  @DisplayName("ensureIndexes creates the unique (ruleSetId, id) and the id index, idempotently")
  void ensureIndexes_createsIndexes() {
    repository.ensureIndexes();
    repository.ensureIndexes();

    List<String> names = new ArrayList<>();
    for (Document index : collection.listIndexes()) {
      names.add(index.getString("name"));
      if (MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX.equals(index.getString("name"))) {
        assertThat(index.getBoolean("unique")).isTrue();
      }
    }
    assertThat(names)
        .contains(
            MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX, MongoRateLimitRuleRepository.ID_INDEX);

    repository.save(rule("A", "r1", 10));
    assertThatThrownBy(
            () ->
                collection.insertOne(
                    RateLimitRuleMongoConverter.toBson(
                        RateLimitRuleConverter.toDocument(rule("A", "r1", 99)))))
        .isInstanceOf(MongoWriteException.class);
  }

  @Test
  @DisplayName("ensureIndexes fails loudly and names the duplicates when the data has some")
  void ensureIndexes_withDuplicates_failsClearly() {
    Document dup =
        RateLimitRuleMongoConverter.toBson(RateLimitRuleConverter.toDocument(rule("A", "dup", 1)));
    collection.insertOne(new Document(dup));
    collection.insertOne(new Document(dup));

    assertThatThrownBy(() -> repository.ensureIndexes())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX)
        .hasMessageContaining("(A, dup)");
  }

  // ======================================================================= malformed documents

  private static Document rawRule(String ruleSetId, String id, Document band) {
    return new Document()
        .append("id", id)
        .append("name", id)
        .append("enabled", true)
        .append("scope", "PER_USER")
        .append("keyStrategyId", "userId")
        .append("onLimitExceedPolicy", "REJECT_REQUEST")
        .append("ruleSetId", ruleSetId)
        .append("bands", List.of(band));
  }

  @Test
  @DisplayName(
      "Int32/Double numbers load, malformed documents are skipped, the rule set still loads")
  void findByRuleSetId_mixedAndMalformedDocuments() {
    collection.insertOne(
        rawRule(
            "S",
            "int32",
            new Document("windowSeconds", 60).append("capacity", 5).append("label", "m")));
    collection.insertOne(
        rawRule(
            "S",
            "double",
            new Document("windowSeconds", 60.0).append("capacity", 7.0).append("label", "m")));
    collection.insertOne(
        rawRule(
            "S",
            "string-capacity",
            new Document("windowSeconds", 60).append("capacity", "lots").append("label", "m")));
    collection.insertOne(
        rawRule(
            "S",
            "unknown-algorithm",
            new Document("windowSeconds", 60L)
                .append("capacity", 5L)
                .append("label", "m")
                .append("algorithm", "LEAKY_FUTURE")));
    collection.insertOne(
        rawRule(
            "S",
            "bad-zone",
            new Document("windowSeconds", 60L)
                .append("capacity", 5L)
                .append("label", "m")
                .append("algorithm", "FIXED_WINDOW")
                .append("zoneId", "Mars/Olympus")));

    List<RateLimitRule> rules = repository.findByRuleSetId("S");

    assertThat(rules).extracting(RateLimitRule::getId).containsExactlyInAnyOrder("int32", "double");
    assertThat(rules)
        .allSatisfy(
            r -> assertThat(r.getBands().get(0).getWindow()).isEqualTo(Duration.ofSeconds(60)));
    assertThat(repository.getSkippedDocumentCount()).isEqualTo(3L);

    MongoRuleSetProvider provider =
        new MongoRuleSetProvider(repository, (context, rule) -> RateLimitKey.of("k"));
    RateLimitRuleSet ruleSet = provider.findById("S").orElseThrow();
    assertThat(ruleSet.getRules()).hasSize(2);
  }

  // ========================================================================== access control

  private void insertWithAcl(String id, Document acl) {
    Document doc =
        RateLimitRuleMongoConverter.toBson(RateLimitRuleConverter.toDocument(rule("acl", id, 5)));
    doc.putAll(acl);
    collection.insertOne(doc);
  }

  @Test
  @DisplayName("ACL read ignores empty arrays and merges every copy deterministically")
  void findAccessControl_ignoresEmptyAndMerges() {
    // An empty allow list must not hide the real one (it used to win when returned first).
    insertWithAcl("a", new Document("allowedIps", List.of()).append("deniedKeys", List.of()));
    insertWithAcl(
        "b",
        new Document("allowedIps", List.of("10.0.0.0/8")).append("deniedKeys", List.of("user:x")));
    insertWithAcl(
        "c",
        new Document("allowedIps", List.of("10.0.0.0/8")).append("deniedKeys", List.of("user:y")));

    AccessControl acl = repository.findAccessControlByRuleSetId("acl");

    assertThat(acl.getAllowedIps().contains("10.1.2.3")).isTrue();
    assertThat(acl.getAllowedIps().contains("192.168.0.1")).isFalse();
    assertThat(acl.getDeniedKeys()).containsExactlyInAnyOrder("user:x", "user:y");
  }

  @Test
  @DisplayName("the merged ACL is the same whatever order the documents come back in")
  void findAccessControl_isOrderIndependent() {
    insertWithAcl("b", new Document("deniedIps", List.of("203.0.113.7")));
    insertWithAcl("a", new Document("deniedIps", List.of("198.51.100.0/24")));
    AccessControl first = repository.findAccessControlByRuleSetId("acl");

    collection.drop();
    insertWithAcl("a", new Document("deniedIps", List.of("198.51.100.0/24")));
    insertWithAcl("b", new Document("deniedIps", List.of("203.0.113.7")));
    AccessControl second = repository.findAccessControlByRuleSetId("acl");

    for (AccessControl acl : List.of(first, second)) {
      assertThat(acl.getDeniedIps().contains("203.0.113.7")).isTrue();
      assertThat(acl.getDeniedIps().contains("198.51.100.9")).isTrue();
    }
  }

  @Test
  @DisplayName("saveAccessControl on a rule set without rules is reported, not dropped")
  void saveAccessControl_withoutDocuments_throws() {
    assertThatThrownBy(
            () ->
                repository.saveAccessControl(
                    "no-such-set", null, List.of("10.0.0.0/8"), null, Set.of("user:x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-set");
  }
}
