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
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
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
 * JMH Benchmark for MongoDB event recording performance.
 *
 * <p>This benchmark measures the CURRENT synchronous event recording performance.
 * Use this as a baseline before implementing async optimization.
 *
 * <p>Measures:
 * <ul>
 *   <li>Event insert latency (single thread)</li>
 *   <li>Event insert throughput (multi thread)</li>
 *   <li>Impact on rate limiting performance</li>
 * </ul>
 *
 * <p>Run with: {@code java -jar target/benchmarks.jar MongoEventRecordingBenchmark}
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>MongoDB running (set FLUXGATE_MONGO_URI env or use default)</li>
 * </ul>
 *
 * @author rojae
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgs = {"-Xms512m", "-Xmx512m"})
public class MongoEventRecordingBenchmark {

  private static final String MONGO_URI =
      System.getenv()
          .getOrDefault(
              "FLUXGATE_MONGO_URI",
              "mongodb://fluxgate:fluxgate123%23%24@localhost:27017/fluxgate?authSource=admin");

  private static final String MONGO_DB =
      System.getenv().getOrDefault("FLUXGATE_MONGO_DB", "fluxgate");

  private static final String COLLECTION_NAME = "rate_limit_events_benchmark";

  private MongoClient mongoClient;
  private MongoDatabase database;
  private MongoCollection<Document> collection;
  private MongoRateLimitMetricsRecorder metricsRecorder;

  // Pre-created objects for benchmark
  private RateLimitRule rule;
  private RequestContext context;
  private RateLimitKey key;
  private RateLimitResult allowedResult;
  private RateLimitResult rejectedResult;

  @Setup(Level.Trial)
  public void setUp() {
    // Connect to MongoDB
    mongoClient = MongoClients.create(MONGO_URI);
    database = mongoClient.getDatabase(MONGO_DB);
    collection = database.getCollection(COLLECTION_NAME);

    // Clean collection
    collection.drop();

    // Create metrics recorder
    metricsRecorder = new MongoRateLimitMetricsRecorder(collection);

    // Pre-create rule for events
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(1), 100)
            .label("benchmark-band")
            .build();

    rule = RateLimitRule.builder("benchmark-rule")
        .name("Benchmark Rule")
        .enabled(true)
        .scope(LimitScope.PER_IP)
        .addBand(band)
        .ruleSetId("benchmark-ruleset")
        .build();

    // Pre-create context
    context = RequestContext.builder()
        .clientIp("127.0.0.1")
        .endpoint("/api/benchmark")
        .method("GET")
        .build();

    // Pre-create key (format: ruleSetId:ruleId:identifier)
    key = RateLimitKey.of("benchmark-ruleset:benchmark-rule:127.0.0.1");

    // Pre-create results
    allowedResult = RateLimitResult.allowed(key, rule, 50, 0);
    rejectedResult = RateLimitResult.rejected(key, rule, Duration.ofSeconds(30).toNanos());
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

  /**
   * Benchmark: Record allowed event (single thread).
   * Measures synchronous MongoDB insert latency.
   */
  @Benchmark
  @Threads(1)
  public void recordAllowedEventSingleThread(Blackhole bh) {
    metricsRecorder.record(context, allowedResult);
  }

  /**
   * Benchmark: Record rejected event (single thread).
   */
  @Benchmark
  @Threads(1)
  public void recordRejectedEventSingleThread(Blackhole bh) {
    metricsRecorder.record(context, rejectedResult);
  }

  /**
   * Benchmark: Concurrent event recording.
   * Simulates high-traffic scenario with multiple threads writing events.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void recordEventMultiThread(Blackhole bh) {
    metricsRecorder.record(context, allowedResult);
  }

  /**
   * Main method to run benchmarks directly.
   */
  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(MongoEventRecordingBenchmark.class.getSimpleName())
        .resultFormat(ResultFormatType.JSON)
        .result("benchmark-results-mongo-events.json")
        .build();

    new Runner(opt).run();
  }
}
