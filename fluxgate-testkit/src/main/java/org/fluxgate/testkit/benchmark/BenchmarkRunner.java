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

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Main entry point for running all FluxGate benchmarks.
 *
 * <p>Usage:
 *
 * <pre>
 * # Build the benchmark jar
 * mvn package -Pbenchmark -pl fluxgate-testkit
 *
 * # Run all benchmarks
 * java -jar fluxgate-testkit/target/benchmarks.jar
 *
 * # Run specific benchmark
 * java -jar fluxgate-testkit/target/benchmarks.jar RedisRateLimiterBenchmark
 *
 * # Quick mode (fewer iterations)
 * java -jar fluxgate-testkit/target/benchmarks.jar -wi 1 -i 3 -f 1
 * </pre>
 *
 * <p>Output formats:
 *
 * <ul>
 *   <li>JSON: For GitHub Action Benchmark integration
 *   <li>Console: Human-readable summary
 * </ul>
 *
 * @author rojae
 */
public class BenchmarkRunner {

  private static final String DEFAULT_OUTPUT_FILE = "benchmark-results.json";

  public static void main(String[] args) throws RunnerException {
    String outputFile = System.getProperty("benchmark.output", DEFAULT_OUTPUT_FILE);
    boolean quickMode = Boolean.getBoolean("benchmark.quick");

    Options opt = createOptions(outputFile, quickMode);
    new Runner(opt).run();
  }

  /**
   * Creates JMH options for running benchmarks.
   *
   * @param outputFile path to JSON output file
   * @param quickMode if true, runs fewer iterations for faster feedback
   * @return configured Options
   */
  public static Options createOptions(String outputFile, boolean quickMode) {
    ChainedOptionsBuilder builder =
        new OptionsBuilder()
            .include("org.fluxgate.testkit.benchmark.*")
            .resultFormat(ResultFormatType.JSON)
            .result(outputFile);

    if (quickMode) {
      // Quick mode: 1 warmup, 3 measurements, 1 fork
      builder =
          builder
              .warmupIterations(1)
              .warmupTime(TimeValue.seconds(1))
              .measurementIterations(3)
              .measurementTime(TimeValue.seconds(1))
              .forks(1);
    }

    return builder.build();
  }

  /**
   * Creates options for running only standalone benchmarks (no Redis required). Useful for CI
   * environments without external dependencies.
   *
   * @param outputFile path to JSON output file
   * @return configured Options
   */
  public static Options createStandaloneOptions(String outputFile) {
    return new OptionsBuilder()
        .include(StandaloneRateLimiterBenchmark.class.getSimpleName())
        .resultFormat(ResultFormatType.JSON)
        .result(outputFile)
        .warmupIterations(2)
        .warmupTime(TimeValue.seconds(1))
        .measurementIterations(5)
        .measurementTime(TimeValue.seconds(2))
        .forks(1)
        .build();
  }
}
