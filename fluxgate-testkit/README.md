# FluxGate TestKit

Testing utilities for FluxGate.

## Overview

FluxGate TestKit provides:

- **`InMemoryRateLimitHandler`** - a real `FluxgateRateLimitHandler` with no Redis, no MongoDB and no Spring context, plus `reset()`
- **`FluxgateTestRules`** - fluent builders so "ten per minute per IP" fits on one line
- **`FluxgateInMemoryExtension`** - a JUnit 5 extension that resets buckets between tests and injects the handler as a test parameter
- **`RateLimitAssertions`** - AssertJ based assertions whose failure messages carry the whole decision
- JMH benchmarks live in the separate, unpublished `fluxgate-benchmarks` module (see [Benchmarks](#benchmarks))

The handler runs the same pipeline a Spring Boot application runs — `RateLimitEngine` over
`Bucket4jRateLimiter` — with rule sets held in a map. So a test written against it exercises real
key resolution, real multi-band evaluation and the real `RateLimitResponse` mapping, and it fails
when those change. What it deliberately does not do is distribute: buckets live in one instance, so
it cannot support a claim about cluster-wide limits.

## Dependencies

> **Not published to Maven Central.** The testkit is excluded from the release deployment
> (`central-publishing-maven-plugin` `excludeArtifacts`, `maven.deploy.skip`), like the samples and
> benchmarks. Build it from the repository (`./mvnw -pl fluxgate-testkit -am install`) and use that
> local build, or copy the classes you need. The coordinates below are what a local build installs.

Add the testkit to your test scope:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-testkit</artifactId>
    <version>${fluxgate.version}</version>
    <scope>test</scope>
</dependency>
```

JUnit 5 and AssertJ are `provided`-scope dependencies of the testkit, not transitive ones: your
project brings its own. If you use the testkit without them, `FluxgateInMemoryExtension` and
`RateLimitAssertions` will not resolve — add `junit-jupiter` and `assertj-core` (Spring Boot's
`spring-boot-starter-test` already contains both).

## Quick Start

```java
import static org.fluxgate.testkit.support.RateLimitAssertions.*;

class RateLimitTest {

  @RegisterExtension
  static final FluxgateInMemoryExtension fluxgate =
      FluxgateInMemoryExtension.withRuleSet(
          FluxgateTestRules.rule("per-ip")
              .perIp()
              .band(Duration.ofMinutes(1), 2)
              .toRuleSet("api-limits"));

  @Test
  void allowsUpToCapacity(InMemoryRateLimitHandler handler) {
    RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();

    assertAllowed(handler.tryConsume(ctx, "api-limits"));
    assertAllowedWithRemaining(handler.tryConsume(ctx, "api-limits"), 0);
    assertRejectedWithRetryAfter(handler.tryConsume(ctx, "api-limits"), Duration.ofMinutes(1));
  }

  @Test
  void startsFreshInTheNextTest(InMemoryRateLimitHandler handler) {
    RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();
    assertAllowed(handler.tryConsume(ctx, "api-limits"));
  }
}
```

The second test passes because the extension resets every bucket before each test method. Without
that, rate limit suites pass in isolation and fail in order — the second method inherits the tokens
the first one spent.

## `FluxgateTestRules`

Builds rules through the production `RateLimitRule.Builder`, so the same validation applies: a rule
needs at least one band, and two bands of one rule may not share a derived key label.

| Method | Effect |
|--------|--------|
| `rule(id)` | Starts a rule: enabled, `PER_IP`, `REJECT_REQUEST` |
| `.perIp()` / `.perUser()` / `.perApiKey()` / `.global()` | Sets the `LimitScope` |
| `.perAttribute("tenantId")` | `CUSTOM` scope reading that `RequestContext` attribute |
| `.name(String)` | Rule name; defaults to the rule id |
| `.disabled()` | Marks the rule disabled, so the limiter skips it |
| `.waitForRefill()` | `OnLimitExceedPolicy.WAIT_FOR_REFILL` |
| `.band(window, capacity)` | Adds a band; key label is derived as `<capacity>-per-<windowSeconds>s` |
| `.band(window, capacity, label)` | Adds a band with an explicit label |
| `.build()` | The `RateLimitRule` alone |
| `.toRuleSet(ruleSetId)` | A `RateLimitRuleSet` containing just this rule |

Several rules in one rule set:

```java
RateLimitRuleSet ruleSet =
    FluxgateTestRules.ruleSet(
        "api-limits",
        FluxgateTestRules.rule("burst").perIp().band(Duration.ofSeconds(1), 5),
        FluxgateTestRules.rule("sustained").perIp().band(Duration.ofMinutes(1), 100));
```

`RateLimitRuleSet.Builder` requires a `KeyResolver`, so `FluxgateTestRules` supplies one. The
default is `new LimitScopeKeyResolver()`, matching a Spring Boot application's default. Two
overloads change it:

```java
// missing-key-behavior=REJECT
FluxgateTestRules.ruleSet("api-limits", MissingKeyBehavior.REJECT, rule);

// your own resolver
FluxgateTestRules.ruleSet("api-limits", myKeyResolver, rule);
```

## `InMemoryRateLimitHandler`

```java
// one rule set
InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSet(ruleSet);

// several
InMemoryRateLimitHandler handler = InMemoryRateLimitHandler.withRuleSets(List.of(first, second));

// and when the missing-rule-set behaviour matters
InMemoryRateLimitHandler handler =
    InMemoryRateLimitHandler.builder()
        .ruleSet(ruleSet)
        .onMissingRuleSet(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
        .build();
```

| Method | Purpose |
|--------|---------|
| `tryConsume(context, ruleSetId)` | One permit |
| `tryConsume(context, ruleSetId, permits)` | Weighted request |
| `reset()` | Discards every bucket |
| `reset(ruleSetId)` | Discards one rule set's buckets, leaving the others |
| `bucketCount()` | Live bucket count — a rule with two bands and three callers holds six |
| `getRuleSetIds()` | The rule sets served |
| `getRuleSetProvider()` | A `RateLimitRuleSetProvider` over the same rule sets, for wiring elsewhere |
| `getRateLimiter()` | The underlying `Bucket4jRateLimiter` |

The default `onMissingRuleSet` is `ALLOW`, because a test asking for an unknown rule set is usually
asserting that an unlimited path stays unlimited. Use `DENY` to mirror the fail-closed production
default, or `THROW` to make a typo in the rule set id fail loudly.

Instances are thread-safe, so a concurrency test can hammer one handler from several threads.

## `FluxgateInMemoryExtension`

Register it with `@RegisterExtension`, not `@ExtendWith`, because the rule sets are constructor
arguments.

| Factory | Use |
|---------|-----|
| `withRuleSet(ruleSet)` | One rule set |
| `withRuleSets(ruleSet...)` | Several |
| `withHandler(handler)` | A handler you built yourself |

A `static` field shares one handler — and one bucket cache — across the test class, which is what
you want: the reset before each test is what provides isolation. An instance field creates a handler
per test, which also works and costs a little more.

The extension injects any test, `@BeforeEach` or `@AfterEach` parameter of type
`InMemoryRateLimitHandler`. `getHandler()` reaches the same instance from anywhere else.

## `RateLimitAssertions`

`assertThat(response.isAllowed()).isTrue()` tells you a decision was wrong but not why. These
helpers fail with the whole decision in the message — remaining tokens, limit, retry delay, band.

| Assertion | Checks |
|-----------|--------|
| `assertAllowed(response)` | Allowed |
| `assertAllowedWithRemaining(response, n)` | Allowed and `remainingTokens == n` |
| `assertAllowedWithoutRule(response)` | Allowed with `remainingTokens == -1`, i.e. no rule was consulted |
| `assertRejected(response)` | Rejected |
| `assertRejectedWithRetryAfter(response, max)` | Rejected with `0 < retryAfterMillis <= max` |
| `assertRejectedWithRetryAfter(response, min, max)` | Rejected with the delay inside the range |
| `assertLimit(response, n)` | The advertised band capacity, which reaches the client as `RateLimit-Limit` |
| `assertBand(response, label)` | Which band produced the decision, by key label |
| `assertAllowed(result)` / `assertRejected(result)` | The same, on a core `RateLimitResult` |

The retry-delay assertions take a bound rather than an exact value, because the delay counts down
from the moment the bucket emptied — asserting equality makes the test fail whenever the machine is
slow.

There is deliberately **no** servlet assertion here: `spring-test` is not a testkit dependency, so
asserting on a `MockHttpServletResponse` belongs in the starter's own tests.

## Running the Testkit's Own Tests

FluxGate has two test tiers. The unit tier needs no infrastructure at all; the integration tier
(`*IntegrationTest` / `*IT`, run by failsafe) needs Redis and MongoDB.

```bash
# Unit tier only - no Docker, no Redis, no MongoDB
./mvnw test -pl fluxgate-testkit -am

# Unit tier + integration tier
./mvnw verify -pl fluxgate-testkit -am

# Integration tier skipped explicitly
./mvnw verify -pl fluxgate-testkit -am -DskipITs

# One class
./mvnw test -pl fluxgate-testkit -Dtest=InMemoryRateLimitHandlerTest
```

Use `-am` (or install the sibling modules first): without it Maven resolves
`fluxgate-redis-ratelimiter` from your local repository, which may be a stale release with the old
bucket key format.

### How the integration tier finds Redis and MongoDB

In this order:

1. a URI supplied through the environment,
2. a disposable [Testcontainers](https://testcontainers.com/) container,
3. **skip** — a JUnit assumption aborts the test. Integration tests never *fail* for want of
   infrastructure.

| Variable | Purpose |
|----------|---------|
| `FLUXGATE_REDIS_URI` | Use an existing Redis, e.g. `redis://localhost:6379` |
| `FLUXGATE_MONGO_URI` | Use an existing MongoDB, e.g. `mongodb://user:pass@localhost:27017/fluxgate?authSource=admin` |
| `FLUXGATE_MONGO_DB` | Database name to use with `FLUXGATE_MONGO_URI` |

```bash
FLUXGATE_REDIS_URI=redis://localhost:6379 \
FLUXGATE_MONGO_URI='mongodb://fluxgate:secret@localhost:27017/fluxgate?authSource=admin' \
FLUXGATE_MONGO_DB=fluxgate \
  ./mvnw verify -pl fluxgate-testkit -am
```

Pointing the integration tier at a shared development server is safe. Every rule set id, bucket key
and collection carries a per-JVM run id, and cleanup deletes only what that run created:
`RedisContainerSupport.deleteKeys` refuses any pattern that does not start with `fluxgate:`, and no
test calls `flushdb()` or drops a collection it did not create.

Local infrastructure, if you want it:

```bash
docker compose -f ../docker/redis-standalone.yml -f ../docker/mongo.yml up -d
```

Those compose files are labelled local development only and bind every port to `127.0.0.1`.

## Writing New Tests

### Naming

Integration tests **must** be named `*IntegrationTest` or `*IT`, otherwise failsafe does not pick
them up and surefire runs them in the unit tier, where the infrastructure is absent.

Method names follow `should<ExpectedBehavior>When<Condition>`:

```java
@Test
void shouldRejectRequestWhenRateLimitExceeded() { }

@Test
void shouldRefillTokensAfterWindowExpires() { }
```

### Structure

```java
@Test
void shouldRejectRequestWhenRateLimitExceeded(InMemoryRateLimitHandler handler) {
    // Arrange
    RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();
    handler.tryConsume(ctx, "api-limits");
    handler.tryConsume(ctx, "api-limits");

    // Act
    RateLimitResponse response = handler.tryConsume(ctx, "api-limits");

    // Assert
    assertRejectedWithRetryAfter(response, Duration.ofMinutes(1));
}
```

### Testing against real Redis

When you need the distributed path rather than the in-memory one, use the support classes in the
integration test sources as a template — `RedisContainerSupport` and `MongoContainerSupport`
implement the three-step resolution above. Keep two rules in mind:

- Scope every key to a run id (`RedisContainerSupport.newRunId()`), and delete only those keys.
- Bucket keys are `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>`, and the hash
  fields are `tokens` and `last_refill_micros`. Assertions written against the 0.3.x layout
  (`fluxgate:{ruleSetId}:…`, `last_refill_nanos`) will not match.

## Benchmarks

The JMH microbenchmarks moved out of this module into `fluxgate-benchmarks`
(`org.fluxgate.benchmark`), which is never published, so JMH is no longer on the testkit's classpath.
They open real Redis and MongoDB connections and nothing there participates in semantic versioning.

```bash
./mvnw -pl fluxgate-benchmarks -am -Pbenchmark -DskipTests clean package
java -jar fluxgate-benchmarks/target/benchmarks.jar
java -jar fluxgate-benchmarks/target/benchmarks.jar RedisRateLimiterBenchmark -f 1 -wi 3 -i 5
```

| Benchmark | Measures |
|-----------|----------|
| `StandaloneRateLimiterBenchmark` | In-memory Bucket4j consumption |
| `RedisRateLimiterBenchmark` | Redis-backed consumption, including the Lua round trip |
| `MongoRuleLoadingBenchmark` | Rule set loading from MongoDB |
| `MongoEventRecordingBenchmark` | Event recording throughput |

Results depend entirely on the Redis and MongoDB instances they reach, so a number from a laptop
against a container is not comparable with one from a cluster. Publish the environment alongside any
number taken from here.

## Troubleshooting

### Integration tests are skipped

That is the designed behaviour when neither `FLUXGATE_*_URI` nor a Docker daemon is available. Check
`docker info`, or supply a URI.

### `-pl fluxgate-testkit` fails to resolve a sibling module

Add `-am`, or run `./mvnw install -DskipTests` first. Without it Maven uses whatever version of
`fluxgate-core` / `fluxgate-redis-ratelimiter` is in `~/.m2`.

### Flaky rate limit tests

- Use a window long enough that nothing refills during the test (`Duration.ofHours(1)`) when you are
  asserting on exact counts.
- Reset between tests — `FluxgateInMemoryExtension` does it, a hand-rolled setup must call
  `handler.reset()`.
- Assert a retry-delay *range*, never an exact millisecond value.

### `FluxgateInMemoryExtension` cannot be resolved

Add `junit-jupiter` (and `assertj-core` for `RateLimitAssertions`) to your test scope. They are
`provided` here on purpose, so the testkit does not force a test framework on your project.

## Related Documentation

- [FluxGate Core](../fluxgate-core/README.md)
- [Redis Rate Limiter](../fluxgate-redis-ratelimiter/README.md) - Lua contract and key format
- [MongoDB Adapter](../fluxgate-mongo-adapter/README.md)
- [Contributing Guide](../CONTRIBUTING.md)
