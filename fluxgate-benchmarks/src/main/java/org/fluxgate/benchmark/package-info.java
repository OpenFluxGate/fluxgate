/**
 * JMH microbenchmarks for FluxGate, <strong>not</strong> testing utilities.
 *
 * <p>These classes are part of the testkit jar only because they need every FluxGate module on the
 * classpath. They are not an API: they open real Redis and MongoDB connections, they have {@code
 * main} methods and JMH annotations rather than assertions, and nothing here is intended to be
 * called from an application or from a user's tests. Nothing in this package participates in
 * semantic versioning and it may change or disappear in any release.
 *
 * <p>The utilities a test should use live in {@code org.fluxgate.testkit.support} and {@code
 * org.fluxgate.testkit.junit} (module {@code fluxgate-testkit}).
 *
 * <p>Run them with the {@code benchmark} profile, which shades an executable jar around {@code
 * org.openjdk.jmh.Main}:
 *
 * <pre>{@code
 * ./mvnw -pl fluxgate-benchmarks -am -Pbenchmark -DskipTests clean package
 * java -jar fluxgate-benchmarks/target/benchmarks.jar
 * java -jar fluxgate-benchmarks/target/benchmarks.jar RedisRateLimiterBenchmark -f 1 -wi 3 -i 5
 * }</pre>
 *
 * <p>Results depend entirely on the Redis and MongoDB instances they reach, so a number produced on
 * a laptop against a container is not comparable with one produced against a cluster. Publish the
 * environment alongside any number taken from here.
 */
package org.fluxgate.benchmark;
