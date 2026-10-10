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
    ddl-auto: validate                # validate | create. validate needs a unique {ruleSetId:1, id:1} index;
                                      # create builds it (and id_1) and fails startup on duplicates
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
    # filter-order: 1                 # Unset by default; the effective 1 is @EnableFluxgateFilter#filterOrder()
    include-patterns:                 # URL patterns to rate limit; unset = /** (all paths)
      - /**
    exclude-patterns: []              # URL patterns to exclude
    case-sensitive-patterns: true     # Match include/exclude patterns case sensitively
    client-ip-header: X-Forwarded-For # Header for client IP
    trust-client-ip-header: false     # Trust only behind a sanitizing proxy
    trusted-proxies: []               # IPs or CIDRs allowed to set the forwarding header
    missing-key-behavior: FALLBACK_TO_IP  # FALLBACK_TO_IP or REJECT for identity scopes
    fail-on-missing-handler: false    # true fails startup when no handler can enforce limits (see Troubleshooting)
    rule-sets: []                     # YAML rule sets, see docs/en/guides/yaml-rule-sets.md
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
    include-headers: true             # Master switch over response.* headers (Retry-After on a rejection is always sent)
    missing-rule-behavior: DENY       # ALLOW or DENY when no rule matches
    failure-behavior: DENY            # ALLOW or DENY when the limiter fails (not for invalid rule sets: always 503)
    fallback:
      mode: NONE                      # NONE | IN_MEMORY (per-instance limits during a Redis outage)
      max-buckets: 100000             # Fallback bucket cache size
      expire-after-access: 1h         # Fallback bucket idle expiry
    wait-for-refill:
      enabled:                        # Unset: rule WAIT_FOR_REFILL ignored, @RateLimit(waitForRefill) waits; true: both wait; false: nothing waits
      max-wait-time-ms: 5000          # Reject rather than park longer than this
      max-concurrent-waits: 50        # Parked container threads allowed at once
    response:
      include-legacy-headers: true    # X-RateLimit-Limit/Remaining/Reset
      include-standard-headers: true  # RateLimit-Limit/Remaining/Reset, RateLimit-Policy
      content-type: application/problem+json   # 429 media type (RFC 9457)
      body-template: null             # Replaces the problem document entirely (429, and 503 unless set below)
      unavailable-body-template: null # Body of the 503 when rate limiting is unavailable; defaults to body-template

  # Actuator Configuration
  actuator:
    health:
      enabled: true                   # Register the `fluxgate` health indicator
      include-endpoint-details: false # host:port, node counts and failure messages in the payload
      degraded-http-status: 503       # HTTP status of the DEGRADED health status; 0 adds no mapping

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
      secret:                         # HMAC-SHA256 secret; without it (or allow-unsigned) AUTO polls, PUBSUB fails
      allow-unsigned: false           # Development only: start without a secret (WARN)
      max-message-age: 60s            # Replay window for signed messages (applied when secret is set)
      accept-legacy-signed: true      # Accept signed v1 messages from pre-0.4 publishers; false once all sign v2

  # Control-plane notifier (fluxgate-control-support; active only when control.redis.uri is set)
  control:
    redis:
      uri: redis://localhost:6379     # Setting it activates the notifier
      channel: fluxgate:rule-reload   # Must equal reload.pubsub.channel
      timeout: 5s
    source: fluxgate-control          # Source identifier carried in each message
    secret:                           # HMAC-SHA256 secret, required; equals reload.pubsub.secret
    allow-unsigned: false             # Development only: publish without a secret (WARN)

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
      failure-threshold: null         # Consecutive failures; set it to also apply the legacy rule
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
(Redis, MongoDB) is unhealthy. Spring Boot neither aggregates nor maps a status it does not know: its
default `management.endpoint.health.status.order` leaves `DEGRADED` out, so `/actuator/health` and the
readiness group reported `UP`, and an unmapped status answers HTTP 200. FluxGate adds both for you:
`FluxgateHealthStatusEnvironmentPostProcessor` contributes, as the lowest-precedence property source,
`management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown` (unless you set an
order yourself) and these `management.endpoint.health.status.http-mapping` defaults:

| Status | HTTP status |
|--------|-------------|
| `DOWN`, `OUT_OF_SERVICE` | 503 (Spring Boot's own defaults, kept) |
| `DEGRADED` | `fluxgate.actuator.health.degraded-http-status`, default `503` |

Only statuses you have not mapped yourself are added, so your own `http-mapping` entries and
`HttpCodeStatusMapper` bean win; health groups without their own mapping or order (`liveness`,
`readiness`) inherit the result, so a readiness group that includes `fluxgate` answers 503 while it is
degraded. An `order` of your own - for the endpoint or a group - is kept as is; when it does not list
`degraded`, FluxGate logs a WARN at startup because that aggregation ignores a degraded FluxGate. Set
`degraded-http-status: 0` (or a negative value) to keep the order but add no mapping (`DEGRADED` then
aggregates and answers 200), or `fluxgate.actuator.health.enabled=false` to add nothing; Spring
Boot's rule then applies again (a non-empty `http-mapping` of your own replaces its defaults, so
include `DOWN: 503` yourself).

A Redis Cluster that answers `PING` but reports `cluster_state` other than `ok`, failing slots, or an
unreadable node list is reported as `redis.status: DOWN` (the component is then `DEGRADED`). The health check does not create the lazy Redis connection.

The `filterEnabled` and `aspectEnabled` details report whether a filter and aspect bean actually
exist in the context, not what a property claims.

A Redis-backed limiter also reports `rateLimiter.ready`. Redis being unavailable at startup no longer
fails the boot, so the limiter can be alive but not yet serving: while it is still connecting the
status is `DEGRADED` and `rateLimiter.failedAttempts` and `rateLimiter.lastError` (only with
`include-endpoint-details`, since it carries the Redis host) say why. This is
distinct from `redis.status`, which only reflects whether Redis answers a PING - the limiter also has
to have loaded its Lua scripts before it can enforce a limit.

### Hardened Defaults

FluxGate's Spring Boot auto-configuration is fail-closed by default:

- `fluxgate.ratelimit.failure-behavior=DENY` rejects requests when the rate limiter throws an error. The filter and the aspect answer **HTTP 503**, not 429, because the client did not exceed a limit (`Retry-After` only when the wait is known). `failure-behavior` covers limiter failures only: a rule set that cannot be built (`InvalidRuleConfigException`) is answered with 503 under `ALLOW` too.
- `fluxgate.ratelimit.missing-rule-behavior=DENY` rejects requests when no rule is available, also with 503.
- `fluxgate.ratelimit.trust-client-ip-header=false` ignores forwarded client IP headers unless explicitly enabled.
- The `FluxgateRateLimitFilter` constructors without explicit security settings (4-, 7- and 8-argument) and the 2-argument `RateLimitAspect` constructor follow the same defaults: they ignore forwarding headers and fail closed. Before 0.4.0 they trusted `X-Forwarded-For` and failed open.
- Invalid configuration fails startup (non-positive timeouts, TTLs, intervals and sizes; unknown YAML `zone-id`; duplicate YAML rule ids; zero windows).

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

**Beans created** by `FluxgateMongoAutoConfiguration` (see the [table below](#fluxgatemongoautoconfiguration)):
- `fluxgateMongoClientHolder` (`FluxgateMongoClientHolder`; FluxGate-private client, not a `MongoClient` bean)
- `fluxgateMongoDatabase` (`MongoDatabase`) and `fluxgateRuleCollection`
- `rateLimitRuleRepository` (`RateLimitRuleRepository`, implemented by `MongoRateLimitRuleRepository`)
- `delegateRuleSetProvider` (`RateLimitRuleSetProvider`, backed by `MongoRuleSetProvider`)

`fluxgate.ratelimit.enabled=false` keeps the filter and the aspect away; it does not switch off
`FluxgateRateLimiterAutoConfiguration`, which is always loaded and still registers an (unused)
in-memory limiter, engine and handler.

**Use case:** Admin API for CRUD operations on rate limit rules.

```java
@RestController
@RequestMapping("/admin/rules")
public class RuleAdminController {

    @Autowired
    private RateLimitRuleRepository ruleRepository;

    @PostMapping
    public void createRule(@RequestBody RateLimitRuleDocument rule) {
        ruleRepository.save(RateLimitRuleConverter.toDomain(rule));
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
    enabled: true            # the filter itself is registered by @EnableFluxgateFilter
    default-rule-set-id: api-gateway-rules
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
```

**Beans created:**
- `fluxgateRedisConfig` (`RedisRateLimiterConfig`) and `fluxgateTokenBucketStore` (`RedisTokenBucketStore`), both `@Lazy`
- `fluxgateDelegateRateLimiter` (`LazyRedisRateLimiter`, alias `redisRateLimiter`), wrapped by the `@Primary` `fluxgateResilientRateLimiter`
- `fluxgateRateLimitFilter` and its registration (with `@EnableFluxgateFilter`)

**Important:** Without Mongo enabled, the rules must come from somewhere else: declare them under
`fluxgate.ratelimit.rule-sets` ([YAML rule sets](../docs/en/guides/yaml-rule-sets.md)) or provide a
`RateLimitRuleSetProvider` bean. With neither, a stopgap handler logs a startup ERROR and applies
`failure-behavior` to every request (see [No RuleSetProvider](#no-rulesetprovider)).

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
    enabled: true            # the filter itself is registered by @EnableFluxgateFilter
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

**Conditions:** `fluxgate.mongo.enabled=true` and the MongoDB driver (`com.mongodb.client.MongoClient`)
on the classpath.

Creates (each one backs off when the condition column finds your own bean):
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `fluxgateMongoClientHolder` | `FluxgateMongoClientHolder` | no `FluxgateMongoClientHolder` bean | FluxGate's own MongoDB client. It is **not** a `MongoClient` bean, so Spring Boot's and Spring Data's client are never replaced. A `MongoClient` bean you name `fluxgateMongoClient` is still used (and not closed) |
| `fluxgateMongoDatabase` | `MongoDatabase` | no bean of that name | FluxGate database |
| `fluxgateRuleCollection` | `MongoCollection<Document>` | no bean of that name | Rules collection; applies `ddl-auto` |
| `rateLimitRuleRepository` | `RateLimitRuleRepository` (a `MongoRateLimitRuleRepository`) | no `RateLimitRuleRepository` bean | Rule CRUD |
| `fluxgateKeyResolver` | `KeyResolver` (a `LimitScopeKeyResolver`) | no `KeyResolver` bean | Key per rule scope (`GLOBAL`, `PER_IP`, `PER_USER`, ...); honours `missing-key-behavior` |
| `delegateRuleSetProvider` | `RateLimitRuleSetProvider` (a `MongoRuleSetProvider` behind a lazy metrics wrapper) | no bean named `delegateRuleSetProvider` | Rule set loading; YAML `rule-sets` are composed in front of it |
| `fluxgateEventCollection` | `MongoCollection<Document>` | `fluxgate.mongo.event-collection` set | Event collection. Like the rule collection it must already exist under `ddl-auto=validate` (startup fails otherwise) and is created under `create`; only the `event-retention` TTL index is always created, whatever `ddl-auto` says |
| `mongoMetricsRecorder` | `MongoRateLimitMetricsRecorder` | `fluxgate.mongo.event-collection` set | Writes rate limit events to MongoDB |
| `mongoHealthChecker` | `FluxgateHealthIndicator.MongoHealthChecker` | no `MongoHealthChecker` bean | Feeds the `mongo.*` health details |

With `ddl-auto=create` the rule collection is created and `MongoRateLimitRuleRepository#ensureIndexes()`
builds the unique `ruleSetId_1_id_1_unique` and `id_1` indexes; duplicate `(ruleSetId, id)` pairs or a
conflicting index **fail startup** with the pairs listed. With `ddl-auto=validate` (the default) startup
fails unless a unique index on `{ruleSetId: 1, id: 1}` exists (any name); the message contains the
`createIndex` command. A unique index that is `sparse`, has a `partialFilterExpression` or a
collation other than `simple` does not enforce uniqueness for every rule and is rejected by name. The auto-configuration runs after Spring Boot's `MongoAutoConfiguration`.

### FluxgateRedisAutoConfiguration

**Conditions:** `fluxgate.redis.enabled=true` and Lettuce (`io.lettuce.core.RedisClient`) on the
classpath.

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `fluxgateRedisConfig` | `RedisRateLimiterConfig` | no `RedisRateLimiterConfig` bean | Redis connection + Lua scripts. **`@Lazy`** |
| `fluxgateTokenBucketStore` | `RedisTokenBucketStore` | no `RedisTokenBucketStore` bean | Token bucket operations. **`@Lazy`** |
| `fluxgateRuleSetStore` | `RedisRuleSetStore` | no `RedisRuleSetStore` bean | Deprecated Redis rule store. **`@Lazy`** |
| `fluxgateDelegateRateLimiter` (alias `redisRateLimiter`) | `LazyRedisRateLimiter` | no `RateLimiter` bean, and `fluxgate.ratelimit.mode` is not `IN_MEMORY` | The Redis limiter, connecting on first use |
| `redisHealthChecker` | `FluxgateHealthIndicator.RedisHealthChecker` | no `RedisHealthChecker` bean | Never creates the lazy connection: reports DOWN until it exists |
| `fluxgateRedisFailFastInitializer` | `FluxgateRedisAutoConfiguration.RedisFailFastInitializer` | `fluxgate.redis.fail-fast=true` | Marker bean that forces the connection at startup |

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
| `fluxgateDelegateRateLimiter` (alias `fluxgateInMemoryRateLimiter`) | `Bucket4jRateLimiter` | no `RateLimiter` bean exists | In-memory limiter, for `mode=IN_MEMORY`, `AUTO` without Redis, or `REDIS` without a Redis limiter (with a WARN) |
| `fluxgateFallbackRateLimiter` | `Bucket4jRateLimiter` | `fallback.mode=IN_MEMORY`, a `ResilientExecutor` and a `fluxgateDelegateRateLimiter` exist, and no `Bucket4jRateLimiter` bean does | The in-memory limiter `ResilientRateLimiter` degrades to |
| `fluxgateResilientRateLimiter` | `ResilientRateLimiter` | `@Primary`; a `ResilientExecutor` and a `fluxgateDelegateRateLimiter` exist, and retry, the circuit breaker or `fallback.mode=IN_MEMORY` is enabled | Routes every call through retry + circuit breaker |
| `fluxgateWaitPermits` | `FluxgateWaitPermits` | no `FluxgateWaitPermits` bean | The one wait semaphore (`wait-for-refill.max-concurrent-waits`) shared by the filter and the aspect |
| `fluxgateLimitScopeKeyResolver` | `KeyResolver` (a `LimitScopeKeyResolver`) | no `KeyResolver` bean exists | Honours `missing-key-behavior` |
| `fluxgatePathPatternMatcher` | `PathPatternMatcher` (a `SpringAntPathMatcherAdapter`) | no `PathPatternMatcher` bean | Ant matching for rule paths; honours `case-sensitive-patterns` |
| `propertiesRuleSetProvider` | `PropertiesRuleSetProvider` | `fluxgate.ratelimit.rule-sets` is not empty and no bean named `propertiesRuleSetProvider` or `delegateRuleSetProvider` exists | Serves the YAML rule sets |
| `fluxgateYamlRuleSetComposer` | `BeanPostProcessor` | `fluxgate.ratelimit.rule-sets` is not empty | Composes the YAML rule sets in front of a `delegateRuleSetProvider` (MongoDB or your own) |
| `fluxgateRateLimitEngine` | `RateLimitEngine` | no `RateLimitEngine` bean; a `RateLimiter` **and** a `RateLimitRuleSetProvider` exist | Wires `missing-rule-behavior` onto `OnMissingRuleSetStrategy` |
| `fluxgateRateLimitHandler` | `FluxgateRateLimitHandler` (an `EngineBackedRateLimitHandler`) | an engine exists and no `FluxgateRateLimitHandler` bean does | The library's default handler |
| `fluxgateMissingRuleSetProviderRateLimitHandler` | `FluxgateRateLimitHandler` (a `MissingRuleSetProviderRateLimitHandler`) | a `RateLimiter`, no `FluxgateRateLimitHandler` and no `RateLimitRuleSetProvider` bean, and `fluxgate.redis.enabled=true` or `fluxgate.ratelimit.mode` set | Logs a startup ERROR and applies `failure-behavior`; fails the boot under `fail-on-missing-handler=true` |

Defining your own bean of the type in the condition column replaces the default; the resilient
wrapper and the YAML composer have no such opt-out. `fluxgateLimiterFailureRecorder`, which feeds
`fluxgate.limiter.failures`, is registered by
[`FluxgateMetricsAutoConfiguration`](#fluxgatemetricsautoconfiguration-and-fluxgatemetricscompositeautoconfiguration).

The engine injects `RateLimitRuleSetProvider` by type: with hot reload that is the `@Primary`
`cachingRuleSetProvider`; without it, two provider beans with no `@Primary` fail the context.

### FluxgateReloadAutoConfiguration

**Condition:** `fluxgate.reload.enabled=true` (the default).

Creates the rule cache, the reload strategy, the caching provider and the bucket reset handlers:

| Bean | Type | Condition | Notes |
|------|------|-----------|-------|
| `ruleCache` | `RuleCache` (a `CaffeineRuleCache`) | no `RuleCache` bean; `reload.cache.enabled=true`, `strategy != NONE`, Caffeine on the classpath | Atomic single load per key, plus a negative cache (`cache.negative-ttl`) |
| `ruleReloadStrategy` | `RuleReloadStrategy` | no `RuleReloadStrategy` bean | One of the strategies below |
| `cachingRuleSetProvider` | `CachingRuleSetProvider`, `@Primary` | a `RuleCache`, a `RuleReloadStrategy` and a `RateLimitRuleSetProvider` exist | Wraps the resolved provider and registers the reload listeners |
| `bucketResetHandler` | `BucketResetHandler` (a `RedisBucketResetHandler`) | a `RedisTokenBucketStore` bean exists and `mode` is not `IN_MEMORY` | Resolves the store per reset; skips with a WARN when Redis is unavailable |
| `inMemoryBucketResetHandler` | `BucketResetHandler` (an `InMemoryBucketResetHandler`) | a `Bucket4jRateLimiter` bean exists | `Bucket4jRateLimiter.reset` / `resetAll`; coexists with the Redis handler |
| `reloadStrategyLifecycle` | `SmartLifecycle` | a `RuleReloadStrategy` exists | Starts and stops the strategy |

`ruleReloadStrategy` is:

| Strategy | Result |
|----------|--------|
| `POLLING` | `PollingReloadStrategy` (content-hash comparison per rule set) |
| `PUBSUB`, `AUTO` with Pub/Sub | `CompositeReloadStrategy(RedisPubSubReloadStrategy, PollingReloadStrategy)`; just the `RedisPubSubReloadStrategy` when `pubsub.backstop-polling-interval` is `0` or there is no `RuleCache` |
| `AUTO` without Pub/Sub | `PollingReloadStrategy` (see [Hot Reload](#hot-reload) for when `AUTO` uses Pub/Sub) |
| `NONE`, or no provider at all | `NoOpReloadStrategy` |

A polling strategy needs the `RuleCache`; without it the strategy is `NoOpReloadStrategy`, with a
WARN.

The provider that gets cached is the bean named `delegateRuleSetProvider` if there is one, otherwise
the only `RateLimitRuleSetProvider`, otherwise the single `@Primary` one; several providers with no way
to choose fail startup — so a single provider bean works under any name.

**Listener order is a contract.** `CachingRuleSetProvider` is registered at
`AbstractReloadStrategy.ORDER_CACHE_INVALIDATION` (`-100`) and the bucket reset handler at
`ORDER_BUCKET_RESET` (`100`). Groups run in ascending order, and a failed group skips every higher
order with an ERROR log. Resetting buckets before invalidating the cache would make the next request
refill them from the stale rules.

### FluxgateFilterAutoConfiguration

Not listed in `AutoConfiguration.imports`: `@EnableFluxgateFilter` imports it.

**Conditions:**
- `@EnableFluxgateFilter` present
- `fluxgate.ratelimit.enabled` is not `false` (`filter-enabled` is deprecated and inert)
- Servlet web application with `jakarta.servlet.Filter` on the classpath

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `fluxgateRateLimitFilter` | `FluxgateRateLimitFilter` | no `FluxgateRateLimitFilter` bean | HTTP filter |
| `fluxgateRateLimitFilterRegistration` | `FilterRegistrationBean<FluxgateRateLimitFilter>` | no bean of that name | Servlet registration on `/*` at `filter-order` (default `1`) |

The filter resolves its collaborators through `ObjectProvider`, so bean ordering imposes no
constraints: `FluxgateRateLimitHandler`, `RateLimitResponseWriter`, `RequestContextCustomizer`,
`RateLimitDurationRecorder` and `FluxgateWaitPermits` are all optional. The `handler` attribute of
`@EnableFluxgateFilter` picks a handler bean by class; when there is none it falls back, with a WARN,
to whatever `FluxgateRateLimitHandler` bean exists. Note that **two** `RateLimitDurationRecorder` beans
silently disable the duration timer, because it is resolved with `getIfUnique()`.

When there is no `FluxgateRateLimitHandler` at all, the filter logs an actionable WARN naming the
missing bean and applies `failure-behavior`: under `DENY` (the default) every matched request is
answered with 503, under `ALLOW` it falls back to `FluxgateRateLimitHandler.ALLOW_ALL`. When Redis is
enabled or `fluxgate.ratelimit.mode` is set, a limiter without a rule set provider gets the stopgap
`MissingRuleSetProviderRateLimitHandler` described above (startup **ERROR**) instead. With
`fluxgate.ratelimit.fail-on-missing-handler=true` both cases fail the boot — see
[No RuleSetProvider](#no-rulesetprovider).

### FluxgateResilienceAutoConfiguration

**Condition:** Always loaded (enabled/disabled via properties)

Creates (every bean is `@ConditionalOnMissingBean` on its type):
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRetryConfig` | `RetryConfig` | Retry configuration |
| `fluxgateCircuitBreakerConfig` | `CircuitBreakerConfig` | Circuit breaker configuration |
| `fluxgateRetryExecutor` | `RetryExecutor` (a `DefaultRetryExecutor`) | `resilience.retry.enabled` not `false` |
| `fluxgateNoOpRetryExecutor` | `RetryExecutor` (a `NoOpRetryExecutor`) | `resilience.retry.enabled=false` |
| `fluxgateCircuitBreaker` | `CircuitBreaker` (a `DefaultCircuitBreaker`) | `resilience.circuit-breaker.enabled` not `false` |
| `fluxgateNoOpCircuitBreaker` | `CircuitBreaker` (a `NoOpCircuitBreaker`) | `resilience.circuit-breaker.enabled=false` |
| `fluxgateResilientExecutor` | `ResilientExecutor` | Combined retry + circuit breaker |

### FluxgateAopExceptionHandlerAutoConfiguration

**Condition:** Servlet web application with a `RateLimitAspect` bean (registered by
`@EnableFluxgateAspect`). Being an auto-configuration, it is evaluated after every user configuration,
so a `RateLimitExceededExceptionHandler` declared anywhere in the application replaces the default.

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `rateLimitExceededExceptionHandler` | `RateLimitExceededExceptionHandler` | Maps `RateLimitExceededException` to 429 / 503 (see [The `@RateLimit` Aspect](#the-ratelimit-aspect)) |

### FluxgateAopAutoConfiguration

Not listed in `AutoConfiguration.imports`: `@EnableFluxgateAspect` imports it (with
`@EnableAspectJAutoProxy`).

**Conditions:** AspectJ (`org.aspectj.lang.annotation.Aspect`) on the classpath and
`fluxgate.ratelimit.enabled` not `false`.

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `rateLimitAspect` | `RateLimitAspect` | no `RateLimitAspect` bean | Enforces `@RateLimit`; resolves its handler like the filter does |

### FluxgateMetricsAutoConfiguration and FluxgateMetricsCompositeAutoConfiguration

**Conditions:** Micrometer on the classpath, a `MeterRegistry` bean, and `fluxgate.metrics.enabled`
not `false`. The composite configuration needs only some `RateLimitMetricsRecorder` bean.

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `micrometerMetricsRecorder` | `MicrometerMetricsRecorder` | — | Request counter, duration timer and remaining-tokens gauge |
| `fluxgateMetrics` | `FluxgateMetrics` | no `FluxgateMetrics` bean | Behind `fluxgate.limiter.failures` |
| `fluxgateLimiterFailureRecorder` | `ResilientRateLimiter.FailureRecorder` | no `FailureRecorder` bean | Feeds `fluxgate.limiter.failures` from `ResilientRateLimiter` |
| `bucketEvictionCounter` | `FunctionCounter` | a `Bucket4jRateLimiter` bean exists | `fluxgate.limiter.bucket_evictions` |
| `fluxgateEndpointTagLimitMeterFilter` | `MeterFilter` | `max-endpoint-tags > 0` and no bean of that name | Caps distinct `endpoint` tag values |
| `compositeMetricsRecorder` | `RateLimitMetricsRecorder`, `@Primary` | composite configuration | A `CompositeMetricsRecorder` over every recorder (MongoDB events and Micrometer); the recorder itself when there is only one |

### FluxgateActuatorAutoConfiguration

**Conditions:** Spring Boot Actuator (`HealthIndicator`) on the classpath and
`fluxgate.actuator.health.enabled` not `false`.

Creates:
| Bean | Type | Condition | Description |
|------|------|-----------|-------------|
| `fluxgateHealthIndicator` | `FluxgateHealthIndicator` | no bean of that name | The `fluxgate` health component (see [Health Checks](#health-checks)) |

The `DEGRADED` status mapping is contributed by `FluxgateHealthStatusEnvironmentPostProcessor`,
registered in `META-INF/spring.factories` (see [Health Status Mapping](#health-status-mapping)).

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
| `REDIS` | The distributed Redis limiter; without Redis enabled it logs a WARN and uses the in-memory limiter |
| `IN_MEMORY` | Always the in-memory limiter, even when Redis is enabled |

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

You still need rules. Declare them under `fluxgate.ratelimit.rule-sets`, supply them from code with a
`RateLimitRuleSetProvider` bean, or use `fluxgate-testkit` in tests.

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

`ResilientRateLimiter` then degrades to an in-memory limiter (`fluxgateFallbackRateLimiter`) when the
primary limiter fails or its circuit is open; when the primary limiter is itself in-memory the setting
is redundant and ignored. Limits stay in force **per instance**, which is a far better approximation of the
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
                                       DENY  → 503,     tag action=fail_closed
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
`tryConsume`, and converts the result with `RateLimitResponse.from(result)`. Problems that are not
limiter failures are answered as follows:

| Problem | Handler | HTTP |
|---------|---------|------|
| Missing scope value under `missing-key-behavior=REJECT` (`MissingRateLimitKeyException`) | returns a rejected response with no wait time | **429** |
| Weighted cost above the capacity of a matching band | throws `PermitsExceedCapacityException` | **429** (filter), **503** (aspect: `@RateLimit(permits)` is fixed in code) |
| Rule set that cannot be built (`InvalidRuleConfigException`) | throws `RateLimiterUnavailableException` | **503** |
| Unknown rule set under `missing-rule-behavior=DENY` | throws `RateLimiterUnavailableException` | **503** |

A rule set that cannot be built is a configuration problem, not a limiter failure, so
`failure-behavior` does not apply to it: it is **503 even with `failure-behavior=ALLOW`** (before 0.4
the filter and the aspect let such requests through under `ALLOW`). Configuration problems are logged at
WARN once per rule set id and at DEBUG afterwards.

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

A cost that is still larger than the capacity of a matching band can never be served, so it is
answered with **429** and a problem document naming the cost and the capacity
(`"Request cost of 500 permits exceeds the rate limit capacity of 100 and can never be served"`),
without `Retry-After`. It is the client's error, not a limiter failure: it never counts against the
circuit breaker, never reaches the in-memory fallback and never triggers `failure-behavior`, so
oversized costs cannot open the breaker for everyone. Keep `max-cost` at or below the smallest band
capacity of the rules the header applies to if you would rather clamp than reject. A custom
`RateLimitResponseWriter` renders this case through `writeCostExceeded`.

> **Only expose `cost-header` to authenticated, trusted callers.** The client is deciding how much of
> its own quota to spend, which is harmless — but on a **shared** bucket scope (`GLOBAL`, or `PER_IP`
> behind NAT) one caller can exhaust everyone else's quota with a single expensive request. Where
> possible, have the server decide the cost per endpoint in a `RequestContextCustomizer` or a custom
> handler instead of trusting a header.

`@RateLimit(permits = 5)` does the same for the aspect. There the cost is fixed in code, so a
`permits` value above a band's capacity is a configuration error and answered with 503.

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
| `waitForRefill` | `false` | Explicit override of the rule's policy. `true` waits unless `fluxgate.ratelimit.wait-for-refill.enabled` is explicitly `false` |
| `maxWaitTimeMs` | `5000` | Cap on the wait |
| `maxConcurrentWaits` | `100` | **Deprecated and ignored** — the wait permits are one `FluxgateWaitPermits` bean shared by the filter and the aspect; use `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` |

Three behaviours worth knowing:

- It shares `RequestContextFactory` with the filter, so `PER_USER`, `PER_API_KEY` and `CUSTOM` scopes
  behave identically in both modes. Before that it built a context containing only the client IP,
  which silently demoted every identity scope to per-IP limiting.
- It works **without an HTTP request**. The context then carries only `endpoint` (typically
  `Type.method`) and `method` (`INTERNAL`), so identity scopes have nothing to resolve and
  `missing-key-behavior` decides. A `GLOBAL` rule is usually what you want for internal invocations.
- On rejection it writes the 429 only when the method is a Spring MVC handler (`@RequestMapping` or a
  shortcut, also on an implemented interface) with a non-primitive return type. In every other case — a
  service called from a controller, a scheduled task, a listener, a primitive return type, or
  `throwOnReject = true` — it throws `org.fluxgate.spring.aop.RateLimitExceededException`; it never
  returns `null` to the caller. When rate limiting is unavailable rather than exceeded,
  `isServiceUnavailable()` is `true` and an HTTP layer should answer 503.

In a servlet application FluxGate maps the exception for you: `RateLimitExceededExceptionHandler`, a
`@RestControllerAdvice` registered whenever the aspect is active, answers **429** with the rate limit
headers and `Retry-After`, or **503** (with `Retry-After` when the wait is known) when
`isServiceUnavailable()` is `true`, using the same `RateLimitResponseWriter` as the filter. Without it
Spring MVC answered the exception with 500.

It is ordered at `RateLimitExceededExceptionHandler.ORDER` (`Ordered.HIGHEST_PRECEDENCE + 1000`).
Spring MVC asks advice beans in order and takes the **first** one that has any matching handler, even
a general one, so the usual application catch-all - `@ExceptionHandler(Exception.class)` in an
unordered advice - would otherwise answer every `@RateLimit` rejection with its own 500. The advice
handles nothing but `RateLimitExceededException`, so its precedence changes no other error mapping.

To use your own error format:

- handle the exception in an advice ordered **before** FluxGate's, for example
  `@Order(RateLimitExceededExceptionHandler.ORDER - 1)` (an unordered advice runs after it and never
  sees the exception), or
- define a `RateLimitExceededExceptionHandler` bean (a subclass, for example); it replaces the default
  wherever it is declared.

An application advice that is ordered before `ORDER` *and* has a catch-all handler still turns
rejections into whatever that catch-all returns; give such an advice a lower precedence, or add a
`RateLimitExceededException` handler to it. The exception carries the `RateLimitResponse`:

```java
@RestControllerAdvice
@Order(RateLimitExceededExceptionHandler.ORDER - 1)
public class RateLimitExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handle(RateLimitExceededException e) {
    HttpStatus status =
        e.isServiceUnavailable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    long retryAfterMillis = e.getRetryAfterMillis();
    if (retryAfterMillis > 0) { // unknown (-1) when rate limiting is unavailable without a known wait
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf((retryAfterMillis + 999) / 1000));
    }
    return response.body(
        Map.of("error", status.getReasonPhrase(), "retryAfterMillis", retryAfterMillis));
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
      secret: ${FLUXGATE_RELOAD_SECRET}   # required for Pub/Sub, same value as fluxgate.control.secret
      max-message-age: 60s                # replay window
      accept-legacy-signed: true          # set false once every publisher signs version 2
      backstop-polling-interval: 60s      # 0 disables the backstop
```

| Strategy | Behaviour |
|----------|-----------|
| `AUTO` | Pub/Sub, with a polling backstop, when Redis is enabled **and** `pubsub.secret` is set (or `pubsub.allow-unsigned=true`). Otherwise, with Redis enabled, it logs a WARN and falls back to `POLLING` (`polling.interval`, default `30s`). Polling when Redis is not enabled |
| `PUBSUB` | Redis Pub/Sub plus the polling backstop. Requires `pubsub.secret` (or `pubsub.allow-unsigned=true`): without either, startup fails with an `IllegalStateException`. Falls back to `POLLING` with a WARN when Redis is not enabled |
| `POLLING` | Content-hash polling only |
| `NONE` | No reload |

### Signed notifications

The reload channel is a control-plane channel: a message on it makes every data plane instance reload
rules and reset buckets. Anyone who can `PUBLISH` to the Redis channel could do that, so since 0.4
Pub/Sub only runs **signed**:

- Set `fluxgate.reload.pubsub.secret` on the data plane and `fluxgate.control.secret` on the control
  plane to the **same** value. Messages carry an HMAC-SHA256 signature over a canonical form that binds
  the channel and a per-message `nonce` (schema version 2).
- The data plane ignores, with a WARN, every message that is unsigned, has a wrong signature, is older
  than `max-message-age` (default `60s`, so publisher and subscribers need reasonably synchronised
  clocks), reuses a nonce it already saw inside that window (replay), was signed for another channel,
  or is a version 2 message without a nonce. The plain-text `"*"` and `"ruleSetId"` forms are unsigned
  and therefore ignored too.
- The secret is trimmed and a blank value means "no secret". A secret shorter than 32 bytes is reported
  at WARN.
- `fluxgate.reload.pubsub.allow-unsigned=true` and `fluxgate.control.allow-unsigned=true` are the
  development-only way to run without a secret. They log a WARN and are ignored once a secret is set.
- Upgrade data planes before control planes: a data plane that only knows schema version 1 drops
  version 2 messages until its polling backstop catches up.

FluxGate authenticates the **message**, not the Redis connection: restrict who can reach Redis with
Redis ACLs and TLS as well. See [SECURITY.md](../SECURITY.md).

### Behaviour worth knowing

- **A Pub/Sub message that cannot be parsed is WARNed and ignored.** It used to be treated as a full
  reload, so one malformed message wiped every bucket. A deliberate full reload is
  `{"version": 2, "fullReload": true, ...}` from the control plane; a targeted one names a
  `ruleSetId`.
- **Messages are handled on a single `fluxgate-pubsub-listener` thread**, in arrival order, off the
  Lettuce event loop. At most 10000 messages wait; further ones are dropped with a WARN and the backstop
  polling picks the change up.
- **Pub/Sub runs with a polling backstop by default** (`pubsub.backstop-polling-interval`, `60s`), so a
  dropped message self-heals instead of leaving the data plane on stale rules indefinitely. Setting
  the interval to `0` (or disabling the rule cache) leaves Pub/Sub without one.
- **`strategy: NONE` no longer breaks the context.** It used to make the rule cache bean `null`,
  failing startup with `UnsatisfiedDependencyException`.

The rule cache collapses concurrent misses for one id into a single load and caches negative results
for `negative-ttl`, so a rule set that does not exist is not re-queried on every request.

Publishing a change from your control plane is `fluxgate-control-support`'s job:
`@NotifyRuleChange` publishes **after commit** inside a transaction (and not at all on rollback),
makes up to three attempts with jittered exponential backoff, and exposes
`RuleChangeNotifierMetrics.getFailedNotifications()` — a rising count means the data plane may be on
stale rules.

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
- A `FluxgateException` decides for itself (`isRetryable()`): `FluxgateConnectionException`s are
  retryable, with two exceptions — a `RedisConnectionException` raised while a command was running
  (it may already have consumed tokens), and `RedisUnavailableException`, which the lazy Redis
  limiter throws before it has a connection (a background reconnect with its own backoff restores
  it, so retrying on the request thread only adds latency). Configuration errors are never
  retryable; operation errors only when constructed as retryable
- `FluxgateTimeoutException` (or a `TimeoutException` anywhere in the cause chain) — **only** when
  `retry-on-timeout: true`
- Any other exception only when it is in the retry allow-list (`FluxgateConnectionException`,
  `FluxgateTimeoutException` by default)

`retry-on-timeout` defaults to `false`: retrying a timed-out operation is only safe when it is
idempotent, and a rate limit consume is not — a retried consume can charge twice.

`jitter-factor` (default `0.2`) applies ±20% randomness to each capped backoff, clamped to
`[0, max-backoff]`, so a fleet recovering from an outage does not retry in lockstep.

Retry decision order: disabled → a call the circuit breaker ignores (never retried) → timeout gate →
`FluxgateException.isRetryable()` (final, in both directions) → the class allow-list.

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
`executeWithFallback(action, fallback)` returns the caller's fallback. Nothing returns
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
│   ├── MissingConfigurationException
│   └── MissingRateLimitKeyException
├── FluxgateConnectionException         # Connection failures (retryable by default)
│   ├── RedisConnectionException        # Retryable only for connect-phase failures
│   ├── RedisUnavailableException       # Not retryable: no connection yet, reconnect runs in the background
│   └── MongoConnectionException
├── FluxgateOperationException          # Runtime errors (not retryable unless constructed so)
│   ├── RateLimitExecutionException
│   └── ScriptExecutionException
└── FluxgateTimeoutException            # Timeout errors (retried only with retry-on-timeout: true)
```

---

## HTTP Filter Behavior

### Request Flow

```
Request → Filter → Check include/exclude → FluxgateRateLimitHandler → Response
                         ↓                              ↓
                    Skip if not matched     429 if exceeded, 503 if unavailable
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
| `X-RateLimit-Reset` | legacy | Epoch **seconds** at which that band resets: TOKEN_BUCKET full again, SLIDING_WINDOW everything counted has left the window, FIXED_WINDOW window end |
| `RateLimit-Limit` | IETF | Capacity of the band that produced the decision |
| `RateLimit-Remaining` | IETF | Tokens left in that band |
| `RateLimit-Reset` | IETF | **Delta seconds** until that band resets (same per-algorithm meaning) |
| `RateLimit-Policy` | IETF | Quota policy such as `100;w=60`; omitted when the limit is `0` or the window is unknown or sub-second |
| `Retry-After` | always | Rejections only, whatever the family switches say. Whole seconds, rounded up, never `0` |

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
@FunctionalInterface
public interface RateLimitResponseWriter {
  // 429: the limit was exceeded
  void write(HttpServletRequest request, HttpServletResponse response, RateLimitResponse result)
      throws IOException;

  // 503: rate limiting is unavailable or not configured (default: minimal problem document)
  default void writeUnavailable(
      HttpServletRequest request, HttpServletResponse response, long retryAfterMillis)
      throws IOException { ... }

  // 429: the request cost exceeds a band's capacity (default: minimal problem document)
  default void writeCostExceeded(
      HttpServletRequest request, HttpServletResponse response, long permits, long capacity)
      throws IOException { ... }
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
Everything else in the template is copied verbatim. The template also renders the cost-exceeded 429
and, unless `unavailable-body-template` is set, the 503; values that do not apply are `-1`.

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

The writer is responsible for the status and the body; the headers are already written by then. A
lambda only replaces `write`: the 503 and the cost-exceeded 429 keep the interface defaults unless you
override `writeUnavailable` and `writeCostExceeded` too.

### Client IP Extraction

The filter extracts the client IP in this order:

1. `trust-client-ip-header=false` (the default): `request.getRemoteAddr()`. The forwarding header is
   ignored entirely.
2. `trust-client-ip-header=true` **with** `trusted-proxies`: the header is honoured only when
   `request.getRemoteAddr()` is itself one of the trusted proxies; otherwise it is ignored and the
   remote address is used. When it is honoured, the configured forwarding header is walked **from
   the right**, skipping hops that are themselves trusted proxies, and the first remaining candidate
   becomes the client IP (the remote address if every hop is a trusted proxy).
3. `trust-client-ip-header=true` with an **empty** `trusted-proxies`: the **right-most** hop is
   used — it was appended by the immediate proxy and is harder to forge than the left-most
   client-supplied value. Nothing can be verified end-to-end, so configure `trusted-proxies` for any
   multi-hop deployment. One WARN is logged at startup in this configuration; treat it as a to-do,
   not as noise.

The hop that would be chosen must parse as an IPv4 or IPv6 literal of at most 45 characters
(`ip:port`, `[v6]` and `[v6]:port` are reduced to the address). If it does not, extraction
**fails closed to `request.getRemoteAddr()`** rather than moving on to the next hop, because every
hop to its left is client-controlled. Hostnames are never resolved, so a header value cannot trigger
a DNS lookup.

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
            50                                     // maxConcurrentWaits (the property default)
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

The 4- and 7-argument constructors apply the secure defaults: forwarding headers are not trusted
(`trust-client-ip-header=false`, so the client IP is the socket address) and a limiter failure is
rejected with 503 (`failure-behavior=DENY`). They do not reject a request whose `ruleSetId` is blank
(`missing-rule-behavior` is not applied), so always pass one. Use the 11-argument constructor to
configure the client IP header and fail-open explicitly, the 12-argument one to set the blank rule set
behaviour as well, or one of the 17-argument constructors for everything the auto-configuration sets. Each filter
built this way gets its own wait semaphore; the `FluxgateWaitPermits` bean is shared only by the
auto-configured filter and the aspect.

### Filter Order in Spring Filter Chain

The filter is registered at `fluxgate.ratelimit.filter-order`, falling back to
`@EnableFluxgateFilter#filterOrder()`, whose default is **`1`**. Against Spring Boot's own servlet
filters:

| Order | Filter |
|-------|--------|
| `Integer.MIN_VALUE` | `CharacterEncodingFilter` |
| `-9900` | `FormContentFilter` |
| `-105` | `RequestContextFilter` |
| `-100` | Spring Security's `springSecurityFilterChain` (`spring.security.filter.order`) |
| **`1`** | **`FluxgateRateLimitFilter` (default)** |
| `Ordered.LOWEST_PRECEDENCE` | Filters without an order |

**Why after Spring Security by default?** The default identity source is `PRINCIPAL`, so `PER_USER`
and `PER_API_KEY` rules need the authenticated principal, which only exists once the security chain
has run. To shed load before authentication instead — at the cost of identity scopes, which then
follow `missing-key-behavior` — set `filter-order` below `-100`, for example
`Integer.MIN_VALUE + 100`, and limit by `PER_IP` or `GLOBAL`.

---

## WAIT_FOR_REFILL Policy

Instead of immediately rejecting requests when rate limit is exceeded, you can configure the filter to wait for token refill:

```yaml
fluxgate:
  ratelimit:
    enabled: true
    wait-for-refill:
      enabled: true          # Required for a rule's WAIT_FOR_REFILL to take effect (see below)
      max-wait-time-ms: 5000 # Maximum wait time (5 seconds)
      max-concurrent-waits: 50   # Limit concurrent waiting requests (default 50)
```

`wait-for-refill.enabled` has three states:

| Value | Rule `WAIT_FOR_REFILL` policy (filter and aspect) | `@RateLimit(waitForRefill = true)` |
|-------|---------------------------------------------------|------------------------------------|
| unset (the default) | Ignored — the request is rejected at once | Waits |
| `true` | Waits | Waits |
| `false` | Ignored | Ignored — a global kill switch, nothing waits |

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

Override the default scope-based key resolver (`LimitScopeKeyResolver`; any `KeyResolver` bean
replaces it):

```java
@Configuration
public class FluxgateConfig {

    @Bean
    public KeyResolver fluxgateKeyResolver() {
        return (context, rule) -> {
            // Rate limit by API key instead of IP. RateLimitKey.of(prefix, rawValue) keeps the
            // prefix and sanitises only the value - the same keys the built-in resolver produces.
            String apiKey = context.getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                return RateLimitKey.of("key:", apiKey);
            }
            // Fallback to IP; a resolver must never pass null (RateLimitKey rejects it)
            String ip = context.getClientIp();
            return RateLimitKey.of("ip:", ip == null || ip.isBlank() ? "unknown" : ip);
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

            KeyResolver keyResolver = (ctx, matchedRule) -> {
                String ip = ctx.getClientIp();
                return RateLimitKey.of("ip:", ip == null || ip.isBlank() ? "unknown" : ip);
            };

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
    enabled: false  # No filter or aspect in dev; the Mongo/Redis beans above are still created

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

FluxGate puts correlation IDs and request attributes into the logging MDC, ready for structured JSON
logs in ELK Stack, Splunk or other log aggregation systems.

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
| `userId` | Resolved user id (the principal by default; the `identity.user-id-header` value only when `identity.source` reads headers), sanitised and length-capped |
| `apiKey` | Resolved API key (the `identity.api-key-header` value only when `identity.source` reads headers), **masked** |
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
```

**Available Metrics:**

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `fluxgate_requests_total` | Counter | `result`, `rule_set`, `endpoint`, `method` | Rate limit decisions. The meter is `fluxgate.requests`; Prometheus appends `_total` |
| `fluxgate_requests_duration_seconds` | Timer | `rule_set`, `endpoint`, `method` | Time spent deciding |
| `fluxgate_limiter_failures_total` | Counter | `rule_set`, `endpoint`, `action`, `exception` | Limiter dependency failures |
| `fluxgate_tokens_remaining` | Gauge | `rule_set`, `endpoint` | Remaining tokens in the most restrictive bucket |
| `fluxgate_limiter_bucket_evictions_total` | FunctionCounter | — | In-memory buckets evicted from the cache (each eviction resets that key's quota); only with a `Bucket4jRateLimiter` |

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

# HELP fluxgate_tokens_remaining Remaining tokens in the most restrictive FluxGate bucket
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/users/{id}",rule_set="api-limits"} 8.0
```

`endpoint` values are normalized — numeric, UUID and 24-hex path segments become `{id}` — and the
number of distinct values is capped by `fluxgate.metrics.max-endpoint-tags` (default 1000). The
recorder folds every endpoint beyond that budget into the single tag value `other`, and the
`fluxgateEndpointTagLimitMeterFilter` (an `EndpointTagLimitMeterFilter`) denies over-budget values
from any other recorder as defence in depth, never `other` or `unknown`. A value of `0` or below
removes the cap. Without it, a path-parameter heavy API grew the meter registry and the heap without
bound. Turn normalization off with
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

fluxgate:
  actuator:
    health:
      include-endpoint-details: true   # mongo.* / redis.* messages and details (default false)
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
        "aspectEnabled": false,
        "failureBehavior": "DENY",
        "missingRuleBehavior": "DENY",
        "trustClientIpHeader": false,
        "defaultRuleSetIdConfigured": true,
        "mongo.status": "UP",
        "mongo.message": "MongoDB is healthy",
        "mongo.database": "fluxgate",
        "mongo.latency_ms": 2,
        "mongo.version": "7.0.0",
        "mongo.connections.current": 10,
        "mongo.connections.available": 100,
        "rateLimiter.ready": true,
        "redis.status": "UP",
        "redis.message": "Redis cluster is healthy",
        "redis.mode": "CLUSTER",
        "redis.latency_ms": 1,
        "redis.cluster_state": "ok",
        "redis.cluster_nodes": 6,
        "redis.cluster_masters": 3,
        "redis.cluster_replicas": 3,
        "dependencyIssues": false
      }
    }
  }
}
```

**Health Check Details:**

Without `include-endpoint-details` only `mongo.status` and `redis.status` are reported for the
dependencies, because the messages and details carry hosts and failure causes.

`rateLimiter.ready` is present whenever the Redis limiter is in use (a `RedisConnectionState` bean
exists) and says whether it has connected and loaded its Lua scripts — not the same as Redis
answering `PING`. While it is `false`, `rateLimiter.failedAttempts` is added, plus
`rateLimiter.lastError` with `include-endpoint-details`; a failing probe reports
`rateLimiter.error` instead. A `DEGRADED` response also carries a `statusReason`.

| Component | Details Provided (with `include-endpoint-details`) |
|-----------|------------------|
| **MongoDB** | database, version, latency_ms, connections (current/available/totalCreated), replicaSet name/role |
| **Redis** | mode (STANDALONE/CLUSTER), latency_ms, cluster_state, cluster_slots_*, cluster_nodes, masters/replicas |

**Health Status Values:**

The `fluxgate` component itself is `UP`, or `DEGRADED` (with `dependencyIssues: true`) when an enabled
dependency or the Redis limiter is not healthy. The `mongo.status` and `redis.status` details take
these values:

| Status | Description |
|--------|-------------|
| `UP` | Component is healthy |
| `DOWN` | Component has failed |
| `UNKNOWN` | Enabled, but no health checker bean exists |
| `ERROR` | The health check itself threw (the exception class is in `mongo.error` / `redis.error`) |
| `DISABLED` | Component is not enabled |

---

## Troubleshooting

### No RateLimiter Bean

`FluxgateRateLimiterAutoConfiguration` always registers a limiter — the Redis one when Redis is
enabled, otherwise the in-memory Bucket4j one, backing off only for a `RateLimiter` bean of your own
(`@ConditionalOnMissingBean`). So this case only arises when that auto-configuration is excluded
(`spring.autoconfigure.exclude`, `@SpringBootApplication(exclude = ...)`) or something else
suppresses its `RateLimiter`.

**Warning** (filter; the aspect logs the same with "invocation" wording):
`No FluxgateRateLimitHandler available (no RateLimiter bean exists). ...`, or
`(neither a RateLimiter nor a RateLimitRuleSetProvider bean exists)` when no rule source exists
either. The WARN then ends with what happens: under the default `failure-behavior=DENY`
"Every request is rejected with 503 because fluxgate.ratelimit.failure-behavior=DENY."; under
`ALLOW` "All requests pass through unlimited." and the filter runs with
`FluxgateRateLimitHandler.ALLOW_ALL`.

**Solutions:**
1. Stop excluding `FluxgateRateLimiterAutoConfiguration` (or whatever suppresses its limiter)
2. Define your own `RateLimiter` bean
3. Define your own `FluxgateRateLimitHandler` bean

### No RuleSetProvider

Without a `RateLimitRuleSetProvider` there are no rules, so nothing is enforced: requests are
rejected with 503 under `failure-behavior=DENY` and allowed under `ALLOW`. How it is reported depends
on the configuration:

- **Redis enabled or `fluxgate.ratelimit.mode` set:** the stopgap
  `MissingRuleSetProviderRateLimitHandler` logs one startup **ERROR**:
  `A RateLimiter bean exists but no RateLimitRuleSetProvider: enable fluxgate.mongo, define a
  RateLimitRuleSetProvider bean, or supply a FluxgateRateLimitHandler. Requests are rejected per
  failure-behavior. ...` (`allowed` under `ALLOW`).
- **Otherwise** (for example the default configuration with no rule source): no handler exists and
  the filter logs one **WARN**:
  `No FluxgateRateLimitHandler available (a RateLimiter bean exists but no RateLimitRuleSetProvider).
  ...` followed by the same DENY/ALLOW ending as above.

**Solutions:**
1. Enable Mongo: `fluxgate.mongo.enabled=true`
2. Declare rule sets under `fluxgate.ratelimit.rule-sets`
3. Define a `RateLimitRuleSetProvider` bean (a lambda returning an `Optional<RateLimitRuleSet>` is
   enough)

**Refusing to start instead:** set `fluxgate.ratelimit.fail-on-missing-handler=true` (default
`false`). Startup then fails with a `MissingConfigurationException` in every case above: when the
stopgap handler would be created, and when the filter (`@EnableFluxgateFilter`) or the aspect
(`@EnableFluxgateAspect`) finds no `FluxgateRateLimitHandler` at all. It has no effect when neither
the filter nor the aspect is enabled and Redis and `mode` are unset — nothing would enforce limits
then anyway.

### Filter Not Applied

**Checklist:**
1. `fluxgate.ratelimit.enabled` is not `false` (unset means `true`) — note that `filter-enabled` is
   deprecated and inert
2. `@EnableFluxgateFilter` is on a configuration class
3. A `FluxgateRateLimitHandler` exists — normally the default one, which needs a `RateLimiter` and a
   `RateLimitRuleSetProvider`; check the startup WARN/ERROR
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
| A quota longer than a day resets every day | Pre-0.4 behaviour: the hard 24h TTL cap. 0.4 caps TOKEN_BUCKET / SLIDING_WINDOW TTLs at `fluxgate.redis.max-bucket-ttl` (default `7d`) instead, and FIXED_WINDOW is exempt |
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
│                       Spring Boot Application                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ FluxgateFilterAutoConfiguration (@EnableFluxgateFilter)          │   │
│  │ @ConditionalOnProperty("fluxgate.ratelimit.enabled")             │   │
│  │ ObjectProvider<FluxgateRateLimitHandler>                         │   │
│  │                                                                  │   │
│  │ FluxgateRateLimitFilter                                          │   │
│  │   - Builds the RequestContext (client IP, identity)              │   │
│  │   - Calls FluxgateRateLimitHandler.tryConsume()                  │   │
│  │   - Returns 429 / 503 or passes through                          │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                │ uses                                   │
│                                ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ FluxgateRateLimiterAutoConfiguration (always loaded)             │   │
│  │                                                                  │   │
│  │ RateLimiter: the Redis limiter below when fluxgate.redis.enabled,│   │
│  │   otherwise Bucket4jRateLimiter (in-memory); wrapped by          │   │
│  │   ResilientRateLimiter for retry / circuit breaker / fallback    │   │
│  │ RateLimitEngine + EngineBackedRateLimitHandler                   │   │
│  │   (need a RateLimiter AND a RateLimitRuleSetProvider)            │   │
│  │ PropertiesRuleSetProvider (fluxgate.ratelimit.rule-sets)         │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                │ limiter (Redis) / rules (Mongo)        │
│                                ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ FluxgateRedisAutoConfiguration                                   │   │
│  │ @ConditionalOnProperty("fluxgate.redis.enabled")                 │   │
│  │                                                                  │   │
│  │ RedisRateLimiterConfig, RedisTokenBucketStore,                   │   │
│  │ LazyRedisRateLimiter (bean fluxgateDelegateRateLimiter):         │   │
│  │   connects in the background, then delegates to RedisRateLimiter │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │ FluxgateMongoAutoConfiguration                                   │   │
│  │ @ConditionalOnProperty("fluxgate.mongo.enabled")                 │   │
│  │                                                                  │   │
│  │ FluxgateMongoClientHolder, RateLimitRuleRepository,              │   │
│  │ delegateRuleSetProvider (MongoRuleSetProvider)                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Related Modules

- [`fluxgate-core`](../fluxgate-core) - Core interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter) - MongoDB integration
- [`fluxgate-redis-ratelimiter`](../fluxgate-redis-ratelimiter) - Redis rate limiter

## License

MIT License
