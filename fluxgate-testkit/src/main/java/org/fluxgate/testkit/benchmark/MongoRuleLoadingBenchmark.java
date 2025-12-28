/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fluxgate.testkit.benchmark;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for MongoDB rule loading performance.
 *
 * <p>This benchmark measures:
 *
 * <ul>
 *   <li>Rule set loading latency from MongoDB
 *   <li>Throughput of findById operations
 *   <li>Performance under concurrent load
 * </ul>
 *
 * <p>Run with: {@code java -jar target/benchmarks.jar MongoRuleLoadingBenchmark}
 *
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>MongoDB running (set FLUXGATE_MONGO_URI env or use default)
 * </ul>
 *
 * @author rojae
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx512m"})
public class MongoRuleLoadingBenchmark {

  private static final String MONGO_URI =
      System.getenv()
          .getOrDefault(
              "FLUXGATE_MONGO_URI",
              "mongodb://fluxgate:fluxgate123%23%24@localhost:27017/fluxgate?authSource=admin");

  private static final String MONGO_DB =
      System.getenv().getOrDefault("FLUXGATE_MONGO_DB", "fluxgate");

  private static final String RULE_SET_ID = "benchmark-ruleset";
  private static final String COLLECTION_NAME = "rate_limit_rules_benchmark";

  private MongoClient mongoClient;
  private MongoDatabase database;
  private MongoCollection<Document> collection;
  private MongoRateLimitRuleRepository repository;
  private MongoRuleSetProvider ruleSetProvider;

  @Setup(Level.Trial)
  public void setUp() {
    // Connect to MongoDB
    mongoClient = MongoClients.create(MONGO_URI);
    database = mongoClient.getDatabase(MONGO_DB);
    collection = database.getCollection(COLLECTION_NAME);

    // Clean and prepare
    collection.drop();

    // Create repository and provider
    repository = new MongoRateLimitRuleRepository(collection);
    ruleSetProvider = new MongoRuleSetProvider(repository, new LimitScopeKeyResolver());

    // Insert benchmark rules
    insertBenchmarkRules();
  }

  private void insertBenchmarkRules() {
    // Insert multiple rules for the benchmark ruleset
    for (int i = 0; i < 5; i++) {
      RateLimitBand band =
          RateLimitBand.builder(Duration.ofMinutes(1), 1000).label("benchmark-band-" + i).build();

      RateLimitRule rule =
          RateLimitRule.builder("benchmark-rule-" + i)
              .name("Benchmark Rule " + i)
              .enabled(true)
              .scope(LimitScope.PER_IP)
              .addBand(band)
              .ruleSetId(RULE_SET_ID)
              .build();

      RateLimitRuleDocument doc = RateLimitRuleMongoConverter.toDto(rule);
      repository.upsert(doc);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (collection != null) {
      collection.drop();
    }
    if (mongoClient != null) {
      mongoClient.close();
    }
  }

  /** Benchmark: Load a rule set by ID. Measures MongoDB query performance for rule loading. */
  @Benchmark
  @Threads(1)
  public void loadRuleSetSingleThread(Blackhole bh) {
    bh.consume(ruleSetProvider.findById(RULE_SET_ID));
  }

  /**
   * Benchmark: Concurrent rule set loading. Simulates multiple services loading rules
   * simultaneously.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void loadRuleSetMultiThread(Blackhole bh) {
    bh.consume(ruleSetProvider.findById(RULE_SET_ID));
  }

  /**
   * Benchmark: Load all rules (without grouping by ruleset). Measures raw MongoDB find performance.
   */
  @Benchmark
  @Threads(1)
  public void findAllRules(Blackhole bh) {
    bh.consume(repository.findAll());
  }

  /** Main method to run benchmarks directly. */
  public static void main(String[] args) throws RunnerException {
    Options opt =
        new OptionsBuilder()
            .include(MongoRuleLoadingBenchmark.class.getSimpleName())
            .resultFormat(ResultFormatType.JSON)
            .result("benchmark-results-mongo-loading.json")
            .build();

    new Runner(opt).run();
  }
}
