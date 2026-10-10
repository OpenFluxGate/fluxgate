package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bson.Document;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The rule collection's indexes against a real MongoDB: {@code ddl-auto=create} delegates to {@link
 * MongoRateLimitRuleRepository#ensureIndexes()} and {@code ddl-auto=validate} insists on the unique
 * {@code (ruleSetId, id)} index the repository's identity relies on.
 */
@DisplayName("FluxgateMongoAutoConfiguration rule indexes (Testcontainers MongoDB)")
class FluxgateMongoRuleIndexIntegrationTest {

  private static GenericContainer<?> mongo;
  private static String mongoUri;
  private static MongoClient client;

  private String database;
  private MongoCollection<Document> rules;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  @BeforeAll
  static void startMongo() {
    boolean docker;
    try {
      docker = DockerClientFactory.instance().isDockerAvailable();
    } catch (RuntimeException | LinkageError e) {
      docker = false;
    }
    Assumptions.assumeTrue(docker, "Docker is required for the MongoDB index tests");
    mongo =
        new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
            .withExposedPorts(27017)
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
            .withStartupTimeout(Duration.ofSeconds(120));
    mongo.start();
    mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017);
    client = MongoClients.create(mongoUri);
  }

  @AfterAll
  static void stopMongo() {
    if (client != null) {
      client.close();
    }
    if (mongo != null) {
      mongo.stop();
    }
  }

  @BeforeEach
  void freshDatabase() {
    database = "fluxgate_idx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    client.getDatabase(database).createCollection("rate_limit_rules");
    rules = client.getDatabase(database).getCollection("rate_limit_rules");
  }

  @AfterEach
  void dropDatabase() {
    client.getDatabase(database).drop();
  }

  private ApplicationContextRunner runner(String ddlAuto) {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class)
        .withPropertyValues(
            "fluxgate.mongo.enabled=true",
            "fluxgate.mongo.uri=" + mongoUri,
            "fluxgate.mongo.database=" + database,
            "fluxgate.mongo.ddl-auto=" + ddlAuto);
  }

  private List<Document> indexes() {
    return rules.listIndexes().into(new ArrayList<>());
  }

  private static Document rule(String ruleSetId, String id) {
    return new Document("ruleSetId", ruleSetId).append("id", id).append("name", id);
  }

  @Test
  void createAndValidateRequireExplicitLegacyMigrationWithoutMutatingDataOrIndexes() {
    // Create the valid compound first: validation must still inspect later global constraints.
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions().unique(true).name(MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX));
    rules.createIndex(Indexes.ascending("id"), new IndexOptions().unique(true).name("id_1"));
    Document preserved = rule("A", "legacy").append("deniedKeys", List.of("user:blocked"));
    rules.insertOne(preserved);
    List<Document> before = indexes();
    for (String mode : List.of("create", "validate")) {
      runner(mode)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "Legacy global-id index id_1 requires explicit migration: stop old writers and concurrent DDL, then call migrateLegacyGlobalIdConstraint()");
              });
      assertThat(indexes()).isEqualTo(before);
      assertThat(rules.find().first()).isEqualTo(preserved);
      // The context's failed owned client must not close the independent fixture client.
      assertThat(client.getDatabase(database).runCommand(new Document("ping", 1)).get("ok"))
          .isEqualTo(1.0);
    }
    new MongoRateLimitRuleRepository(rules).migrateLegacyGlobalIdConstraint();
    for (String mode : List.of("create", "validate")) {
      runner(mode).run(context -> assertThat(context).hasNotFailed());
    }
    rules.insertOne(rule("B", "legacy"));
    assertThat(rules.countDocuments()).isEqualTo(2);
  }

  @Test
  void createAndValidateRefuseForeignGlobalIdConstraintsWithoutRemovingThem() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions().unique(true).name(MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX));
    rules.createIndex(
        Indexes.ascending("id"), new IndexOptions().unique(true).name("application_owned_id"));
    rules.insertOne(rule("A", "legacy"));
    List<Document> before = indexes();
    for (String mode : List.of("create", "validate")) {
      runner(mode)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                        "Unknown/custom global-id index requires manual operator resolution; FluxGate never removes custom constraints automatically");
              });
    }
    assertThat(indexes()).isEqualTo(before);
    assertThat(rules.countDocuments()).isEqualTo(1);
  }

  @Test
  @DisplayName("create: the adapter's unique (ruleSetId, id) and id indexes are created")
  void createBuildsTheAdapterIndexes() {
    runner("create")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(indexes())
                  .anySatisfy(
                      index -> {
                        assertThat(index.getString("name"))
                            .isEqualTo(MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX);
                        assertThat(index.getBoolean("unique")).isTrue();
                      })
                  .anySatisfy(
                      index ->
                          assertThat(index.getString("name"))
                              .isEqualTo(MongoRateLimitRuleRepository.ID_INDEX));
            });
  }

  @Test
  @DisplayName("create: duplicate (ruleSetId, id) pairs fail the startup and are listed")
  void createFailsOnDuplicates() {
    rules.insertOne(rule("orders", "r1"));
    rules.insertOne(rule("orders", "r1"));

    runner("create")
        .run(
            context -> {
              assertThat(context).hasFailed();
              // The adapter's IllegalStateException wraps the driver's duplicate key error.
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "IllegalStateException: Cannot create the unique FluxGate rule index "
                          + MongoRateLimitRuleRepository.UNIQUE_RULE_INDEX)
                  .hasStackTraceContaining("(orders, r1) x2");
            });
  }

  @Test
  @DisplayName("validate: a collection without the unique index fails the startup clearly")
  void validateFailsWithoutTheUniqueIndex() {
    rules.insertOne(rule("orders", "r1"));

    runner("validate")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("rate_limit_rules")
                  .hasMessageContaining("unique")
                  .hasMessageContaining("fluxgate.mongo.ddl-auto=create");
            });
  }

  @Test
  @DisplayName("validate: a non-unique (ruleSetId, id) index is not enough")
  void validateRejectsANonUniqueIndex() {
    rules.createIndex(Indexes.ascending("ruleSetId", "id"));

    runner("validate").run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("validate: the unique index, whatever its name, lets the application start")
  void validateAcceptsTheUniqueIndex() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"), new IndexOptions().unique(true).name("custom"));

    runner("validate").run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("validate: indexes created by the adapter satisfy the check")
  void validateAcceptsTheAdapterIndexes() {
    new MongoRateLimitRuleRepository(rules).ensureIndexes();

    runner("validate").run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("validate: a partial unique index does not cover every rule and is rejected")
  void validateRejectsAPartialUniqueIndex() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions()
            .unique(true)
            .name("partial")
            .partialFilterExpression(new Document("enabled", true)));

    runner("validate")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("'partial'")
                  .hasMessageContaining("partialFilterExpression");
            });
  }

  @Test
  @DisplayName("validate: a sparse unique index skips rules without the keys and is rejected")
  void validateRejectsASparseUniqueIndex() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions().unique(true).sparse(true).name("sparse"));

    runner("validate")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("'sparse'")
                  .hasMessageContaining("sparse");
            });
  }

  @Test
  @DisplayName("validate: a case-insensitive collation merges distinct ids and is rejected")
  void validateRejectsANonSimpleCollation() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions()
            .unique(true)
            .name("ci")
            .collation(
                com.mongodb.client.model.Collation.builder()
                    .locale("en")
                    .collationStrength(com.mongodb.client.model.CollationStrength.SECONDARY)
                    .build()));

    runner("validate")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining("'ci'")
                  .hasMessageContaining("collation");
            });
  }

  @Test
  @DisplayName("validate: a simple collation is the default binary comparison and is accepted")
  void validateAcceptsASimpleCollation() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions()
            .unique(true)
            .name("simple")
            .collation(com.mongodb.client.model.Collation.builder().locale("simple").build()));

    runner("validate").run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("validate: a plain unique index next to a restricted one is enough")
  void validateAcceptsAPlainIndexNextToARestrictedOne() {
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"), new IndexOptions().unique(true).name("plain"));
    // MongoDB allows a second index on the same keys only with a different collation
    rules.createIndex(
        Indexes.ascending("ruleSetId", "id"),
        new IndexOptions()
            .unique(true)
            .name("ci-too")
            .collation(
                com.mongodb.client.model.Collation.builder()
                    .locale("en")
                    .collationStrength(com.mongodb.client.model.CollationStrength.SECONDARY)
                    .build()));

    runner("validate").run(context -> assertThat(context).hasNotFailed());
  }
}
