# FluxGate Spring Boot 3 Starter

Spring Boot 3.x auto-configuration for FluxGate distributed rate limiting (Java 17+,
`jakarta.servlet`).

For Spring Boot 2.7 on `javax.servlet`, use
[fluxgate-spring-boot2-starter](../fluxgate-spring-boot2-starter/README.md). Its feature set is
identical; the only difference in your own code is the servlet import.

## Overview

This starter provides automatic configuration for FluxGate components in Spring Boot applications. It supports **role-separated deployments**, allowing you to run different components in different microservices.

### Deployment Patterns

| Pattern | Mongo | Redis | Filter | Use Case |
|---------|-------|-------|--------|----------|
| **Pod A** (Control-plane) | Yes | No | No | Rule management API |
| **Pod B** (Data-plane) | No | Yes | Yes | Rate limiting proxy |
| **Pod C** (Full Gateway) | Yes | Yes | Yes | All-in-one gateway |

## Installation

Add the starter to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>
```

### Optional Dependencies

The starter has optional dependencies. Include only what you need:

```xml
<!-- For MongoDB rule storage -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>

<!-- For Redis rate limiting -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>
```

## Configuration

### Full Configuration Reference

```yaml
fluxgate:
  # MongoDB Configuration (Control-plane)
  mongo:
    enabled: false                    # Enable MongoDB integration
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events   # unset by default
    ddl-auto: validate                # validate | create (create also builds the two indexes)
    event-retention: 30d              # TTL index on the event collection; 0 = manage it yourself

  # Redis Configuration (Data-plane)
  redis:
    enabled: false                    # Enable Redis integration
    uri: redis://localhost:6379       # comma-separated hosts for cluster
    mode: auto                        # standalone | cluster | auto (auto-detect)
    timeout-ms: 5000                  # Command timeout
    fail-fast: false                  # true fails startup when Redis is unreachable
    max-bucket-ttl: 7d                # Upper bound on a bucket's TTL

  # Rate Limiting Configuration
  ratelimit:
    enabled: true                     # Master switch: false registers neither filter nor aspect
    filter-enabled: false             # DEPRECATED and inert; use `enabled`
    mode: AUTO                        # AUTO | REDIS | IN_MEMORY (which limiter backs the handler)
    default-rule-set-id: null         # Default rule set ID
    filter-order: 1                   # Filter priority (lower = higher); unset = annotation value
    include-patterns:                 # URL patterns to rate limit; unset = /** (all paths)
      - /**
    exclude-patterns: []              # URL patterns to exclude
    case-sensitive-patterns: true     # Match include/exclude patterns case sensitively
    client-ip-header: X-Forwarded-For # Header for client IP
    trust-client-ip-header: false     # Trust only behind a sanitizing proxy
    trusted-proxies: []               # IPs or CIDRs allowed to set the forwarding header
    missing-key-behavior: FALLBACK_TO_IP  # FALLBACK_TO_IP or REJECT for identity scopes
    fail-on-missing-handler: false    # true fails startup rather than running with no rule provider
    identity:
      source: PRINCIPAL               # PRINCIPAL | HEADERS | PRINCIPAL_THEN_HEADERS
                                      # Header sources are an opt-in for a trusted proxy and
                                      # log a WARN at startup.
      user-id-header: X-User-Id
      api-key-header: X-API-Key
    collect-headers: false            # Copy allow-listed headers into the rate limit context
    header-allowlist: []              # Headers that may be copied when collect-headers is true
    log-query-string: false           # Put the raw query string into the logging MDC
    cost-header: null                 # Optional header carrying the request cost in permits
    max-cost: 1000                    # Upper bound applied to cost-header; must be > 0 when set
    include-headers: true             # Master switch over response.* headers
    missing-rule-behavior: DENY       # ALLOW or DENY when no rule matches
    failure-behavior: DENY            # ALLOW or DENY when limiter fails
    fallback:
      mode: NONE                      # NONE | IN_MEMORY (per-instance limits during a Redis outage)
      max-buckets: 100000             # Fallback bucket cache size
      expire-after-access: 1h         # Fallback bucket idle expiry
    wait-for-refill:
      enabled: false                  # Honour a rule's WAIT_FOR_REFILL policy
      max-wait-time-ms: 5000          # Reject rather than park longer than this
      max-concurrent-waits: 50        # Parked container threads allowed at once
    response:
      include-legacy-headers: true    # X-RateLimit-Limit/Remaining/Reset, Retry-After
      include-standard-headers: true  # RateLimit-Limit/Remaining/Reset, RateLimit-Policy
      content-type: application/problem+json   # 429 media type (RFC 9457)
      body-template: null             # Replaces the problem document entirely

  # Actuator Configuration
  actuator:
    health:
      enabled: true                   # Register the `fluxgate` health indicator
      include-endpoint-details: false # host:port, node counts and failure messages in the payload

  # Hot Reload Configuration
  reload:
    enabled: true                     # Enable hot reload of rule sets
    strategy: AUTO                    # AUTO | POLLING | PUBSUB | NONE
    cache:
      enabled: true
      ttl: 5m                         # Rule cache TTL
      max-size: 1000                  # Rule cache size
      negative-ttl: 5s                # Cache "rule set does not exist" for this long; 0 disables
    polling:
      interval: 30s
      initial-delay: 10s
    pubsub:
      channel: fluxgate:rule-reload
      retry-on-failure: true
      retry-interval: 5s
      backstop-polling-interval: 60s  # Polling backstop behind Pub/Sub; 0 disables
      secret:                         # HMAC-SHA256 secret; without it AUTO polls, PUBSUB fails
      allow-unsigned: false           # Development only: start without a secret (WARN)
      max-message-age: 5m             # Replay window for signed messages

  # Metrics Configuration
  metrics:
    enabled: true                     # Register the Micrometer recorder
    include-endpoint: true            # Tag metrics with the endpoint
    endpoint-normalization: true      # Numeric / UUID / 24-hex segments become {id}
    max-endpoint-tags: 1000           # Hard cap on distinct endpoint tag values

  # Resilience Configuration
  resilience:
    retry:
      enabled: true                   # Enable retry on failures
      max-attempts: 3                 # Maximum retry attempts
      initial-backoff: 100ms          # Initial backoff duration
      multiplier: 2.0                 # Exponential backoff multiplier
      max-backoff: 2s                 # Maximum backoff duration
      jitter-factor: 0.2              # Randomness added to each backoff
      retry-on-timeout: false         # Only safe for idempotent operations
    circuit-breaker:
      enabled: true                   # Enable circuit breaker
      failure-threshold: null         # Consecutive failures; set it to force the legacy rule
      sliding-window-size: 20         # Calls the failure rate is computed over
      failure-rate-threshold: 50      # Percent of failed calls that opens the circuit
      minimum-number-of-calls: 10     # Calls needed before the rate is evaluated
      wait-duration-in-open-state: 30s # Wait before half-open
      permitted-calls-in-half-open-state: 3
      fallback: FAIL_OPEN             # Deprecated: use ratelimit.failure-behavior
```

### Property Precedence

`application.yml` always wins. `default-rule-set-id`, `include-patterns`, `exclude-patterns` and
`filter-order` are read from `fluxgate.ratelimit.*` first; the matching `@EnableFluxgateFilter`
attribute is used only when the property is not set. The annotation therefore carries defaults for
code that ships without a yml, and an operator can override any of them without touching source.

Two keys are deprecated and inert: `fluxgate.ratelimit.filter-enabled` (the filter is registered by
`@EnableFluxgateFilter` and switched off by `fluxgate.ratelimit.enabled=false`) and
`fluxgate.resilience.circuit-breaker.fallback` (replaced by `fluxgate.ratelimit.failure-behavior`).

### Trusted Proxies

`trust-client-ip-header: true` without `trusted-proxies` trusts the forwarding header from every
client, so a caller can rotate `X-Forwarded-For` per request and bypass every `PER_IP` limit. The
starter logs one warning at startup in that case. List your load balancer or proxy addresses instead:

```yaml
fluxgate:
  ratelimit:
    trust-client-ip-header: true
    trusted-proxies:
      - 10.0.0.0/8
      - 2001:db8::/32
```

With a non-empty list the forwarded chain is walked from the right and the first hop that is not
itself a trusted proxy becomes the client IP; candidates must parse as an IPv4 or IPv6 literal.

### Health Status Mapping

The FluxGate health indicator reports the custom status `DEGRADED` when an enabled dependency
(Redis, MongoDB) is unhealthy. Spring Boot maps statuses it does not know to HTTP 200, so external
monitoring would see a degraded gateway as healthy. Add the mapping explicitly:

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          DEGRADED: 503
```

The `filterEnabled` and `aspectEnabled` details report whether a filter and aspect bean actually
exist in the context, not what a property claims.

A Redis-backed limiter also reports `rateLimiter.ready`. Redis being unavailable at startup no longer
fails the boot, so the limiter can be alive but not yet serving: while it is still connecting the
status is `DEGRADED` and `rateLimiter.failedAttempts` and `rateLimiter.lastError` say why. This is
distinct from `redis.status`, which only reflects whether Redis answers a PING - the limiter also has
to have loaded its Lua scripts before it can enforce a limit.

### Hardened Defaults

FluxGate's Spring Boot auto-configuration is fail-closed by default:

- `fluxgate.ratelimit.failure-behavior=DENY` returns HTTP 429 when the rate limiter throws an error.
- `fluxgate.ratelimit.missing-rule-behavior=DENY` denies requests when no rule is available.
- `fluxgate.ratelimit.trust-client-ip-header=false` ignores forwarded client IP headers unless explicitly enabled.

Set `failure-behavior: ALLOW`, `missing-rule-behavior: ALLOW`, or `trust-client-ip-header: true` only when that behavior is intentional and the deployment has compensating controls.

## Deployment Examples

### Pod A: Control-Plane (Mongo-only)

Use this configuration for a service that manages rate limit rules but doesn't enforce them.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://user:pass@mongo.internal:27017/fluxgate?authSource=admin
    database: fluxgate
  redis:
    enabled: false
  ratelimit:
    enabled: false
```

**Beans created:**
- `MongoClient`
- `MongoDatabase`
- `MongoRateLimitRuleRepository`
- `MongoRuleSetProvider`

**Use case:** Admin API for CRUD operations on rate limit rules.

```java
@RestController
@RequestMapping("/admin/rules")
public class RuleAdminController {

    @Autowired
    private MongoRateLimitRuleRepository ruleRepository;

    @PostMapping
    public void createRule(@RequestBody RateLimitRuleDocument rule) {
        ruleRepository.upsert(rule);
    }
}
```

---

### Pod B: Data-Plane (Redis + Filter)

Use this configuration for a rate limiting proxy that enforces limits but doesn't manage rules.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: false
  redis:
    enabled: true
    uri: redis://redis.internal:6379
  ratelimit:
    enabled: true
    enabled: true
    default-rule-set-id: api-gateway-rules
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
```

**Beans created:**
- `RedisRateLimiterConfig`
- `RedisTokenBucketStore`
- `RedisRateLimiter`
- `FluxgateRateLimitFilter`

**Important:** Without Mongo enabled, you need to provide a custom `RateLimitRuleSetProvider`:

```java
@Configuration
public class RuleSetConfig {

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider() {
        // Load rules from config server, cache, or other source
        return ruleSetId -> {
            // Custom implementation
            return Optional.of(loadRulesFromConfigServer(ruleSetId));
        };
    }
}
```

Or use an external rule cache that's populated by the control-plane.

---

### Pod C: Full Gateway (Mongo + Redis + Filter)

Use this configuration for an all-in-one API gateway.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://user:pass@mongo.internal:27017/fluxgate?authSource=admin
    database: fluxgate
  redis:
    enabled: true
    uri: redis://redis.internal:6379
  ratelimit:
    enabled: true
    enabled: true
    default-rule-set-id: default-limits
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /metrics
      - /actuator/*
    include-headers: true
```

**Beans created:**
- All MongoDB beans
- All Redis beans
- Filter and registration

**Automatic behavior:**
1. Rules loaded from MongoDB via `MongoRuleSetProvider`
2. Rate limiting enforced by `RedisRateLimiter`
3. Filter applies limits to matching requests
4. Standard rate limit headers added to responses

---

## Auto-Configuration Classes

### FluxgateMongoAutoConfiguration

**Condition:** `fluxgate.mongo.enabled=true`

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateMongoClient` | `MongoClient` | MongoDB connection |
| `fluxgateMongoDatabase` | `MongoDatabase` | FluxGate database |
| `fluxgateRuleCollection` | `MongoCollection<Document>` | Rules collection |
| `mongoRateLimitRuleRepository` | `MongoRateLimitRuleRepository` | Rule CRUD |
| `fluxgateKeyResolver` | `KeyResolver` | Default: client IP |
| `mongoRuleSetProvider` | `RateLimitRuleSetProvider` | Rule set loading |

Index creation on `ddl-auto=create` adds `{ruleSetId: 1}` and a unique `{ruleSetId: 1, id: 1}`;
failures are WARNed, not fatal. `fluxgateKeyResolver` honours
`fluxgate.ratelimit.missing-key-behavior`.

### FluxgateRedisAutoConfiguration

**Conditions:** `fluxgate.redis.enabled=true` and Lettuce (`io.lettuce.core.RedisClient`) on the
classpath.

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRedisConfig` | `RedisRateLimiterConfig` | Redis connection + Lua scripts. **`@Lazy`** |
| `fluxgateTokenBucketStore` | `RedisTokenBucketStore` | Token bucket operations. **`@Lazy`** |
| `fluxgateRuleSetStore` | `RedisRuleSetStore` | Deprecated Redis rule store. **`@Lazy`** |
| `fluxgateDelegateRateLimiter` (alias `redisRateLimiter`) | `LazyRedisRateLimiter` | The Redis limiter, connecting on first use |
| `redisHealthChecker` | `RedisHealthChecker` | Resolved per health check |
| `fluxgateRedisFailFastInitializer` | — | Only when `fluxgate.redis.fail-fast=true` |

**Redis being down no longer fails startup.** The three `@Lazy` beans are reached through
`ObjectProvider`, and `LazyRedisRateLimiter` makes one tolerant connect attempt at construction and
then reconnects in the background with exponential backoff to a 60s cap. Request threads are never
blocked waiting for a reconnect: while the limiter is disconnected a request fails fast and
`fluxgate.ratelimit.failure-behavior` (or `fallback.mode`) decides the outcome. Set
`fluxgate.redis.fail-fast=true` to restore the eager connect.

Because those beans are lazy, injecting `RedisRateLimiterConfig`, `RedisTokenBucketStore` or
`RedisRuleSetStore` directly into an eager bean of your own moves creation — and any connection
failure — to that injection point.

### FluxgateRateLimiterAutoConfiguration

**Condition:** always loaded.

This is the auto-configuration that makes `@EnableFluxgateFilter` work with no user code.

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `fluxgateDelegateRateLimiter` (alias `fluxgateInMemoryRateLimiter`) | `Bucket4jRateLimiter` | no `RateLimiter` bean exists | In-memory limiter, for `mode=IN_MEMORY` or `AUTO` without Redis |
| `fluxgateResilientRateLimiter` | `ResilientRateLimiter` | `@Primary`; a `ResilientExecutor` and a delegate limiter exist | Routes every call through retry + circuit breaker |
| `fluxgateLimitScopeKeyResolver` | `KeyResolver` | no `KeyResolver` bean exists | Honours `missing-key-behavior` |
| `fluxgateRateLimitEngine` | `RateLimitEngine` | a `RateLimiter` **and** a `RateLimitRuleSetProvider` exist | Wires `missing-rule-behavior` onto `OnMissingRuleSetStrategy` |
| `fluxgateRateLimitHandler` | `EngineBackedRateLimitHandler` | an engine exists and no `FluxgateRateLimitHandler` bean does | The library's default handler |
| `fluxgateLimiterFailureRecorder` | `ResilientRateLimiter.FailureRecorder` | Micrometer + `FluxgateMetrics` present | Feeds `fluxgate.limiter.failures` |

Every one of these is `@ConditionalOnMissingBean`, so defining your own bean of the same type replaces
it.

The rule set provider is resolved by looking for a bean named `delegateRuleSetProvider` first, and
falling back to a unique `RateLimitRuleSetProvider` bean when that name is absent — so a single
provider bean works under any name.

### FluxgateReloadAutoConfiguration

**Condition:** `fluxgate.reload.enabled=true` (the default).

Creates the rule cache, the reload strategy and the bucket reset handlers:

| Bean | Condition | Notes |
|------|-----------|-------|
| `ruleCache` (`CaffeineRuleCache`) | `fluxgate.reload.strategy != NONE` | Atomic single load per key, plus a negative cache (`cache.negative-ttl`) |
| `PollingReloadStrategy` | `strategy=POLLING` | Content-hash comparison per rule set |
| `CompositeReloadStrategy(pubsub, pollingBackstop)` | `strategy=AUTO` or `PUBSUB` | A dropped Pub/Sub message self-heals within `pubsub.backstop-polling-interval` |
| `NoOpReloadStrategy` | `strategy=NONE` | |
| `inMemoryBucketResetHandler` | in-memory limiter | `Bucket4jRateLimiter.reset` / `resetAll` |
| `RedisBucketResetHandler` | not `mode=IN_MEMORY` | Takes a `Supplier<RedisTokenBucketStore>`; skips with a WARN when Redis is unavailable |

**Listener order is a contract.** `CachingRuleSetProvider` is registered at
`AbstractReloadStrategy.ORDER_CACHE_INVALIDATION` (`-100`) and the bucket reset handler at
`ORDER_BUCKET_RESET` (`100`). Groups run in ascending order, and a failed group skips every higher
order with an ERROR log. Resetting buckets before invalidating the cache would make the next request
refill them from the stale rules.

### FluxgateFilterAutoConfiguration

**Conditions:**
- `fluxgate.ratelimit.enabled=true` (`filter-enabled` is deprecated and inert)
- `@EnableFluxgateFilter` present
- Web application context

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRateLimitFilter` | `FluxgateRateLimitFilter` | HTTP filter |
| `fluxgateRateLimitFilterRegistration` | `FilterRegistrationBean` | Servlet registration |

The filter resolves its collaborators through `ObjectProvider`, so bean ordering imposes no
constraints: `FluxgateRateLimitHandler`, `RateLimitResponseWriter`, `RequestContextCustomizer` and
`RateLimitDurationRecorder` are all optional. Note that **two** `RateLimitDurationRecorder` beans
silently disable the duration timer, because it is resolved with `getIfUnique()`.

When there is no `RateLimiter` bean at all, the filter falls back to `FluxgateRateLimitHandler.ALLOW_ALL`
and logs an actionable WARN. A Redis-only deployment with no rule set provider logs a startup **ERROR**;
set `fluxgate.ratelimit.fail-on-missing-handler=true` to fail the boot instead of starting unprotected.

### FluxgateResilienceAutoConfiguration

**Condition:** Always loaded (enabled/disabled via properties)

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRetryConfig` | `RetryConfig` | Retry configuration |
| `fluxgateCircuitBreakerConfig` | `CircuitBreakerConfig` | Circuit breaker configuration |
| `fluxgateRetryExecutor` | `RetryExecutor` | Retry executor (or NoOp if disabled) |
| `fluxgateCircuitBreaker` | `CircuitBreaker` | Circuit breaker (or NoOp if disabled) |
| `fluxgateResilientExecutor` | `ResilientExecutor` | Combined retry + circuit breaker |

---

## Rate Limiter Selection and Fallback

### Which limiter backs the handler

```yaml
fluxgate:
  ratelimit:
    mode: AUTO        # AUTO | REDIS | IN_MEMORY
```

| Mode | Limiter |
|------|---------|
| `AUTO` (default) | Redis when `fluxgate.redis.enabled=true`, otherwise the in-memory `Bucket4jRateLimiter` |
| `REDIS` | Always the distributed Redis limiter |
| `IN_MEMORY` | Always the in-memory limiter |

The choice is logged at INFO on startup. `IN_MEMORY` gives a **working but non-distributed** limiter:
buckets live in one instance, so N instances enforce N times the configured limit. It is meant for a
single instance, for development, and for tests.

```yaml
# A development profile with no infrastructure at all
fluxgate:
  redis:
    enabled: false
  ratelimit:
    mode: IN_MEMORY
    default-rule-set-id: api-limits
```

You still need rules. Supply them from code with a `RateLimitRuleSetProvider` bean, or use
`fluxgate-testkit` in tests.

### Fallback during a Redis outage

Without a fallback, `failure-behavior` is a binary choice between allowing everything and rejecting
everything. `IN_MEMORY` is usually better:

```yaml
fluxgate:
  ratelimit:
    fallback:
      mode: IN_MEMORY          # NONE (default) | IN_MEMORY
      max-buckets: 100000
      expire-after-access: 1h
```

`ResilientRateLimiter` then degrades to an in-memory limiter when the primary limiter fails or its
circuit is open. Limits stay in force **per instance**, which is a far better approximation of the
intended limit than either extreme. Watch `fluxgate.limiter.failures` with
`action=fallback_in_memory` to know when it is happening.

Note that a bucket evicted from the fallback cache starts full again, so `max-buckets` and
`expire-after-access` bound not only memory but also how much quota an outage can hand back. Keep
`expire-after-access` at least as long as your longest band window.

Decision order when the limiter fails:

```
limiter call
  ├─ retry (fluxgate.resilience.retry.*)
  ├─ circuit breaker (fluxgate.resilience.circuit-breaker.*)
  └─ still failing
       ├─ fallback.mode=IN_MEMORY  → in-memory limiter, tag action=fallback_in_memory
       └─ fallback.mode=NONE       → failure-behavior
                                       ALLOW → allowed, tag action=fail_open
                                       DENY  → 429,     tag action=fail_closed
```

The filter's own try/catch remains the outer net for anything that escapes this.

## The Rate Limit Handler

### The library default

With a `RateLimiter` and a `RateLimitRuleSetProvider` on the context, the starter registers
`org.fluxgate.spring.handler.EngineBackedRateLimitHandler`. `@EnableFluxgateFilter` needs **no
`handler` attribute**:

```java
@SpringBootApplication
@EnableFluxgateFilter
public class MyApplication { }
```

It wraps `RateLimitEngine`, implements both the two-argument and the three-argument (weighted)
`tryConsume`, and converts the result with `RateLimitResponse.from(result)`. Configuration errors
become **rejections** rather than propagating: a missing scope value under
`missing-key-behavior=REJECT` (`MissingRateLimitKeyException`) and an unbuildable rule
(`InvalidRuleConfigException`) both produce a rejected response with no wait time, logged at WARN once
per rule set id and at DEBUG afterwards.

`RateLimitEngine` is no longer dead code, and it is the only place `missing-rule-behavior` is decided.

### Writing your own

Define a `FluxgateRateLimitHandler` bean and it replaces the default:

```java
public interface FluxgateRateLimitHandler {

  RateLimitResponse tryConsume(RequestContext context, String ruleSetId);

  default RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    if (permits == 1) {
      return tryConsume(context, ruleSetId);
    }
    throw new UnsupportedOperationException(
        "Weighted permits are not supported by " + getClass().getName());
  }
}
```

Override the **three-argument** form to support `fluxgate.ratelimit.cost-header`.

### Weighted requests

```yaml
fluxgate:
  ratelimit:
    cost-header: X-RateLimit-Cost
    max-cost: 1000
```

The filter reads the header and consumes that many permits. Values below 1 and unparseable values are
treated as 1, and the value is capped by `max-cost`. `max-cost` must be positive.

> **Only expose `cost-header` to authenticated, trusted callers.** The client is deciding how much of
> its own quota to spend, which is harmless — but on a **shared** bucket scope (`GLOBAL`, or `PER_IP`
> behind NAT) one caller can exhaust everyone else's quota with a single expensive request. Where
> possible, have the server decide the cost per endpoint in a `RequestContextCustomizer` or a custom
> handler instead of trusting a header.

`@RateLimit(permits = 5)` does the same for the aspect.

## The `@RateLimit` Aspect

For cases the filter cannot express — a method behind a message listener, a scheduled job, a single
expensive operation inside an otherwise cheap endpoint.

```java
@RateLimit(ruleSetId = "expensive-ops", permits = 5)
public Report generateReport(String id) { ... }
```

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `ruleSetId` | `""` | Falls back to `fluxgate.ratelimit.default-rule-set-id` |
| `permits` | `1` | Weight of this invocation |
| `throwOnReject` | `false` | Throw instead of writing a 429 to the response |
| `waitForRefill` | `false` | Explicit override of the rule's policy |
| `maxWaitTimeMs` | `5000` | Cap on the wait |
| `maxConcurrentWaits` | `100` | **Deprecated and ignored** — the semaphore is aspect-wide; use `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` |

Three behaviours worth knowing:

- It shares `RequestContextFactory` with the filter, so `PER_USER`, `PER_API_KEY` and `CUSTOM` scopes
  behave identically in both modes. Before that it built a context containing only the client IP,
  which silently demoted every identity scope to per-IP limiting.
- It works **without an HTTP request**. The context then carries only `endpoint` (typically
  `Type.method`) and `method` (`INTERNAL`), so identity scopes have nothing to resolve and
  `missing-key-behavior` decides. A `GLOBAL` rule is usually what you want for internal invocations.
- On rejection it throws `org.fluxgate.spring.aop.RateLimitExceededException` when there is no
  response to write to, or whenever `throwOnReject = true`. The exception carries the
  `RateLimitResponse`:

```java
@RestControllerAdvice
public class RateLimitExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handle(RateLimitExceededException e) {
    RateLimitResponse result = e.getRateLimitResponse();
    long retryAfter = Math.max(1L, (result.getRetryAfterMillis() + 999) / 1000);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
        .body(Map.of("error", "Too Many Requests", "retryAfterSeconds", retryAfter));
  }
}
```

`fluxgate-samples/fluxgate-sample-standalone-java21` contains a working version of this advice.

## Hot Reload

```yaml
fluxgate:
  reload:
    enabled: true
    strategy: AUTO              # AUTO | POLLING | PUBSUB | NONE
    cache:
      ttl: 5m
      max-size: 1000
      negative-ttl: 5s          # 0 disables negative caching
    polling:
      interval: 30s
      initial-delay: 10s
    pubsub:
      channel: fluxgate:rule-reload
      backstop-polling-interval: 60s   # 0 disables the backstop
```

| Strategy | Behaviour |
|----------|-----------|
| `AUTO` | Pub/Sub when Redis is available, with a polling backstop; polling otherwise |
| `PUBSUB` | Redis Pub/Sub plus the polling backstop |
| `POLLING` | Content-hash polling only |
| `NONE` | No reload |

Three things changed in 0.4 that matter operationally:

- **A Pub/Sub message that cannot be parsed is WARNed and ignored.** It used to be treated as a full
  reload, so one malformed message wiped every bucket. `"*"` and `{"fullReload": true}` are the two
  deliberate full-reload payloads; a targeted message is `{"version": 1, "ruleSetId": "api-limits"}`.
- **`AUTO` and `PUBSUB` always run a polling backstop**, so a dropped message self-heals instead of
  leaving the data plane on stale rules indefinitely.
- **`strategy: NONE` no longer breaks the context.** It used to make the rule cache bean `null`,
  failing startup with `UnsatisfiedDependencyException`.

The rule cache collapses concurrent misses for one id into a single load and caches negative results
for `negative-ttl`, so a rule set that does not exist is not re-queried on every request.

Publishing a change from your control plane is `fluxgate-control-support`'s job:
`@NotifyRuleChange` publishes **after commit** inside a transaction, retries three times with jittered
backoff, and exposes `RuleChangeNotifierMetrics.getFailedNotifications()` — a rising count means the
data plane may be on stale rules.

> **Security.** The reload channel is a control-plane channel: `PUBLISH` access is equivalent to write
> access to your rules. FluxGate does not authenticate publishers. See [SECURITY.md](../SECURITY.md).

## Resilience

FluxGate provides built-in resilience features to handle transient failures in Redis/MongoDB
connections. Unlike 0.3.x, these are **wired**: `ResilientRateLimiter` routes every limiter call
through `ResilientExecutor`.

### Retry Strategy

Automatically retries failed operations with exponential backoff:

```yaml
fluxgate:
  resilience:
    retry:
      enabled: true
      max-attempts: 3
      initial-backoff: 100ms
      multiplier: 2.0
      max-backoff: 2s
```

**Behavior:**
```
Request → Fail → Wait 100ms → Retry → Fail → Wait 200ms → Retry → Success
```

Retryable exceptions:
- `FluxgateConnectionException` (Redis/MongoDB connection failures)
- `FluxgateTimeoutException` — **only** when `retry-on-timeout: true`

`retry-on-timeout` defaults to `false`: retrying a timed-out operation is only safe when it is
idempotent, and a rate limit consume is not — a retried consume can charge twice.

`jitter-factor` (default `0.2`) applies ±20% randomness to each capped backoff, clamped to
`[0, max-backoff]`, so a fleet recovering from an outage does not retry in lockstep.

Retry decision order: disabled → timeout gate → `FluxgateException.isRetryable()` (final, in both
directions) → the class allow-list.

### Circuit Breaker

Prevents cascading failures by stopping requests when the system is unhealthy:

```yaml
fluxgate:
  resilience:
    circuit-breaker:
      enabled: true                      # the default
      sliding-window-size: 20            # Calls the failure rate is computed over
      failure-rate-threshold: 50         # Percent of failed calls that opens the circuit
      minimum-number-of-calls: 10        # Calls needed before the rate is evaluated
      failure-threshold:                 # Unset. Set it to ALSO apply the legacy consecutive rule
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open-state: 3
```

**Two conditions can open the circuit:**

| Condition | When it applies |
|-----------|-----------------|
| Failure **rate** over a sliding window | Always, once `minimum-number-of-calls` have been recorded |
| **Consecutive** failures (`failure-threshold`) | Only when you set the property explicitly |

`failure-threshold` is an `Integer`, unset by default. Unset, the breaker uses the rate condition
alone, which is what catches an intermittent failure rate — a dependency failing half the time never
produces 5 consecutive failures, so the legacy rule alone never tripped. Set it when you want the
legacy behaviour as well.

**States:**
```
CLOSED ──(rate >= 50% over 20 calls, min 10)──> OPEN ──(30s)──> HALF_OPEN ──(success)──> CLOSED
                                                  ▲                  │
                                                  └──(trial fails)───┘
```

`HALF_OPEN` admits at most `permitted-calls-in-half-open-state` **concurrent** trial calls, gated by a
semaphore published before the state flip. It used to admit an unlimited number, which defeated the
point of the state. `getState()` no longer performs the transition as a side effect of being polled.

**What happens while the circuit is open** is decided by the call site, not by a strategy enum:
`CircuitBreaker.execute(...)` throws `CircuitBreakerOpenException`, and
`executeWithFallback(operation, action, fallback)` returns the caller's fallback. Nothing returns
`null` any more.

`fluxgate.resilience.circuit-breaker.fallback` (`FAIL_OPEN` / `FAIL_CLOSED`) is **deprecated and
inert**, kept only for binding compatibility. What requests get while the circuit is open comes from
`fluxgate.ratelimit.failure-behavior` and `fluxgate.ratelimit.fallback.mode` — see
[Fallback during a Redis outage](#fallback-during-a-redis-outage).

New observability on `DefaultCircuitBreaker`: `getRecordedCalls()` and `getFailureRate()`.

### Exception Hierarchy

FluxGate provides a clear exception hierarchy for error handling:

```
FluxgateException (abstract)
├── FluxgateConfigurationException      # Configuration errors (non-retryable)
│   ├── InvalidRuleConfigException
│   └── MissingConfigurationException
├── FluxgateConnectionException         # Connection failures (retryable)
│   ├── RedisConnectionException
│   └── MongoConnectionException
├── FluxgateOperationException          # Runtime errors
│   ├── RateLimitExecutionException
│   └── ScriptExecutionException
└── FluxgateTimeoutException            # Timeout errors (retryable)
```

---

## HTTP Filter Behavior

### Request Flow

```
Request → Filter → Check Exclusions → Load RuleSet → Rate Limit → Response
                         ↓                              ↓
                    Skip if excluded              429 if exceeded
```

### Rate Limit Headers

`fluxgate.ratelimit.include-headers` is the master switch; the two families switch independently with
`response.include-legacy-headers` and `response.include-standard-headers` (both `true` by default).
Headers are written on **allowed** responses too, so a well behaved client can pace itself instead of
discovering the limit only after a 429.

| Header | Family | Meaning |
|--------|--------|---------|
| `X-RateLimit-Limit` | legacy | Capacity of the band that produced the decision |
| `X-RateLimit-Remaining` | legacy | Tokens left in that band |
| `X-RateLimit-Reset` | legacy | Epoch **seconds** at which the bucket is full again |
| `RateLimit-Limit` | IETF | Capacity of the band that produced the decision |
| `RateLimit-Remaining` | IETF | Tokens left in that band |
| `RateLimit-Reset` | IETF | **Delta seconds** until the bucket is full again |
| `RateLimit-Policy` | IETF | Quota policy such as `100;w=60`; omitted when the window is unknown or sub-second |
| `Retry-After` | both | Rejections only. Whole seconds, rounded up, never `0` |

A value the limiter reports as unknown (`-1`) is **omitted** rather than written as a misleading
number.

**Successful Response (2xx):**
```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1701388800
RateLimit-Limit: 100
RateLimit-Remaining: 42
RateLimit-Reset: 35
RateLimit-Policy: 100;w=60
```

**Rate Limited Response (429):**
```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 45
RateLimit-Policy: 100;w=60
Retry-After: 45
Content-Type: application/problem+json;charset=UTF-8

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 45 seconds","retryAfterMillis":44120}
```

### Customizing the 429 body

The body is written by a `org.fluxgate.spring.filter.RateLimitResponseWriter`:

```java
public interface RateLimitResponseWriter {
  void write(HttpServletRequest request, HttpServletResponse response, RateLimitResponse result)
      throws IOException;
}
```

The default `ProblemDetailRateLimitResponseWriter` writes the RFC 9457 document above **without
needing Jackson**, checks `isCommitted()` first, and sets the charset explicitly.

Two ways to change it. A template, for a small tweak:

```yaml
fluxgate:
  ratelimit:
    response:
      content-type: application/json
      body-template: '{"error":"Too Many Requests","retryAfter":{retryAfterSeconds}}'
```

Placeholders: `{status}`, `{retryAfterSeconds}`, `{retryAfterMillis}`, `{remaining}`, `{limit}`.
Everything else in the template is copied verbatim.

Or a bean, which replaces the body entirely:

```java
@Bean
public RateLimitResponseWriter rateLimitResponseWriter(ObjectMapper mapper) {
    return (request, response, result) -> {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), Map.of(
            "error", "rate_limited",
            "retryAfterMillis", result.getRetryAfterMillis(),
            "limit", result.getLimit()));
    };
}
```

The writer is responsible for the status and the body; the headers are already written by then.

### Client IP Extraction

The filter extracts the client IP in this order:

1. `trust-client-ip-header=false` (the default): `request.getRemoteAddr()`. The forwarding header is
   ignored entirely.
2. `trust-client-ip-header=true` **with** `trusted-proxies`: the configured forwarding header is
   walked **from the right**, skipping hops that are themselves trusted proxies, and the first
   remaining candidate becomes the client IP.
3. `trust-client-ip-header=true` with an **empty** `trusted-proxies`: the **right-most** valid hop
   is used — it was appended by the immediate proxy and is harder to forge than the left-most
   client-supplied value. Nothing can be verified end-to-end, so configure `trusted-proxies` for any
   multi-hop deployment. One WARN is logged at startup in this configuration; treat it as a to-do,
   not as noise.

Every candidate must parse as an IPv4 or IPv6 literal of at most 45 characters; hostnames are never
resolved, so a header value cannot trigger a DNS lookup.

```yaml
fluxgate:
  ratelimit:
    client-ip-header: X-Real-IP        # Your proxy's IP header
    trust-client-ip-header: true       # Enable only behind a sanitizing proxy
    trusted-proxies:                   # Strongly recommended whenever trust is enabled
      - 10.0.0.0/8
```

Your proxy must also strip incoming client-supplied forwarding headers. FluxGate cannot distinguish a
header your load balancer appended from one the client sent.

When the forwarding chain cannot be expressed this way — a CDN with its own header, for example — set
the IP in a `RequestContextCustomizer` instead. See
[Request Context](../docs/en/customization/request-context.md).

---

## Multiple Filters (Java Config)

For advanced use cases, you can configure multiple rate limit filters with different priorities using Java Config:

```java
@Configuration
public class MultipleFiltersConfig {

    @Bean
    @Order(1)  // Higher priority (runs first)
    public FilterRegistrationBean<FluxgateRateLimitFilter> apiRateLimitFilter(
            FluxgateRateLimitHandler handler) {
        FluxgateRateLimitFilter filter = new FluxgateRateLimitFilter(
            handler,
            "api-rules",                           // ruleSetId
            new String[]{"/api/**"},               // includePatterns
            new String[]{"/api/health"},           // excludePatterns
            true,                                  // waitForRefillEnabled
            5000,                                  // maxWaitTimeMs
            100                                    // maxConcurrentWaits
        );

        FilterRegistrationBean<FluxgateRateLimitFilter> registration =
            new FilterRegistrationBean<>(filter);
        registration.setOrder(1);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    @Bean
    @Order(2)  // Lower priority (runs after api filter)
    public FilterRegistrationBean<FluxgateRateLimitFilter> adminRateLimitFilter(
            FluxgateRateLimitHandler handler) {
        FluxgateRateLimitFilter filter = new FluxgateRateLimitFilter(
            handler,
            "admin-rules",
            new String[]{"/admin/**"},
            new String[]{}
        );

        FilterRegistrationBean<FluxgateRateLimitFilter> registration =
            new FilterRegistrationBean<>(filter);
        registration.setOrder(2);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
```

### Filter Order in Spring Filter Chain

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Filter Chain                          │
├─────────────────────────────────────────────────────────────────┤
│  Order: Integer.MIN_VALUE                                       │
│  ├── CharacterEncodingFilter                                    │
│  ├── FormContentFilter                                          │
│                                                                 │
│  Order: Integer.MIN_VALUE + 100  ← FluxgateRateLimitFilter     │
│                                     (default order)             │
│                                                                 │
│  Order: -100                                                    │
│  ├── RequestContextFilter                                       │
│                                                                 │
│  Order: 0 (default)                                             │
│  ├── SecurityFilterChain (Spring Security)                      │
│  ├── CorsFilter                                                 │
│                                                                 │
│  Order: Ordered.LOWEST_PRECEDENCE                               │
│  └── Custom Filters                                             │
└─────────────────────────────────────────────────────────────────┘
```

**Why high priority?** Rate limiting should run **before** authentication/authorization to:
- Block malicious requests early (save resources)
- Defend against DDoS attacks
- Prevent unnecessary auth processing

---

## WAIT_FOR_REFILL Policy

Instead of immediately rejecting requests when rate limit is exceeded, you can configure the filter to wait for token refill:

```yaml
fluxgate:
  ratelimit:
    enabled: true
    wait-for-refill:
      enabled: true          # Enable wait mode
      max-wait-time-ms: 5000 # Maximum wait time (5 seconds)
      max-concurrent-waits: 100  # Limit concurrent waiting requests
```

### How it works

1. When a request exceeds the rate limit with `WAIT_FOR_REFILL` policy
2. Filter checks if wait time <= `max-wait-time-ms`
3. Tries to acquire a semaphore permit (prevents thread pool exhaustion)
4. Sleeps for the required time
5. Retries the rate limit check
6. Allows or rejects based on retry result

```java
// In RateLimitRule configuration
RateLimitRule rule = RateLimitRule.builder("wait-rule")
    .name("Wait for Refill Rule")
    .onLimitExceedPolicy(OnLimitExceedPolicy.WAIT_FOR_REFILL)  // <-- Enable wait
    // ... other config
    .build();
```

---

## RequestContext Customizer

Customize the `RequestContext` before rate limiting evaluation:

```java
@Configuration
public class FluxgateCustomConfig {

    @Bean
    public RequestContextCustomizer requestContextCustomizer() {
        return (builder, request) -> {
            // Override client IP from custom header (e.g., Cloudflare)
            String cfIp = request.getHeader("CF-Connecting-IP");
            if (cfIp != null) {
                builder.clientIp(cfIp);
            }

            // Add custom attributes for rate limiting decisions
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId != null) {
                builder.attribute("tenantId", tenantId);
            }

            // Remove sensitive headers before logging
            builder.getHeaders().remove("Authorization");
            builder.getHeaders().remove("Cookie");

            return builder;
        };
    }
}
```

### Combining Multiple Customizers

```java
@Configuration
public class FluxgateCustomConfig {

    @Bean
    public RequestContextCustomizer cloudflareIpCustomizer() {
        return (builder, request) -> {
            String cfIp = request.getHeader("CF-Connecting-IP");
            if (cfIp != null) {
                builder.clientIp(cfIp);
            }
            return builder;
        };
    }

    @Bean
    public RequestContextCustomizer tenantCustomizer() {
        return (builder, request) -> {
            builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
            return builder;
        };
    }
}
```

Multiple `RequestContextCustomizer` beans are automatically combined in order.

---

## Custom KeyResolver

Override the default IP-based key resolver:

```java
@Configuration
public class FluxgateConfig {

    @Bean
    public KeyResolver fluxgateKeyResolver() {
        return context -> {
            // Rate limit by API key instead of IP
            String apiKey = context.getApiKey();
            if (apiKey != null) {
                return new RateLimitKey(apiKey);
            }
            // Fallback to IP
            return new RateLimitKey(context.getClientIp());
        };
    }
}
```

---

## Custom RuleSetProvider

For data-plane pods without Mongo, provide rules via custom provider:

```java
@Configuration
public class RuleSetConfig {

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider() {
        // Example: Load from environment/config
        return ruleSetId -> {
            RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
                .label("100-per-minute")
                .build();

            RateLimitRule rule = RateLimitRule.builder("default-rule")
                .name("Default Rate Limit")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .keyStrategyId("clientIp")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(band)
                .ruleSetId(ruleSetId)
                .build();

            KeyResolver keyResolver = ctx -> new RateLimitKey(ctx.getClientIp());

            RateLimitRuleSet ruleSet = RateLimitRuleSet.builder(ruleSetId)
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .build();

            return Optional.of(ruleSet);
        };
    }
}
```

---

## Conditional Bean Examples

### Disable Filter Programmatically

```java
@Configuration
@ConditionalOnProperty(name = "app.rate-limiting.enabled", havingValue = "true")
public class ConditionalRateLimitConfig {
    // Only loaded when app.rate-limiting.enabled=true
}
```

### Environment-Specific Configuration

```yaml
# application-dev.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate-dev
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    enabled: false  # Disable rate limiting in dev

---
# application-prod.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://mongo-cluster.prod:27017/fluxgate?replicaSet=rs0
  redis:
    enabled: true
    uri: redis://redis-cluster.prod:6379
  ratelimit:
    enabled: true
    default-rule-set-id: production-limits
```

---

## Observability

FluxGate provides comprehensive observability features for production monitoring.

### Structured JSON Logging

FluxGate outputs structured JSON logs with correlation IDs for easy integration with ELK Stack, Splunk, or other log aggregation systems.

FluxGate populates the SLF4J **MDC**; it does not ship a logback configuration. Turn the MDC into
JSON with your own encoder, for example
[logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

**Example JSON log output:**

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "level": "DEBUG",
  "logger": "org.fluxgate.spring.filter.FluxgateRateLimitFilter",
  "message": "Request completed",
  "traceId": "abc123-def456",
  "ruleSetId": "api-limits",
  "endpoint": "/api/test",
  "method": "GET",
  "clientIp": "192.168.1.100",
  "rateLimitAllowed": "true",
  "remainingTokens": "9",
  "statusCode": "200",
  "durationMs": "3"
}
```

**MDC keys** (constants in `org.fluxgate.core.constants.FluxgateConstants.MdcKeys`):

| Key | Description |
|-----|-------------|
| `traceId` | Request correlation ID, taken from `X-Trace-Id` or generated |
| `ruleSetId` | Rule set ID applied |
| `method`, `endpoint` | HTTP method and the normalized path |
| `clientIp` | Resolved client IP |
| `protocol`, `serverPort` | Request protocol and port |
| `userAgent`, `referer` | Request headers, sanitised |
| `userId` | `X-User-Id`, sanitised and length-capped |
| `apiKey` | `X-API-Key`, **masked** |
| `rateLimitAllowed` | Whether the request was allowed |
| `remainingTokens` | Tokens remaining after the request |
| `retryAfterMs` | Advertised retry delay, on rejection |
| `statusCode`, `durationMs` | Response status and total time |
| `error`, `errorMessage` | Exception class and sanitised message, when the limiter failed |
| `queryString` | Only when `fluxgate.ratelimit.log-query-string=true` |

"Request completed" is logged at **DEBUG**, not INFO — at INFO it doubled every access log line.

Every value derived from a request header passes through `LogSanitizer`, which strips control
characters (closing a CRLF log-injection path) and caps the length. The MDC entries FluxGate found on
entry are restored when the request completes, so it no longer clears MDC context owned by other
components.

---

### Prometheus Metrics

FluxGate automatically exposes Micrometer-based metrics when `spring-boot-starter-actuator` is on the classpath.

**Dependencies:**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**Configuration:**

```yaml
fluxgate:
  metrics:
    enabled: true  # default: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
```

**Available Metrics:**

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `fluxgate_requests_total` | Counter | `result`, `rule_set`, `endpoint`, `method` | Rate limit decisions. The meter is `fluxgate.requests`; Prometheus appends `_total` |
| `fluxgate_requests_duration_seconds` | Timer | `rule_set`, `endpoint`, `method` | Time spent deciding |
| `fluxgate_limiter_failures_total` | Counter | `rule_set`, `endpoint`, `action`, `exception` | Limiter dependency failures |
| `fluxgate_tokens_remaining` | Gauge | `rule_set`, `endpoint` | Remaining tokens in the bucket |

> **The untagged `fluxgate.requests.total` meter was removed.** Under Micrometer's Prometheus naming it
> exported as `fluxgate_requests_total` and collided with `fluxgate.requests`, which carries `result`.
> Sum over the tag instead: `sum(rate(fluxgate_requests_total{result=~"allowed|rejected"}[5m]))`.

`fluxgate.limiter.failures` `action` values to alert on:

| `action` | Meaning |
|----------|---------|
| `fail_open` | Requests are passing through unlimited — alert on any non-zero value in production |
| `fail_closed` | Requests are rejected because a dependency is down — a dependency incident |
| `fallback_in_memory` | Limits are enforced per instance, not globally |

**Example Prometheus output:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/users/{id}",method="GET",result="allowed",rule_set="api-limits"} 142.0
fluxgate_requests_total{endpoint="/api/users/{id}",method="GET",result="rejected",rule_set="api-limits"} 7.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/users/{id}",rule_set="api-limits"} 8.0
```

`endpoint` values are normalized — numeric, UUID and 24-hex path segments become `{id}` — and the
number of distinct values is capped by `fluxgate.metrics.max-endpoint-tags` (default 1000) through a
`MeterFilter.maximumAllowableTags` deny filter. Without that, a path-parameter heavy API grew the
meter registry and the heap without bound. Turn normalization off with
`fluxgate.metrics.endpoint-normalization: false`, or drop the tag entirely with
`fluxgate.metrics.include-endpoint: false`.

Gauges are backed by per-tag-set `AtomicLong` holders registered once with `strongReference(true)`.
The previous `registry.gauge(name, tags, long)` form held an autoboxed `Long` weakly, so the gauge
became `NaN` after a GC.

Ready-made assets: [`docker/grafana/fluxgate-dashboard.json`](../docker/grafana/fluxgate-dashboard.json)
(10 panels) and [`docker/prometheus/fluxgate-alerts.yml`](../docker/prometheus/fluxgate-alerts.yml)
(3 recording rules, 6 alerts).

**Disable metrics:**

```yaml
fluxgate:
  metrics:
    enabled: false
```

---

### Health Checks

FluxGate provides detailed health indicators for Redis and MongoDB connections via Spring Boot Actuator.

**Configuration:**

```yaml
management:
  endpoint:
    health:
      show-details: always      # Show detailed health info
      show-components: always   # Show component breakdown
```

**Access:** `GET /actuator/health`

**Example Response:**

```json
{
  "status": "UP",
  "components": {
    "fluxgate": {
      "status": "UP",
      "details": {
        "rateLimitingEnabled": true,
        "filterEnabled": true,
        "mongo.status": "UP",
        "mongo.message": "MongoDB is healthy",
        "mongo.database": "fluxgate",
        "mongo.latency_ms": 2,
        "mongo.version": "7.0.0",
        "mongo.connections.current": 10,
        "mongo.connections.available": 100,
        "redis.status": "UP",
        "redis.message": "Redis cluster is healthy",
        "redis.mode": "CLUSTER",
        "redis.latency_ms": 1,
        "redis.cluster_state": "ok",
        "redis.cluster_nodes": 6,
        "redis.cluster_masters": 3,
        "redis.cluster_replicas": 3
      }
    }
  }
}
```

**Health Check Details:**

| Component | Details Provided |
|-----------|------------------|
| **MongoDB** | database, version, latency_ms, connections (current/available), replicaSet info |
| **Redis** | mode (STANDALONE/CLUSTER), latency_ms, cluster_state, cluster_nodes, masters/replicas |

**Health Status Values:**

| Status | Description |
|--------|-------------|
| `UP` | Component is healthy |
| `DOWN` | Component has failed |
| `DEGRADED` | Some components have issues |
| `DISABLED` | Component is not enabled |

---

## Troubleshooting

### No RateLimiter Bean

**Warning:** the filter is running with `FluxgateRateLimitHandler.ALLOW_ALL` — every request passes
through unlimited.

**Solutions**, in order of preference:
```yaml
fluxgate:
  redis:
    enabled: true            # distributed limiting
# or, for a single instance / development:
fluxgate:
  ratelimit:
    mode: IN_MEMORY
```
Or define your own `FluxgateRateLimitHandler` bean.

### No RuleSetProvider

**Warning:** `No RateLimitRuleSetProvider bean found`, and a startup **ERROR** in a Redis-only
deployment — without a provider there are no rules, so nothing is enforced.

**Solutions:**
1. Enable Mongo: `fluxgate.mongo.enabled=true`
2. Define a `RateLimitRuleSetProvider` bean (a lambda returning an `Optional<RateLimitRuleSet>` is
   enough)

To refuse to start rather than start unprotected, set
`fluxgate.ratelimit.fail-on-missing-handler=true` (default `false`).

### Filter Not Applied

**Checklist:**
1. `fluxgate.ratelimit.enabled=true` — note that `filter-enabled` is deprecated and inert
2. `@EnableFluxgateFilter` is on a configuration class
3. A `RateLimiter` bean exists (Redis enabled, or `mode=IN_MEMORY`)
4. The **normalized** request path matches `include-patterns`. The default is all paths; `/*` matches
   a single segment only
5. The normalized path is not in `exclude-patterns`
6. `default-rule-set-id` is configured, or the rule set id is resolved some other way
7. A `RateLimitRuleSetProvider` returns a rule set for that id — check for the "rule set not found"
   WARN

### Rate limits are not what I configured

| Symptom | Likely cause |
|---------|--------------|
| Limits are N times too permissive across N instances | `mode=IN_MEMORY`, or `AUTO` with Redis disabled. Check the startup INFO line naming the limiter |
| Every caller got a full quota after the upgrade | Expected: bucket keys changed shape in 0.4. See [Migrating to 0.4](../docs/en/operations/migration-0.4.md) |
| Only one of a rule's two bands seems enforced | Pre-0.4 behaviour: unlabelled bands collided on one key. Fixed by the derived band key label |
| A quota longer than a day resets every day | Pre-0.4 behaviour: the 24h TTL cap. The cap is gone |
| Anonymous callers get the authenticated tier's quota | `missing-key-behavior=FALLBACK_TO_IP` keeps the rule's limits. Use `REJECT`, or a separate rule set |
| A client picks its own bucket with `X-User-Id` | Identity headers are client input. Set `userId` from the authenticated principal in a `RequestContextCustomizer` — see [SECURITY.md](../SECURITY.md) |

### Debugging

Enable debug logging:
```yaml
logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.spring: DEBUG
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Application                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateFilterAutoConfiguration                     │   │
│  │  @ConditionalOnProperty("fluxgate.ratelimit.enabled")            │   │
│  │  ObjectProvider<FluxgateRateLimitHandler>                        │   │
│  │                                                                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │ FluxgateRateLimitFilter                                     │ │   │
│  │  │   - Extracts client IP                                      │ │   │
│  │  │   - Loads RuleSet from Provider                             │ │   │
│  │  │   - Calls RateLimiter.tryConsume()                          │ │   │
│  │  │   - Returns 429 or passes through                           │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                              │                                           │
│                              │ depends on                                │
│                              ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateRedisAutoConfiguration                      │   │
│  │  @ConditionalOnProperty("fluxgate.redis.enabled")                │   │
│  │                                                                   │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │ RedisConfig     │  │ TokenBucketStore│  │ RedisRateLimiter│  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateMongoAutoConfiguration                      │   │
│  │  @ConditionalOnProperty("fluxgate.mongo.enabled")                │   │
│  │                                                                   │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │ MongoClient     │  │ RuleRepository  │  │ RuleSetProvider │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Related Modules

- [`fluxgate-core`](../fluxgate-core) - Core interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter) - MongoDB integration
- [`fluxgate-redis-ratelimiter`](../fluxgate-redis-ratelimiter) - Redis rate limiter

## License

MIT License
