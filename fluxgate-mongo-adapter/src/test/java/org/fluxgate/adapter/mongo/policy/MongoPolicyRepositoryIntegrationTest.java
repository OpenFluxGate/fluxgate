package org.fluxgate.adapter.mongo.policy;

import static org.assertj.core.api.Assertions.*;

import com.mongodb.client.*;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.support.MongoContainerSupport;
import org.fluxgate.core.config.*;
import org.junit.jupiter.api.*;

class MongoPolicyRepositoryIntegrationTest {
  MongoClient client;
  MongoDatabase database;
  String collection;
  MongoPolicyRepository repository;

  @BeforeEach
  void setup() {
    client = MongoClients.create(MongoContainerSupport.mongoUri());
    database = client.getDatabase(MongoContainerSupport.databaseName());
    collection = MongoContainerSupport.uniqueCollectionName("policy");
    repository = new MongoPolicyRepository(database, collection);
    database.getCollection(collection).insertOne(rule(100));
  }

  @AfterEach
  void cleanup() {
    if (database != null) {
      database.getCollection(collection).drop();
      database.getCollection(collection + "_policies").drop();
      database.getCollection(collection + "_revisions").drop();
    }
    if (client != null) client.close();
  }

  Document rule(long capacity) {
    Document doc =
        RateLimitRuleMongoConverter.toBson(
            RateLimitRuleMongoConverter.toDto(
                RateLimitRule.builder("r")
                    .ruleSetId("s")
                    .scope(LimitScope.PER_IP)
                    .addBand(RateLimitBand.builder(Duration.ofSeconds(60), capacity).build())
                    .build()));
    doc.getList("bands", Document.class).get(0).remove("label");
    return doc;
  }

  Document publish(long expected, long capacity, String op) {
    return repository.publish(
        "s", expected, List.of(rule(capacity)), new Document(), false, null, op, "admin");
  }

  @Test
  void capacityChangesFreezeIdentityAndReplaySurvivesLaterPublish() {
    Document first = publish(0, 100, "one");
    Document second = publish(1, 50, "two");
    assertThat(first.getString("counterEpoch")).isEqualTo("legacy");
    assertThat(
            second
                .getList("rules", Document.class)
                .get(0)
                .getList("bands", Document.class)
                .get(0)
                .getString("label"))
        .isEqualTo(
            first
                .getList("rules", Document.class)
                .get(0)
                .getList("bands", Document.class)
                .get(0)
                .getString("label"));
    assertThat(publish(0, 100, "one").getString("snapshotId"))
        .isEqualTo(first.getString("snapshotId"));
    assertThat(repository.history("s", 20)).hasSize(2);
    assertThatThrownBy(() -> publish(0, 20, "stale")).isInstanceOf(IllegalStateException.class);
    assertThat(repository.history("s", 20)).hasSize(2);
  }

  @Test
  void rollbackGetsNewRevisionAndExplicitResetChangesEpoch() {
    publish(0, 100, "one");
    publish(1, 50, "two");
    Document rollback = repository.rollback("s", 2, 1, false, null, "rollback", "admin");
    assertThat(rollback.getLong("revision")).isEqualTo(3L);
    assertThat(rollback.getString("counterEpoch")).isEqualTo("legacy");
    Document reset =
        repository.publish(
            "s", 3, List.of(rule(100)), new Document(), true, "operator reset", "reset", "admin");
    assertThat(reset.getString("counterEpoch")).isNotEqualTo("legacy");
  }

