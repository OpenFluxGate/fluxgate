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
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for in-memory Bucket4j rate limiter performance testing.
 *
 * <p>This benchmark measures the pure in-memory token bucket performance without any network I/O
 * (no Redis dependency).
 *
 * <p>Useful for:
 *
 * <ul>
 *   <li>Baseline performance comparison
 *   <li>Testing token bucket algorithm efficiency
 *   <li>CI/CD environments without Redis
 * </ul>
 *
 * <p>Run with: {@code java -jar target/benchmarks.jar Bucket4jRateLimiterBenchmark}
 *
 * @author rojae
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(
    value = 1,
    jvmArgs = {"-Xms512m", "-Xmx512m"})
public class StandaloneRateLimiterBenchmark {

  private Bucket4jRateLimiter rateLimiter;
  private RateLimitRuleSet ruleSet;
  private RequestContext context;

  @Setup(Level.Trial)
  public void setUp() {
    rateLimiter = new Bucket4jRateLimiter();

    // Create a high-capacity rule to avoid rejections during benchmark
    RateLimitBand band =
        RateLimitBand.builder(Duration.ofMinutes(1), 10_000_000).label("benchmark-band").build();

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

  /**
   * Benchmark: Single-threaded token consumption. Measures raw algorithm performance without
   * contention.
   */
  @Benchmark
  @Threads(1)
  public void tryConsumeSingleThread(Blackhole bh) {
    bh.consume(rateLimiter.tryConsume(context, ruleSet, 1));
  }

  /** Benchmark: Multi-threaded token consumption. Measures performance under concurrent load. */
  @Benchmark
  @Threads(Threads.MAX)
  public void tryConsumeMultiThread(Blackhole bh) {
    bh.consume(rateLimiter.tryConsume(context, ruleSet, 1));
  }

  /** Benchmark: Burst consumption (10 tokens at once). */
  @Benchmark
  @Threads(1)
  public void tryConsumeBurst(Blackhole bh) {
    bh.consume(rateLimiter.tryConsume(context, ruleSet, 10));
  }

  /**
   * Benchmark: Different IP addresses (simulates multiple clients). Tests cache/map performance
   * with multiple keys.
   */
  @Benchmark
  @Threads(Threads.MAX)
  public void tryConsumeMultipleClients(Blackhole bh) {
    // Use thread ID to simulate different clients
    String ip = "192.168.1." + (Thread.currentThread().getId() % 256);
    RequestContext ctx =
        RequestContext.builder().clientIp(ip).endpoint("/api/benchmark").method("GET").build();
    bh.consume(rateLimiter.tryConsume(ctx, ruleSet, 1));
  }

  /** Main method to run benchmarks directly. */
  public static void main(String[] args) throws RunnerException {
    Options opt =
        new OptionsBuilder()
            .include(StandaloneRateLimiterBenchmark.class.getSimpleName())
            .resultFormat(ResultFormatType.JSON)
            .result("benchmark-results.json")
            .build();

    new Runner(opt).run();
  }
}
