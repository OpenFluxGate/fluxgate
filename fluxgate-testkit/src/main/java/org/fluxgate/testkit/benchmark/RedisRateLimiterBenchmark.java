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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
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
 * JMH Benchmark for Redis-based rate limiter performance testing.
 *
 * <p>This benchmark measures:
 *
 * <ul>
 *   <li>Throughput (operations per second)
 *   <li>Latency (average time per operation)
 *   <li>Scalability under concurrent load
 * </ul>
 *
 * <p>Run with: {@code java -jar target/benchmarks.jar RedisRateLimiterBenchmark}
 *
 * <p>Prerequisites:
 *
 * <ul>
 *   <li>Redis running at localhost:6379 (or set FLUXGATE_REDIS_URI env)
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
public class RedisRateLimiterBenchmark {

  private static final String REDIS_URI =
      System.getenv().getOrDefault("FLUXGATE_REDIS_URI", "redis://localhost:6379");

  @Param({"1", "4", "8", "16"})
  private int threadCount;

  private RedisRateLimiterConfig redisConfig;
  private RedisRateLimiter rateLimiter;
  private RateLimitRuleSet ruleSet;
  private RequestContext context;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    // Initialize Redis connection
    redisConfig = new RedisRateLimiterConfig(REDIS_URI);
    rateLimiter = new RedisRateLimiter(redisConfig.getTokenBucketStore());

    // Flush Redis to start clean
    redisConfig.getConnectionProvider().flushdb();

    // Create a high-capacity rule to avoid rejections during benchmark
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(1), 1_000_000).label("benchmark-band").build();

    RateLimitRule rule =
        RateLimitRule.builder("benchmark-rule")
            .name("Benchmark Rule")
            .enabled(true)
            .scope(LimitScope.PER_IP)
            .addBand(band)
            .ruleSetId("benchmark-ruleset")
            .build();

    ruleSet =
        RateLimitRuleSet.builder("benchmark-ruleset")
            .keyResolver(new LimitScopeKeyResolver())
            .rules(java.util.List.of(rule))
            .build();

    context =
        RequestContext.builder()
            .clientIp("127.0.0.1")
            .endpoint("/api/benchmark")
            .method("GET")
            .build();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (redisConfig != null) {
      redisConfig.close();
    }
  }

  /**
   * Benchmark: Single token consumption. Measures the latency and throughput of tryConsume
   * operation.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void tryConsume(Blackhole bh) {
    bh.consume(rateLimiter.tryConsume(context, ruleSet, 1));
  }

  /** Benchmark: Burst consumption (10 tokens at once). Simulates batch request processing. */
  @Benchmark
  @Threads(Threads.MAX)
  public void tryConsumeBurst(Blackhole bh) {
    bh.consume(rateLimiter.tryConsume(context, ruleSet, 10));
  }

  /** Main method to run benchmarks directly. */
  public static void main(String[] args) throws RunnerException {
    Options opt =
        new OptionsBuilder()
            .include(RedisRateLimiterBenchmark.class.getSimpleName())
            .resultFormat(ResultFormatType.JSON)
            .result("benchmark-results.json")
            .build();

    new Runner(opt).run();
  }
}