  @Test
  void missingOrTamperedSnapshotNeverFallsBack() {
    Document first = publish(0, 100, "one");
    database
        .getCollection(collection + "_revisions")
        .updateOne(
            new Document("_id", first.getString("snapshotId")),
            new Document("$set", new Document("actor", "forged")));
    assertThatThrownBy(() -> repository.findActive("s")).isInstanceOf(IllegalStateException.class);
    database.getCollection(collection + "_revisions").deleteMany(new Document());
    assertThatThrownBy(() -> repository.findActive("s")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void incompatibleChangesAndReintroductionsNeedReset() {
    publish(0, 100, "one");
    Document altered = rule(100);
    altered.put("scope", "PER_USER");
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(altered), new Document(), false, null, "bad", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    Document keeper = rule(100).append("id", "keeper");
    repository.publish("s", 1, List.of(keeper), new Document(), false, null, "remove", "admin");
    assertThatThrownBy(() -> publish(2, 100, "readd")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void competingBootstrapPublishesOnlyOneVisibleSnapshot() throws Exception {
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    try {
      java.util.concurrent.Callable<Boolean> a =
          () -> {
            start.await();
            try {
              publish(0, 100, "race-a");
              return true;
            } catch (IllegalStateException ex) {
              return false;
            }
          };
      java.util.concurrent.Callable<Boolean> b =
          () -> {
            start.await();
            try {
              publish(0, 100, "race-b");
              return true;
            } catch (IllegalStateException ex) {
              return false;
            }
          };
      java.util.concurrent.Future<Boolean> first = executor.submit(a), second = executor.submit(b);
      start.countDown();
      assertThat(first.get() ^ second.get()).isTrue();
      assertThat(repository.history("s", 100)).hasSize(1);
      assertThat(repository.findActive("s").orElseThrow().getLong("revision")).isEqualTo(1L);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void invalidPayloadsAndOperationIdReuseAreRejected() {
    publish(0, 100, "one");
    assertThatThrownBy(() -> publish(0, 50, "one")).isInstanceOf(IllegalStateException.class);
    Document reserved =
        rule(100).append("attributes", new Document("fluxgate.counterEpoch", "forged"));
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(reserved), new Document(), false, null, "reserved", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s",
                    1,
                    List.of(rule(100)),
                    new Document("deniedIps", List.of("example.com/8")),
                    false,
                    null,
                    "cidr",
                    "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s",
                    1,
                    List.of(rule(100), rule(100)),
                    new Document(),
                    false,
                    null,
                    "duplicate",
                    "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(rule(100)), new Document(), true, "", "reset", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void initialPublicationRequiresIdenticalLegacyCountersOrReset() {
    assertThatThrownBy(() -> publish(0, 50, "changed"))
        .isInstanceOf(IllegalArgumentException.class);
    database.getCollection(collection).deleteMany(new Document());
    assertThatThrownBy(() -> publish(0, 100, "new")).isInstanceOf(IllegalArgumentException.class);
    assertThat(
            repository
                .publish(
                    "s",
                    0,
                    List.of(rule(100)),
                    new Document(),
                    true,
                    "new deployment",
                    "new-reset",
                    "admin")
                .getString("counterEpoch"))
        .isNotEqualTo("legacy");
  }

  @Test
  void gatewayWaitPolicyAndUnknownSchemaFailClosed() {
    Document wait = rule(100).append("onLimitExceedPolicy", "WAIT_FOR_REFILL");
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 0, List.of(wait), new Document(), false, null, "wait", "admin"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WAIT_FOR_REFILL");
    Document active = publish(0, 100, "valid");
    database
        .getCollection(collection + "_revisions")
        .updateOne(
            new Document("_id", active.getString("snapshotId")),
            new Document("$set", new Document("schemaVersion", 2)));
    assertThatThrownBy(() -> repository.findActive("s"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void unsafePoliciesNeverAdvanceActivePointer() {
    publish(0, 100, "initial");
    for (Document acl :
        List.of(
            new Document("deniedIPs", List.of("10.0.0.0/8")), new Document("scope", "INVALID"))) {
      assertThatThrownBy(
              () ->
                  repository.publish("s", 1, List.of(rule(100)), acl, false, null, "typo", "admin"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Document invalid :
        List.of(
            rule(100).append("id", "r:a"), rule(100).append("deniedIps", List.of("10.0.0.0/8")))) {
      assertThatThrownBy(
              () ->
                  repository.publish(
                      "s", 1, List.of(invalid), new Document(), false, null, "invalid", "admin"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Document badLabel = rule(100);
    badLabel.getList("bands", Document.class).get(0).put("label", "a:b");
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(badLabel), new Document(), false, null, "label", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(), new Document(), false, null, "empty", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.findActive("s").orElseThrow().getLong("revision")).isEqualTo(1L);
  }

  @Test
  void redisNumericBoundsRejectedBeforePublication() {
    publish(0, 100, "initial");
    for (long capacity : new long[] {Long.MAX_VALUE, 9_007_199_254_740_992L, 200_000_000L}) {
      assertThatThrownBy(
              () ->
                  repository.publish(
                      "s",
                      1,
                      List.of(rule(capacity)),
                      new Document(),
                      false,
                      null,
                      "numeric",
                      "admin"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Document overflow = rule(100);
    overflow.getList("bands", Document.class).get(0).put("windowSeconds", Long.MAX_VALUE);
    assertThatThrownBy(
            () ->
                repository.publish(
                    "s", 1, List.of(overflow), new Document(), true, "reset", "overflow", "admin"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.findActive("s").orElseThrow().getLong("revision")).isEqualTo(1L);
  }

  private static Document withChecksum(Document snapshot) throws Exception {
    snapshot.remove("checksum");
    java.lang.reflect.Method hash =
        MongoPolicyRepository.class.getDeclaredMethod("hash", Document.class);
    hash.setAccessible(true);
    snapshot.put("checksum", hash.invoke(null, snapshot));
    return snapshot;
  }

  @Test
  void indexedReplayAndPublicationWorkBeyondTenThousandRevisions() throws Exception {
    Document first = publish(0, 100, "initial");
    String firstId = first.getString("snapshotId");
    java.util.ArrayList<Document> batch = new java.util.ArrayList<>();
    for (long revision = 2; revision <= 10002; revision++) {
      Document snapshot =
          Document.parse(
              first.toJson(
                  org.bson.json.JsonWriterSettings.builder()
                      .outputMode(org.bson.json.JsonMode.EXTENDED)
                      .build()));
      String snapshotId = "scale-" + revision;
      snapshot.put("_id", snapshotId);
      snapshot.put("snapshotId", snapshotId);
      snapshot.put("revision", revision);
      snapshot.put("operationId", "fixture-" + revision);
      snapshot.put("digest", "fixture");
      snapshot.put("previousSnapshotId", revision == 2 ? firstId : "scale-" + (revision - 1));
      java.util.ArrayList<String> ancestors = new java.util.ArrayList<>();
      for (int level = 0; (1L << level) < revision; level++) {
        long ancestorRevision = revision - (1L << level);
        ancestors.add(ancestorRevision == 1 ? firstId : "scale-" + ancestorRevision);
      }
      snapshot.put("ancestorSnapshotIds", ancestors);
      batch.add(withChecksum(snapshot));
      if (batch.size() == 1000) {
        database.getCollection(collection + "_revisions").insertMany(batch);
        batch.clear();
      }
    }
    if (!batch.isEmpty()) database.getCollection(collection + "_revisions").insertMany(batch);
    database
        .getCollection(collection + "_policies")
        .replaceOne(
            new Document("_id", "s"),
            new Document("_id", "s")
                .append("revision", 10002L)
                .append("counterEpoch", "legacy")
                .append("snapshotId", "scale-10002"));
    assertThat(publish(0, 100, "initial").getString("snapshotId")).isEqualTo(firstId);
    assertThat(publish(10002, 50, "after-scale").getLong("revision")).isEqualTo(10003L);
    assertThat(repository.history("s", 3)).hasSize(3);
    assertThat(
            repository
                .rollback("s", 10003, 1, false, null, "scale-rollback", "admin")
                .getLong("revision"))
        .isEqualTo(10004L);
  }

  @Test
  void orphanOperationAndRuleIdentityNeverBecomePublished() throws Exception {
    Document first = publish(0, 100, "initial");
    Document ghost = rule(100).append("id", "ghost");
    Document orphan =
        Document.parse(
            first.toJson(
                org.bson.json.JsonWriterSettings.builder()
                    .outputMode(org.bson.json.JsonMode.EXTENDED)
                    .build()));
    orphan.put("_id", "orphan");
    orphan.put("snapshotId", "orphan");
    orphan.put("revision", 2L);
    orphan.put("previousSnapshotId", first.getString("snapshotId"));
    orphan.put("ancestorSnapshotIds", List.of(first.getString("snapshotId")));
    orphan.put("operationId", "orphan-op");
    orphan.put("digest", "not-a-published-operation");
    orphan.put("rules", List.of(rule(100), ghost));
    database.getCollection(collection + "_revisions").insertOne(withChecksum(orphan));
    publish(1, 100, "second");
    Document published =
        repository.publish(
            "s", 2, List.of(rule(100), ghost), new Document(), false, null, "orphan-op", "admin");
    assertThat(published.getLong("revision")).isEqualTo(3L);
    assertThat(repository.history("s", 10))
        .hasSize(3)
        .noneMatch(d -> "orphan".equals(d.getString("snapshotId")));
    assertThat(
            repository
                .publish(
                    "s",
                    2,
                    List.of(rule(100), ghost),
                    new Document(),
                    false,
                    null,
                    "orphan-op",
                    "admin")
                .getString("snapshotId"))
        .isEqualTo(published.getString("snapshotId"));
  }

  @Test
  void concurrentCasLoserLeavesInvisibleOrphanAndCannotReplayIt() throws Exception {
    com.mongodb.client.MongoCollection<Document> actual =
        database
            .getCollection(collection + "_revisions")
            .withReadPreference(com.mongodb.ReadPreference.primary())
            .withReadConcern(com.mongodb.ReadConcern.MAJORITY)
            .withWriteConcern(com.mongodb.WriteConcern.MAJORITY);
    @SuppressWarnings("unchecked")
    com.mongodb.client.MongoCollection<Document> synchronizedCollection =
        org.mockito.Mockito.mock(
            com.mongodb.client.MongoCollection.class,
            org.mockito.AdditionalAnswers.delegatesTo(actual));
    org.mockito.Mockito.doReturn(synchronizedCollection)
        .when(synchronizedCollection)
        .withReadPreference(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doReturn(synchronizedCollection)
        .when(synchronizedCollection)
        .withReadConcern(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doReturn(synchronizedCollection)
        .when(synchronizedCollection)
        .withWriteConcern(org.mockito.ArgumentMatchers.any());
    java.util.concurrent.CountDownLatch inserted = new java.util.concurrent.CountDownLatch(2);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              com.mongodb.client.result.InsertOneResult result =
                  actual.insertOne(invocation.getArgument(0));
              inserted.countDown();
              if (!inserted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Concurrent snapshot insertion barrier timed out");
              return result;
            })
        .when(synchronizedCollection)
        .insertOne(org.mockito.ArgumentMatchers.any(Document.class));
    MongoDatabase wrapped =
        org.mockito.Mockito.mock(
            MongoDatabase.class, org.mockito.AdditionalAnswers.delegatesTo(database));
    org.mockito.Mockito.doReturn(synchronizedCollection)
        .when(wrapped)
        .getCollection(collection + "_revisions");
    MongoPolicyRepository raced = new MongoPolicyRepository(wrapped, collection);
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      java.util.concurrent.Callable<Boolean> a =
          () -> {
            try {
              raced.publish(
                  "s", 0, List.of(rule(100)), new Document(), false, null, "concurrent-a", "admin");
              return true;
            } catch (IllegalStateException ex) {
              return false;
            }
          };
      java.util.concurrent.Callable<Boolean> b =
          () -> {
            try {
              raced.publish(
                  "s", 0, List.of(rule(100)), new Document(), false, null, "concurrent-b", "admin");
              return true;
            } catch (IllegalStateException ex) {
              return false;
            }
          };
      java.util.concurrent.Future<Boolean> first = executor.submit(a), second = executor.submit(b);
      assertThat(first.get() ^ second.get()).isTrue();
      assertThat(actual.countDocuments()).isEqualTo(2);
      String winner = repository.findActive("s").orElseThrow().getString("operationId");
      String loser = "concurrent-a".equals(winner) ? "concurrent-b" : "concurrent-a";
      assertThatThrownBy(
              () ->
                  repository.publish(
                      "s", 0, List.of(rule(100)), new Document(), false, null, loser, "admin"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("revision conflict");
      assertThat(repository.history("s", 100)).hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cidrPublicationUsesExactCoreParserAndNeverSilentlyDropsDenies() {
    publish(0, 100, "initial");
    for (String cidr : List.of("127.1/16", "999.0.0.1/8", "10.0.0.0/33", "example.com/8")) {
      assertThatThrownBy(
              () ->
                  repository.publish(
                      "s",
                      1,
                      List.of(rule(100)),
                      new Document("deniedIps", List.of(cidr)),
                      false,
                      null,
                      "invalid-cidr",
                      "admin"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Invalid CIDR");
      assertThat(repository.findActive("s").orElseThrow().getLong("revision")).isEqualTo(1L);
    }
  }
}
