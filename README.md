# FluxGate

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x%20%7C%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/OpenFluxGate/fluxgate/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/OpenFluxGate/fluxgate/actions)
[![Benchmark](https://img.shields.io/badge/Benchmark-Results-blueviolet.svg)](https://openfluxgate.github.io/fluxgate/benchmark/)
[![Admin UI](https://img.shields.io/badge/Admin%20UI-FluxGate%20Studio-orange.svg)](https://github.com/OpenFluxGate/fluxgate-studio)

English | [한국어](README.ko.md)

> **🚀 Live Demo** - Try FluxGate without installation:
>
> | Demo | Description | Link |
> |------|-------------|------|
> | **FluxGate Studio** | Admin UI for rate limit rule management | [Open Demo](http://13.124.192.116:3000/) |
> | **FluxGate API** | Rate limiting API with Swagger UI | [Open Swagger](http://13.124.192.116:8080/swagger-ui/index.html) |

**FluxGate** is a distributed rate limiting framework for Java applications. Built on top of [Bucket4j](https://github.com/bucket4j/bucket4j), it provides Redis-backed distributed rate limiting, MongoDB rule management, and Spring Boot integration.

## Key Features

- **Distributed Rate Limiting** - Redis-backed token bucket algorithm with atomic Lua scripts
- **Multi-Band Support** - Multiple rate limit tiers (e.g., 100/sec + 1000/min + 10000/hour), evaluated all-or-nothing per rule
- **Dynamic Rule Management** - Store and update rules in MongoDB without restart
- **Spring Boot Auto-Configuration** - Working out of the box; the starter provides the rate limit handler
- **In-Memory Mode** - `fluxgate.ratelimit.mode=IN_MEMORY` gives a single-instance limiter with no infrastructure
- **LimitScope-based Key Resolution** - Rate limit by IP, User ID, API Key, or custom composite keys
- **Composite Key Support** - Combine multiple identifiers (e.g., IP + User ID) for fine-grained control
- **WAIT_FOR_REFILL Policy** - Wait for token refill instead of immediate rejection
- **RequestContext Customization** - Override client IP, add custom attributes before rate limiting
- **Multiple Filters Support** - Configure multiple filters with different priorities via Java Config
- **Resilience** - Retry, circuit breaker, and an optional in-memory fallback when Redis is unreachable
- **Standard Rate Limit Headers** - Legacy `X-RateLimit-*` and IETF `RateLimit-*` families, RFC 9457 problem responses
- **Trusted Proxy Handling** - Forwarded client IP headers are honoured only from configured proxies
- **Production-Safe Design** - Redis server time (no clock drift), fail-closed defaults, bounded caches
- **Pluggable Architecture** - Easy to extend with custom handlers, response writers, and rule providers
- **Structured Logging** - JSON logging with correlation IDs for ELK/Splunk integration
- **Prometheus Metrics** - Built-in Micrometer integration, with a packaged Grafana dashboard and alert rules

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         FluxGate Architecture                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐   │
│  │   Client     │───▶│ Spring Boot  │───▶│   FluxGate Filter        │   │
│  │  Application │    │  Application │    │  (Auto Rate Limiting)    │  │
│  └──────────────┘    └──────────────┘    └───────────┬──────────────┘  │
│                                                      │                  │
│                      ┌───────────────────────────────┼───────────────┐  │
│                      │                               ▼               │  │
│                      │  ┌─────────────────────────────────────────┐  │  │
│                      │  │        FluxgateRateLimitHandler         │  │  │
│                      │  │  ┌─────────────┐  ┌──────────────────┐  │  │  │
│                      │  │  │ Engine      │  │   Your own       │  │  │  │
│                      │  │  │ Backed      │  │   handler        │  │  │  │
│                      │  │  │ (default)   │  │   (HTTP, custom) │  │  │  │
│                      │  │  └──────┬──────┘  └────────┬─────────┘  │  │  │
│                      │  └─────────┼──────────────────┼────────────┘  │  │
│                      │            │                  │               │  │
│                      └────────────┼──────────────────┼───────────────┘  │
│                                   │                  │                  │
│                                   ▼                  ▼                  │
│  ┌────────────────────────────────────┐    ┌────────────────────────┐   │
│  │             Redis                  │    │  Rate Limit Service    │  │
│  │  ┌──────────────────────────────┐  │    │  (fluxgate-sample-     │  │
│  │  │   Token Bucket State         │  │    │   redis on port 8082)  │  │
│  │  │   (Lua Script - Atomic)      │  │◀───│                        │  │
│  │  └──────────────────────────────┘  │    └────────────────────────┘  │
│  └────────────────────────────────────┘                                 │
│                                                                         │
│  ┌────────────────────────────────────┐                                 │
│  │           MongoDB                  │                                 │
│  │  ┌──────────────────────────────┐  │                                 │
│  │  │   Rate Limit Rules           │  │                                 │
│  │  │   (Dynamic Configuration)    │  │                                 │
│  │  └──────────────────────────────┘  │                                 │
│  └────────────────────────────────────┘                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

The decision path is always the same: the filter (or the `@RateLimit` aspect) builds a
`RequestContext`, hands it to a `FluxgateRateLimitHandler`, and the library's default handler
delegates to `RateLimitEngine`, which resolves the rule set and calls a `RateLimiter`. See
[docs/en/architecture/README.md](docs/en/architecture/README.md) for the full walk-through.

## Modules

| Module | Description |
|--------|-------------|
| **fluxgate-core** | Core rate limiting engine, SPIs, and Bucket4j in-memory limiter |
| **fluxgate-redis-ratelimiter** | Redis-backed distributed rate limiter with Lua scripts |
| **fluxgate-mongo-adapter** | MongoDB adapter for dynamic rule management |
| **fluxgate-spring-boot3-starter** | Spring Boot 3.x auto-configuration (Java 17+, jakarta.servlet) |
| **fluxgate-spring-boot2-starter** | Spring Boot 2.7.x auto-configuration (Java 11+, `javax.servlet`). Equivalent feature set; **requires Spring Boot 2.7.x** ([README](fluxgate-spring-boot2-starter/README.md)) |
| **fluxgate-control-support** | Control-plane helpers: `@NotifyRuleChange` / `@NotifyFullReload` and the Redis rule-change notifier |
| **fluxgate-testkit** | Integration testing utilities and JMH benchmarks |
| **fluxgate-samples** | Sample applications demonstrating various use cases |

## Quick Start

### Prerequisites

- Java 11+ with Spring Boot 2.7.x, or Java 17+ with Spring Boot 3.x
- Maven 3.8+
- Redis 6.0+ (for distributed rate limiting; not needed in `IN_MEMORY` mode)
- MongoDB 4.4+ (optional, for rule management)

### 1. Add Dependencies

```xml
<!-- For Spring Boot 3.x (Java 17+) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.3.7</version>
</dependency>

<!-- For Spring Boot 2.7.x (Java 11+). 2.7 is required, not just supported: the starter
     uses @AutoConfiguration, a 2.7 API. Boot 2.7 is OSS end-of-life - plan the move to
     the boot3 starter. -->
<!--
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot2-starter</artifactId>
    <version>0.3.7</version>
</dependency>
-->

<!-- For Redis-backed rate limiting -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>0.3.7</version>
</dependency>

<!-- For MongoDB rule management (optional) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>0.3.7</version>
</dependency>
```

### 2. Configure Application

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
  ratelimit:
    default-rule-set-id: api-limits
    failure-behavior: DENY
    missing-rule-behavior: DENY
    trust-client-ip-header: false
    include-patterns:
      - /api/**
    exclude-patterns:
      - /health
      - /actuator/**
```

Every key above is read from properties. `include-patterns`, `exclude-patterns`,
`filter-order` and `default-rule-set-id` take precedence over the matching
`@EnableFluxgateFilter` attribute, so a deployment can retune the filter without
recompiling. Note that `/*` matches a **single** path segment; use `/**` for nested
paths.

FluxGate's Spring Boot auto-configuration is fail-closed by default. A version upgrade is enough for auto-configured applications to deny limiter failures and missing rules with HTTP 429, and forwarded client IP headers are ignored unless `fluxgate.ratelimit.trust-client-ip-header=true` is explicitly set behind a trusted proxy — in which case you should also list the proxies in `fluxgate.ratelimit.trusted-proxies`.

Redis bucket keys use the `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandLabel>` layout, and resolved key values carry a scope prefix (`ip:`, `user:`, `key:`, `custom:`, or `global`). In shared Redis deployments, isolate tenants with distinct rule set IDs and include tenant identity in custom key strategies; do not rely on a shared global rule set for multiple tenants.

### 3. Enable Rate Limiting Filter

```java
@SpringBootApplication
@EnableFluxgateFilter
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

No `handler` attribute and no custom handler class are needed. With a `RateLimiter`
(Redis or in-memory) and a `RateLimitRuleSetProvider` (MongoDB or your own bean) on the
context, the starter registers `EngineBackedRateLimitHandler` for you. Define your own
`FluxgateRateLimitHandler` bean only when you want to replace that — for example to call a
central rate limit service over HTTP.

### 4. Development without Redis

For local development and tests, run the in-memory limiter instead. Limits then apply
**per instance**, which is fine for one process and wrong for a cluster:

```yaml
fluxgate:
  redis:
    enabled: false
  ratelimit:
    mode: IN_MEMORY           # or AUTO, which falls back to in-memory when Redis is off
    default-rule-set-id: api-limits
```

Supply the rules from code instead of MongoDB:

```java
@Bean
public RateLimitRuleSetProvider ruleSetProvider() {
    RateLimitRule rule = RateLimitRule.builder("per-ip")
        .scope(LimitScope.PER_IP)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
        .ruleSetId("api-limits")
        .build();

    RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("api-limits")
        .rules(List.of(rule))
        .build();

    return ruleSetId -> "api-limits".equals(ruleSetId)
        ? Optional.of(ruleSet)
        : Optional.empty();
}
```

For tests, `fluxgate-testkit` packages exactly this wiring behind
`FluxgateInMemoryExtension` and `FluxgateTestRules` — see
[fluxgate-testkit/README.md](fluxgate-testkit/README.md).

### 5. Test Rate Limiting

```bash
# Send 12 requests (with 10 req/min limit)
for i in {1..12}; do
  curl -s -o /dev/null -w "Request $i: %{http_code}\n" http://localhost:8080/api/hello
done

# Expected output:
# Request 1-10: 200
# Request 11-12: 429 (Too Many Requests)
```

## Deployment Patterns

### Pattern 1: Direct Redis Access

Best for simple deployments where each application instance connects directly to Redis.

```
┌─────────────┐     ┌─────────────┐
│   App #1    │────▶│             │
├─────────────┤     │    Redis    │
│   App #2    │────▶│             │
├─────────────┤     │             │
│   App #N    │────▶│             │
└─────────────┘     └─────────────┘
```

### Pattern 2: HTTP API Mode (Centralized)

Best for microservices architecture where you want a dedicated rate limiting service.
FluxGate does not ship an HTTP handler; the client side is a
`FluxgateRateLimitHandler` you write, as demonstrated by
`fluxgate-samples/fluxgate-sample-filter`'s `HttpRateLimitHandler`.

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────┐
│   App #1    │────▶│                 │     │             │
├─────────────┤     │  Rate Limit     │────▶│    Redis    │
│   App #2    │────▶│  Service (8082) │     │             │
├─────────────┤     │                 │     │             │
│   App #N    │────▶│                 │     │             │
└─────────────┘     └─────────────────┘     └─────────────┘
```

```java
@SpringBootApplication
@EnableFluxgateFilter(handler = HttpRateLimitHandler.class)
public class ClientApplication { }
```

```yaml
# Client application configuration. fluxgate.api.url is read by the sample handler,
# not by the library.
fluxgate:
  api:
    url: http://rate-limit-service:8082
```

## Sample Applications

| Sample | Port | Description |
|--------|------|-------------|
| **fluxgate-sample-standalone-java21** | 8085 | Full stack with direct MongoDB + Redis integration, plus the `@RateLimit` aspect |
| **fluxgate-sample-standalone-java11** | 8085 | The same stack on Java 11 / Spring Boot 2.7 |
| **fluxgate-sample-redis** | 8082 | Rate limit service with Redis backend |
| **fluxgate-sample-mongo** | 8081 | Rule management with MongoDB |
| **fluxgate-sample-filter** | 8083 | Client app with auto rate limiting filter over HTTP |
| **fluxgate-sample-api** | 8080 | REST API for rate limit checking |

### Running Samples

```bash
# Start infrastructure (local development only; ports bind to 127.0.0.1)
docker compose -f docker/redis-standalone.yml -f docker/mongo.yml up -d

# Start rate limit service
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis

# Start client application (in another terminal)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter

# Test rate limiting
curl http://localhost:8083/api/hello
```

## Configuration Reference

All defaults below are the values in `FluxgateProperties` and
`FluxgateResilienceProperties`. The Spring Boot 3 starter's
[README](fluxgate-spring-boot3-starter/README.md) documents each key in detail.

### Redis and MongoDB

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.redis.enabled` | `false` | Enable the Redis rate limiter |
| `fluxgate.redis.uri` | `redis://localhost:6379` | Redis connection URI (comma-separated hosts for cluster) |
| `fluxgate.redis.mode` | `auto` | `standalone`, `cluster`, or `auto` (auto-detect) |
| `fluxgate.redis.timeout-ms` | `5000` | Command timeout |
| `fluxgate.redis.fail-fast` | `false` | Fail application startup when Redis is unreachable. Default connects lazily and reconnects in the background |
| `fluxgate.redis.max-bucket-ttl` | `7d` | Upper bound on a bucket's TTL. Bounds how much Redis memory a forged-identity caller can pin down with a long window |
| `fluxgate.mongo.enabled` | `false` | Enable the MongoDB adapter |
| `fluxgate.mongo.uri` | `mongodb://localhost:27017/fluxgate` | MongoDB connection URI |
| `fluxgate.mongo.database` | `fluxgate` | MongoDB database name |
| `fluxgate.mongo.rule-collection` | `rate_limit_rules` | Collection holding rate limit rules |
| `fluxgate.mongo.event-collection` | _(unset)_ | Collection for rate limit events (optional) |
| `fluxgate.mongo.ddl-auto` | `validate` | `validate` or `create` (see below) |
| `fluxgate.mongo.event-retention` | `30d` | TTL index on the event collection. `0` means you manage retention yourself and no index is created |

### Rate limiting

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.ratelimit.enabled` | `true` | Master switch. `false` registers neither the filter nor the aspect |
| `fluxgate.ratelimit.mode` | `AUTO` | `AUTO` (Redis when enabled, else in-memory), `REDIS`, `IN_MEMORY` |
| `fluxgate.ratelimit.default-rule-set-id` | _(unset)_ | Rule set applied when nothing else selects one. No default value |
| `fluxgate.ratelimit.failure-behavior` | `DENY` | `DENY` or `ALLOW` when the limiter itself fails |
| `fluxgate.ratelimit.missing-rule-behavior` | `DENY` | `DENY` or `ALLOW` when no rule set is found |
| `fluxgate.ratelimit.missing-key-behavior` | `FALLBACK_TO_IP` | `FALLBACK_TO_IP` or `REJECT` when a scope's value is absent |
| `fluxgate.ratelimit.identity.source` | _(derived)_ | `HEADERS`, `PRINCIPAL`, or `PRINCIPAL_THEN_HEADERS`. Unset resolves to `PRINCIPAL_THEN_HEADERS` when Spring Security is on the classpath and `HEADERS` otherwise. The effective value is logged once at startup |
| `fluxgate.ratelimit.identity.user-id-header` | `X-User-Id` | Header carrying the user id when identity comes from headers |
| `fluxgate.ratelimit.identity.api-key-header` | `X-API-Key` | Header carrying the API key |
| `fluxgate.ratelimit.include-patterns` | _(unset, effective `/**`)_ | Paths to rate limit. Falls back to `@EnableFluxgateFilter#includePatterns()`, then all paths |
| `fluxgate.ratelimit.exclude-patterns` | _(unset)_ | Paths to skip. Falls back to `@EnableFluxgateFilter#excludePatterns()` |
| `fluxgate.ratelimit.case-sensitive-patterns` | `true` | Whether include/exclude matching is case sensitive |
| `fluxgate.ratelimit.filter-order` | _(unset, effective `1`)_ | Filter order. Falls back to `@EnableFluxgateFilter#filterOrder()`, which is `1` |
| `fluxgate.ratelimit.client-ip-header` | `X-Forwarded-For` | Forwarding header inspected when trusted |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | Whether the forwarding header may override `remoteAddr` |
| `fluxgate.ratelimit.trusted-proxies` | `[]` | IPs or CIDR blocks allowed to set the forwarding header. Configure this whenever `trust-client-ip-header=true`: an empty list falls back to the right-most valid hop (harder to forge than the left-most client-supplied value) and logs one startup WARN |
| `fluxgate.ratelimit.collect-headers` | `false` | Copy allow-listed request headers into `RequestContext` |
| `fluxgate.ratelimit.header-allowlist` | `[]` | Header names that may be copied (case-insensitive) |
| `fluxgate.ratelimit.log-query-string` | `false` | Put the raw query string into the logging MDC |
| `fluxgate.ratelimit.cost-header` | _(unset)_ | Header carrying the request cost in permits |
| `fluxgate.ratelimit.max-cost` | `1000` | Upper bound applied to `cost-header`. Must be **> 0** when `cost-header` is set; a `0` typo used to mean "unlimited" |
| `fluxgate.ratelimit.fail-on-missing-handler` | `false` | `true` fails startup instead of running with no rule set provider |
| `fluxgate.ratelimit.include-headers` | `true` | Master switch over both rate limit header families |
| `fluxgate.ratelimit.response.include-legacy-headers` | `true` | Write `X-RateLimit-Limit/Remaining/Reset` |
| `fluxgate.ratelimit.response.include-standard-headers` | `true` | Write `RateLimit-Limit/Remaining/Reset/Policy` |
| `fluxgate.ratelimit.response.content-type` | `application/problem+json` | 429 content type (`;charset=UTF-8` is appended) |
| `fluxgate.ratelimit.response.body-template` | _(unset)_ | Replaces the problem document. Placeholders: `{status}`, `{retryAfterSeconds}`, `{retryAfterMillis}`, `{remaining}`, `{limit}` |
| `fluxgate.ratelimit.fallback.mode` | `NONE` | `IN_MEMORY` keeps per-instance limits while Redis is down |
| `fluxgate.ratelimit.fallback.max-buckets` | `100000` | Fallback bucket cache size |
| `fluxgate.ratelimit.fallback.expire-after-access` | `1h` | Fallback bucket idle expiry |
| `fluxgate.ratelimit.wait-for-refill.enabled` | `false` | Enable the WAIT_FOR_REFILL policy |
| `fluxgate.ratelimit.wait-for-refill.max-wait-time-ms` | `5000` | Maximum time a request may wait |
| `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` | `50` | Maximum requests waiting at once, across the application |
| `fluxgate.ratelimit.filter-enabled` | `false` | **Deprecated and inert.** Use `fluxgate.ratelimit.enabled` |

### Metrics, health and hot reload

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.metrics.enabled` | `true` | Enable Micrometer metrics |
| `fluxgate.metrics.include-endpoint` | `true` | Add the `endpoint` tag |
| `fluxgate.metrics.endpoint-normalization` | `true` | Replace numeric/UUID/24-hex path segments with `{id}` |
| `fluxgate.metrics.max-endpoint-tags` | `1000` | Cap on distinct `endpoint` tag values |
| `fluxgate.actuator.health.enabled` | `true` | Register the `fluxgate` health indicator |
| `fluxgate.actuator.health.include-endpoint-details` | `false` | Include `host:port`, cluster node counts and failure messages in the health payload. Turn on only behind `show-details=when_authorized` |
| `fluxgate.reload.enabled` | `true` | Enable hot reload of rule sets |
| `fluxgate.reload.strategy` | `AUTO` | `AUTO`, `POLLING`, `PUBSUB`, `NONE` |
| `fluxgate.reload.cache.ttl` | `5m` | Rule cache TTL |
| `fluxgate.reload.cache.max-size` | `1000` | Rule cache size |
| `fluxgate.reload.cache.negative-ttl` | `5s` | How long a "rule set does not exist" answer is cached (`0` disables) |
| `fluxgate.reload.polling.interval` | `30s` | Polling interval |
| `fluxgate.reload.polling.initial-delay` | `10s` | Delay before the first poll |
| `fluxgate.reload.pubsub.channel` | `fluxgate:rule-reload` | Pub/Sub channel |
| `fluxgate.reload.pubsub.backstop-polling-interval` | `60s` | Polling backstop behind Pub/Sub (`0` disables) |
| `fluxgate.reload.pubsub.secret` | _(unset)_ | Shared HMAC-SHA256 secret. When set, every reload message without a valid signature — the legacy `"*"` included — is WARNed and ignored. Must equal the control plane's `fluxgate.control.secret` |
| `fluxgate.reload.pubsub.max-message-age` | `5m` | How old a signed message may be before it is ignored (replay window). Only applied when `secret` is set |

### Resilience

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.resilience.retry.enabled` | `true` | Retry failed limiter calls |
| `fluxgate.resilience.retry.max-attempts` | `3` | Total attempts |
| `fluxgate.resilience.retry.initial-backoff` | `100ms` | First backoff |
| `fluxgate.resilience.retry.multiplier` | `2.0` | Backoff multiplier |
| `fluxgate.resilience.retry.max-backoff` | `2s` | Backoff cap |
| `fluxgate.resilience.retry.jitter-factor` | `0.2` | ±20% jitter on the capped backoff |
| `fluxgate.resilience.retry.retry-on-timeout` | `false` | Whether timeouts are retried |
| `fluxgate.resilience.circuit-breaker.enabled` | `true` | Enable the circuit breaker. Retry alone triples the load on a failing dependency with nothing to stop it |
| `fluxgate.resilience.circuit-breaker.sliding-window-size` | `20` | Calls in the failure-rate window |
| `fluxgate.resilience.circuit-breaker.failure-rate-threshold` | `50` | Percent of failures that opens the circuit |
| `fluxgate.resilience.circuit-breaker.minimum-number-of-calls` | `10` | Calls required before the rate is evaluated |
| `fluxgate.resilience.circuit-breaker.failure-threshold` | _(unset)_ | Legacy consecutive-failure threshold, applied only when set |
| `fluxgate.resilience.circuit-breaker.wait-duration-in-open-state` | `30s` | Time before a half-open trial |
| `fluxgate.resilience.circuit-breaker.permitted-calls-in-half-open-state` | `3` | Concurrent trial calls allowed |
| `fluxgate.resilience.circuit-breaker.fallback` | `FAIL_OPEN` | **Deprecated and inert.** Behaviour comes from the fallback you pass |

### MongoDB DDL Auto Mode

The `fluxgate.mongo.ddl-auto` property controls how FluxGate handles MongoDB collections:

| Mode | Description |
|------|-------------|
| `validate` | (Default) Validates that collections exist. Throws an error if missing. |
| `create` | Creates collections if they don't exist, plus a `{ruleSetId: 1}` index and a unique `{ruleSetId: 1, id: 1}` index. Index failures are logged as warnings, not fatal. |

**Example configuration:**

```yaml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
    rule-collection: my_rate_limit_rules    # Custom collection name
    event-collection: my_rate_limit_events  # Optional: enable event logging
    ddl-auto: create                        # Auto-create collections and indexes
```

### Rate Limit Rule Configuration

```java
RateLimitRule rule = RateLimitRule.builder("api-rule")
    .name("API Rate Limit")
    .enabled(true)
    .scope(LimitScope.PER_IP)  // GLOBAL, PER_IP, PER_USER, PER_API_KEY, or CUSTOM
    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)  // or WAIT_FOR_REFILL
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
        .label("10-per-second")
        .build())
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100-per-minute")
        .build())
    .ruleSetId("api-limits")
    .attribute("tier", "standard")  // Custom attributes for tracking
    .build();
```

Band labels are optional. When you omit one, FluxGate derives `<capacity>-per-<windowSeconds>s`
(for example `100-per-60s`), or `<capacity>-per-<millis>ms` for a sub-second window
(`500-per-250ms`), and uses it as the bucket key segment, so two bands of the same rule can never
share a bucket. Two bands whose derived
labels collide are rejected by `build()` with an `InvalidRuleConfigException`. Renaming a
label moves that band's bucket, which resets it once.

### LimitScope Options

| LimitScope | Key Source | Resolved Key |
|------------|------------|--------------|
| `GLOBAL` | constant | `global` |
| `PER_IP` | `RequestContext.clientIp` | `ip:192.168.1.100` |
| `PER_USER` | `RequestContext.userId` | `user:user-123` |
| `PER_API_KEY` | `RequestContext.apiKey` | `key:abc123` |
| `CUSTOM` | `attributes.get(keyStrategyId)` | `custom:<value>` |

Key values are sanitised: characters outside `[A-Za-z0-9._:@-]` become `_`, and a value
longer than 256 characters is replaced by its SHA-256 hex digest. When the value a scope
needs is missing, `fluxgate.ratelimit.missing-key-behavior` decides between falling back
to the client IP (the key then carries `ip:`, its real source) and rejecting the request.

### Composite Key Example (IP + User)

For fine-grained rate limiting by IP and User combination:

```java
// Rule with CUSTOM scope
RateLimitRule rule = RateLimitRule.builder("composite-rule")
    .name("IP+User Rate Limit")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")  // Looks up context.attributes.get("ipUser")
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();

// RequestContextCustomizer builds the composite key
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        String userId = request.getHeader("X-User-Id");
        String clientIp = request.getRemoteAddr();

        // Prefix each component so they stay unambiguous: "ip:192.168.1.100:user:user-123"
        String compositeKey = userId != null
            ? "ip:" + clientIp + ":user:" + userId
            : "ip:" + clientIp;
        builder.attribute("ipUser", compositeKey);

        return builder;
    };
}
```

For multi-tenant systems, validate `X-Tenant-Id` at your application boundary before copying it into `RequestContext`. A safe custom key should include both tenant and subject, for example `tenantId + ":" + userId`, so one tenant cannot consume or reset another tenant's bucket.

### RequestContext Customization

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        // Set userId for PER_USER scope
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            builder.userId(userId);
        }

        // Set apiKey for PER_API_KEY scope
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }

        // Override client IP from Cloudflare header
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null) {
            builder.clientIp(cfIp);
        }

        // Add tenant info for CUSTOM scope with keyStrategyId="tenantId"
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

> **Security note.** `PER_USER` and `PER_API_KEY` read `RequestContext.userId` / `apiKey`, and in
> `HEADERS` mode those come from the `X-User-Id` and `X-API-Key` request headers — which a client
> controls, so any caller can pick which bucket to spend. With Spring Security on the classpath
> `fluxgate.ratelimit.identity.source` resolves to `PRINCIPAL_THEN_HEADERS`, which takes the
> authenticated principal first; set it to `PRINCIPAL` to ignore the headers entirely. A
> `RequestContextCustomizer` remains the escape hatch for anything more involved — see
> [docs/en/customization/request-context.md](docs/en/customization/request-context.md) and
> [SECURITY.md](SECURITY.md).

## Response Headers

FluxGate writes rate limit headers on allowed responses too, so a well behaved client can
pace itself instead of discovering the limit only after a 429. A value the limiter reports
as unknown (`-1`) is omitted rather than written as a misleading number.

| Header | Family | Meaning |
|--------|--------|---------|
| `X-RateLimit-Limit` | legacy | Capacity of the band that produced the decision |
| `X-RateLimit-Remaining` | legacy | Tokens left in that band |
| `X-RateLimit-Reset` | legacy | Epoch **seconds** at which the bucket is full again |
| `RateLimit-Limit` | IETF | Capacity of the band that produced the decision |
| `RateLimit-Remaining` | IETF | Tokens left in that band |
| `RateLimit-Reset` | IETF | **Delta seconds** until the bucket is full again |
| `RateLimit-Policy` | IETF | Quota policy, for example `100;w=60`. Omitted when the window is unknown or sub-second |
| `Retry-After` | both | Rejections only. Whole seconds, rounded up, never `0` |

Switch the families independently with
`fluxgate.ratelimit.response.include-legacy-headers` and
`response.include-standard-headers`, or turn both off with
`fluxgate.ratelimit.include-headers=false`.

A rejected request receives an RFC 9457 problem document:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json;charset=UTF-8
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 27
RateLimit-Policy: 100;w=60
Retry-After: 27

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 27 seconds","retryAfterMillis":26340}
```

Override the body with `fluxgate.ratelimit.response.body-template`, or take it over
completely with a `org.fluxgate.spring.filter.RateLimitResponseWriter` bean.

## Observability

FluxGate provides comprehensive observability features out of the box.

### Structured Logging

FluxGate outputs JSON-formatted logs with correlation IDs for easy integration with log aggregation systems like ELK Stack or Splunk.

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

The filter populates these SLF4J MDC keys (constants in
`org.fluxgate.core.constants.FluxgateConstants.MdcKeys`): `traceId`, `ruleSetId`, `method`,
`endpoint`, `clientIp`, `protocol`, `serverPort`, `userAgent`, `referer`, `userId`,
`apiKey` (masked), `rateLimitAllowed`, `remainingTokens`, `retryAfterMs`, `statusCode`,
`durationMs`, `error`, `errorMessage`, and `queryString` when
`fluxgate.ratelimit.log-query-string=true`.

FluxGate does **not** ship a logback configuration; turn the MDC into JSON with your own
encoder, for example [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder):

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>
```

Values derived from request headers are stripped of control characters and length-capped
before they reach the MDC, the API key is masked, and the MDC entries FluxGate found on
entry are restored when the request completes.

### Prometheus Metrics

FluxGate automatically exposes Micrometer-based metrics when `spring-boot-starter-actuator` is on the classpath.

Limiter failures are exposed through `fluxgate.limiter.failures` with `rule_set`, `endpoint`, `action`, and `exception` tags. Alert on non-zero `action=fail_open` in production, track `action=fail_closed` as a dependency incident signal, and `action=fallback_in_memory` as a "limits are per instance right now" signal.

**Available Metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `fluxgate_requests_total` | Counter | Rate limit decisions, tagged `result=allowed\|rejected`, plus `rule_set`, `endpoint`, `method` |
| `fluxgate_requests_duration_seconds` | Timer | Time spent deciding |
| `fluxgate_limiter_failures_total` | Counter | Limiter dependency failures by `action` and `exception` |
| `fluxgate_tokens_remaining` | Gauge | Remaining tokens in the bucket |

The meter is named `fluxgate.requests`; Prometheus appends `_total` to the counter. The
separate untagged `fluxgate.requests.total` meter that older versions also registered has
been removed, because both exported under the same Prometheus name. Sum over the `result`
tag instead.

**Example Prometheus output:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/test",method="GET",result="allowed",rule_set="api-limits"} 42.0
fluxgate_requests_total{endpoint="/api/test",method="GET",result="rejected",rule_set="api-limits"} 3.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/test",rule_set="api-limits"} 8.0
```

`endpoint` values are normalized (`/api/users/42` → `/api/users/{id}`) and the number of
distinct values is capped by `fluxgate.metrics.max-endpoint-tags`, so a path-parameter
heavy API cannot grow the meter registry without bound.

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

**Packaged dashboards and alerts:**

| Asset | Location |
|-------|----------|
| Grafana dashboard (10 panels) | [`docker/grafana/fluxgate-dashboard.json`](docker/grafana/fluxgate-dashboard.json) |
| Prometheus recording rules and alerts | [`docker/prometheus/fluxgate-alerts.yml`](docker/prometheus/fluxgate-alerts.yml) |

Import the dashboard JSON into Grafana and point the `datasource` variable at your
Prometheus; load the alert file with `rule_files` in `prometheus.yml`.

### Health

The `fluxgate` health indicator reports on the Redis and MongoDB dependencies, the
configured `failureBehavior` / `missingRuleBehavior`, and whether a filter and aspect bean
actually exist.

An unhealthy dependency reports the custom status **`DEGRADED`**, which Spring Boot maps to
HTTP 200 by default. Map it explicitly if a load balancer probes this endpoint:

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          DEGRADED: 503
```

Monitor `/actuator/health/fluxgate` for `DEGRADED` and `dependencyIssues=true`.

## Building from Source

```bash
# Clone the repository
git clone https://github.com/OpenFluxGate/fluxgate.git
cd fluxgate

# Build all modules
./mvnw clean install

# Build without tests
./mvnw clean install -DskipTests
```

### Test Tiers

FluxGate has two test tiers, so a clean checkout tests fully without infrastructure:

```bash
# Unit tests only - no Docker, no Redis, no MongoDB
./mvnw test

# Unit tests + integration tests (surefire + failsafe)
./mvnw verify

# Integration tests skipped explicitly
./mvnw verify -DskipITs

# Redis Cluster integration tests (opt-in profile)
./mvnw -pl fluxgate-redis-ratelimiter -Predis-cluster-it verify
```

Integration tests are the classes named `*IntegrationTest` or `*IT`. They obtain Redis and
MongoDB in this order:

1. a URI supplied through the environment,
2. a disposable [Testcontainers](https://testcontainers.com/) container,
3. **skip** — the tests abort with a JUnit assumption, they never fail, when neither is
   available.

| Variable | Purpose |
|----------|---------|
| `FLUXGATE_REDIS_URI` | Use an existing Redis instead of Testcontainers, e.g. `redis://localhost:6379` |
| `FLUXGATE_MONGO_URI` | Use an existing MongoDB, e.g. `mongodb://user:pass@localhost:27017/fluxgate?authSource=admin` |
| `FLUXGATE_MONGO_DB` | Database name to use with `FLUXGATE_MONGO_URI` |

Integration tests scope every key and collection they create to a per-JVM run id and clean
up only their own data, so pointing them at a shared development server is safe.

## Limitations

FluxGate is honest about what it does not do yet:

- **Cross-rule consumption is not atomic.** All bands of one rule are evaluated
  all-or-nothing in a single Lua call, but a rule set with several rules is evaluated rule
  by rule. Evaluation stops at the first rejecting rule, so earlier rules keep the tokens
  they already charged. Use one rule with several bands when you need strict atomicity.
- **Servlet only.** There is no WebFlux or reactive support, and no Spring Cloud Gateway
  filter. `WAIT_FOR_REFILL` blocks a worker thread, which would need redesigning for a
  reactive stack.
- **One algorithm.** Token bucket only. No sliding window, no fixed window, no calendar
  quotas (daily/monthly).
- **No rule matching by path or method.** A rule has a scope and bands, not a path
  pattern. Select the rule set per request surface by registering more than one filter with
  different `include-patterns`, or by setting `default-rule-set-id` per application.
- **No bucket introspection or manual reset API.** There is no endpoint to read or clear a
  single caller's bucket; a rule-set reload resets the buckets of that rule set.

## Documentation

- [FluxGate Core](fluxgate-core/README.md) - Core rate limiting concepts and API
- [Redis Rate Limiter](fluxgate-redis-ratelimiter/README.md) - Distributed rate limiting with Redis, Lua contract, key format
- [MongoDB Adapter](fluxgate-mongo-adapter/README.md) - Dynamic rule management
- [Spring Boot 3 Starter](fluxgate-spring-boot3-starter/README.md) - Auto-configuration and full property reference
- [Testkit](fluxgate-testkit/README.md) - In-memory handler, rule builders, JUnit 5 extension, benchmarks
- [Documentation Index](docs/README.md) - Architecture deep dives, customization guides, migration notes
- [Migrating to 0.4](docs/en/operations/migration-0.4.md) - Upgrade impact from 0.3.x
- [Changelog](CHANGELOG.md) - What changed, with the breaking list
- [Security Policy](SECURITY.md) - Reporting, secure defaults, tenant isolation
- [Contributing Guide](CONTRIBUTING.md) - Contribute Guide

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Related Projects

| Project | Description |
|---------|-------------|
| [FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio) | Web-based admin UI for managing rate limit rules |

## Roadmap

- [x] Prometheus metrics integration
- [x] Redis Cluster support
- [x] Structured JSON logging with correlation IDs
- [x] Rate limit quota management UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
- [x] Circuit breaker and retry integration (wired through `ResilientRateLimiter`)
- [x] In-memory limiter and in-memory fallback during a Redis outage
- [x] Standard IETF `RateLimit-*` response headers and RFC 9457 problem responses
- [x] Modularization
- [ ] Sliding window rate limiting algorithm
- [ ] Calendar quotas (daily / monthly)
- [ ] Rule matching by path and method
- [ ] WebFlux / reactive support and a Spring Cloud Gateway filter
- [ ] gRPC API support
- [ ] Bucket introspection and manual reset API

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Bucket4j](https://github.com/bucket4j/bucket4j) - The underlying rate limiting library
- [Lettuce](https://lettuce.io/) - Redis client for Java
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework

---

**FluxGate** - Distributed Rate Limiting Made Simple
