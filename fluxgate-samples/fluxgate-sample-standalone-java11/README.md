# FluxGate Sample: Standalone (Java 11)

A complete standalone application demonstrating FluxGate with direct MongoDB and Redis
integration, built for **Java 11 / Spring Boot 2.7** instead of the Java 21 / Boot 3 stack used by
[`fluxgate-sample-standalone-java21`](../fluxgate-sample-standalone-java21).

> **Spring Boot 2.7 is OSS end-of-life.** This sample pins `spring-boot.version` to `2.7.18`, the
> last OSS release in the 2.7 line, and depends on `fluxgate-spring-boot2-starter` (the `javax.servlet`
> counterpart of `fluxgate-spring-boot3-starter`). It exists as a compatibility reference for
> applications that haven't migrated to Java 17+/Boot 3 yet. It is not a recommendation to stay on
> Boot 2.7 — treat the starter/sample as a bridge, not a destination.

## Overview

This sample showcases:

- Direct MongoDB connection for rule storage
- Direct Redis connection for rate limiting
- **Multiple rate limit filters with different rule sets**, registered by hand as
  `FilterRegistrationBean`s (no `@EnableFluxgateFilter`)
- **RequestContext customization** for header-based identity (`X-User-Id`, `X-API-Key`,
  `X-Real-IP`) plus tenant/request-id attributes and IP+User composite key building
- **AOP-based rate limiting** with `@RateLimit` via `@EnableFluxgateAspect`
- A `@RestControllerAdvice` (`RateLimitExceptionHandler`) that renders `RateLimitExceededException`
  as a 429 in the application's own JSON shape
- Runtime rule management via REST API
- No manual Redis wiring: unlike the Java 21 sample's `FluxgateConfig`, this module has **no**
  custom `@Configuration` class for Mongo/Redis beans — `fluxgate-spring-boot2-starter`'s
  auto-configuration creates the `RateLimitRuleSetProvider` and `RateLimiter` beans from
  `fluxgate.mongo.*` / `fluxgate.redis.*` properties alone

## Prerequisites

- Java 11+
- Docker (for MongoDB and Redis)

### Start Infrastructure

```bash
# Start MongoDB
docker run -d --name mongodb -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:latest

# Start Redis
docker run -d --name redis -p 6379:6379 redis:latest
```

## Running the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-standalone-java11
```

The application starts on port **8085** (same port as the Java 21 sample — run one at a time).

## API Endpoints

### Rule Management APIs (`AdminController`, `/api/admin`)

| Method | Endpoint | Description |
|--------|----------|--------------|
| POST | `/api/admin/rules/standalone` | Create rule for `standalone-rules` (10 req/min, PER_IP) |
| POST | `/api/admin/rules/multi-filter` | Create 2 rules for `multi-filter-rules` (10 + 20 req/min, PER_IP) |
| POST | `/api/admin/rules/composite` | Create rule for `composite-key-rules` (10 req/min per IP+User, CUSTOM scope) |
| GET | `/api/admin/rules/{ruleSetId}` | Get rules by rule set ID |

There is no `POST /api/admin/rules/all` convenience endpoint here (unlike the class-level Javadoc
suggests) — create each rule set individually with the three POST calls above.

### Filter-based Test Endpoints (`FilterTestController`, `/api/test/filter`)

| Method | Endpoint | Description |
|--------|----------|--------------|
| GET | `/api/test/filter` | Rate-limited endpoint; see filter note below |
| GET | `/api/test/filter/multi-filter` | Rate-limited endpoint; see filter note below |
| GET | `/api/test/filter/composite?userId=` | Rate-limited endpoint; see filter note below |
| GET | `/api/test/filter/info` | Returns in-memory request counters for the three endpoints above (not itself rate-limited) |

### AOP-based Test Endpoint (`AopTestController`, `/api/test/aop`)

| Method | Endpoint | RuleSet | Enforced by |
|--------|----------|---------|-------------|
| GET | `/api/test/aop/aop-test1` | `standalone-rules` | `RateLimitAspect` (`@RateLimit(ruleSetId = "standalone-rules")`), not a servlet filter |

`apiFilter`'s `/api/test/**` include pattern (see below) isn't excluded for `/api/test/aop/**`
either, so a call to `/api/test/aop/aop-test1` is checked by *both* the filter and the aspect
against the same `standalone-rules` bucket — expect it to exhaust the 10-token/minute budget
roughly twice as fast as an endpoint that only goes through one enforcement point.

## Quick Start

```bash
# 1. Create the standalone rule (10 req/min, PER_IP)
curl -X POST http://localhost:8085/api/admin/rules/standalone | jq

# 2. Send 12 requests to the filter-covered endpoint - first 10 succeed, last 2 get 429
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/test/filter
  echo ""
done
```

`/api/test/filter` is only checked by `apiFilter` (see the path-matching note below), so it's a
reliable way to see the 10 req/min `standalone-rules` limit in isolation.

## Servlet Filter Configuration (`MultipleFiltersConfig`)

Three `FluxgateRateLimitFilter`s are registered as separate `FilterRegistrationBean`s:

| Order | Filter | Include Pattern | Exclude Pattern | RuleSet |
|-------|--------|------------------|------------------|---------|
| 1 | `compositeKeyApiFilter` | `/api/test/composite/**` | — | `composite-key-rules` |
| 2 | `multiFilterApiFilter` | `/api/test/multi-filter/**` | — | `multi-filter-rules` |
| 3 | `apiFilter` | `/api/test/**` | `/api/test/composite/**`, `/api/test/multi-filter/**` | `standalone-rules` |

> **Note on path matching.** `FilterTestController`'s endpoints live under `/api/test/filter/**`
> (e.g. `/api/test/filter/composite`), not directly under `/api/test/composite` or
> `/api/test/multi-filter`. Because `compositeKeyApiFilter` and `multiFilterApiFilter` only match
> those literal top-level paths, neither one currently intercepts `/api/test/filter/**` requests —
> those all fall through to `apiFilter`'s broader `/api/test/**` pattern (its excludes don't match
> `/api/test/filter/...` either) and are rate-limited under `standalone-rules` only. Keep this in
> mind if you adapt the sample: include/exclude patterns are matched against the literal request
> path, not a "logical" endpoint grouping.

## RequestContext Customization

`MultipleFiltersConfig` registers a `RequestContextCustomizer` bean that reads identity and
tracing values from headers before the filters run:

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
  return (builder, request) -> {
    // X-User-Id -> builder.userId()      (PER_USER scope)
    // X-API-Key -> builder.apiKey()      (PER_API_KEY scope)
    // X-Real-IP -> builder.clientIp()    (overrides IP for proxy setups)
    // X-Tenant-Id -> attribute("tenantId", ...)   (CUSTOM scope)
    // X-Request-Id -> attribute("requestId", ...) (tracing only, not used for rate limiting)
    // Composite "ipUser" attribute = clientIp + ":" + userId, for CUSTOM scope keyStrategyId="ipUser"
    ...
  };
}
```

The composite key is what `composite-key-rules` (created via `POST /api/admin/rules/composite`)
looks up through `keyStrategyId="ipUser"`.

## Rate Limit Handler (`StandaloneRateLimitHandler`)

A custom `FluxgateRateLimitHandler` bean that:

1. Looks up the rule set from MongoDB via `RateLimitRuleSetProvider`
2. Applies rate limiting via Redis using `RateLimiter`
3. Falls back to `fluxgate.ratelimit.missing-rule-behavior` (`ALLOW`/`DENY`) when the rule set
   isn't found yet

## Rate Limit Exception Handling (`RateLimitExceptionHandler`)

```java
@RestControllerAdvice
public class RateLimitExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException e) {
    // 429, Retry-After header, and a { "error", "message", "retryAfterSeconds" } body
  }
}
```

`RateLimitExceededException` is thrown by the `RateLimitAspect` instead of writing the 429
response itself when a `@RateLimit`-annotated method uses `throwOnReject = true`, or whenever the
invocation has no servlet response to write to (a scheduled task, a message listener). Without an
advice like this one, Spring would turn the rejection into a 500.

The sample's only `@RateLimit` endpoint, `/api/test/aop/aop-test1`, does **not** set
`throwOnReject = true` — under normal HTTP handling the aspect writes its own 429 response
directly, so this advice doesn't fire for it today. It's included so the handler is ready as soon
as a `throwOnReject = true` endpoint (or a non-servlet `@RateLimit` invocation) is added.

## Configuration

### application.yml

```yaml
server:
  port: 8085

fluxgate:
  mongo:
    enabled: true
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events
    ddl-auto: validate   # create for the docker profile below
  redis:
    enabled: true
    uri: redis://localhost:6379
  metrics:
    enabled: true
  resilience:
    retry:
      enabled: true
      max-attempts: 3
      initial-backoff: 100ms
      multiplier: 2.0
      max-backoff: 2s
    circuit-breaker:
      enabled: false   # true, with failure-threshold/wait-duration-in-open-state, in the docker profile
      failure-threshold: 5
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open-state: 3
  ratelimit:
    enabled: true
    # ALLOW (this sample) or DENY (starter default) when no matching rule is found
    missing-rule-behavior: ALLOW
  reload:
    enabled: true
    strategy: PUBSUB   # Redis Pub/Sub for real-time rule updates
    pubsub:
      channel: fluxgate:rule-reload   # must match the Admin API's reload channel
```

`fluxgate.resilience.*` is bound by `FluxgateResilienceProperties`
(`fluxgate-spring-boot2-starter/src/main/java/org/fluxgate/spring/properties/FluxgateResilienceProperties.java`),
a sibling of `FluxgateProperties`, not a nested block of it.

### Docker Profile

For Docker Compose environments:

```yaml
spring:
  config:
    activate:
      on-profile: docker

fluxgate:
  mongo:
    uri: mongodb://fluxgate:fluxgate123@mongodb:27017/fluxgate?authSource=admin
    ddl-auto: create
  redis:
    uri: redis://redis:6379
  resilience:
    retry:
      max-attempts: 5
      initial-backoff: 200ms
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      wait-duration-in-open-state: 30s
```

## Key Components

### StandaloneJava11Application.java

```java
@SpringBootApplication
@EnableConfigurationProperties(FluxgateProperties.class)
@EnableFluxgateAspect
public class StandaloneJava11Application {
  public static void main(String[] args) {
    SpringApplication.run(StandaloneJava11Application.class, args);
  }
}
```

No `@EnableFluxgateFilter` here — the three servlet filters come from `MultipleFiltersConfig`'s
manual `FilterRegistrationBean`s instead.

### MultipleFiltersConfig.java

Registers the `requestContextCustomizer` bean and the three `FluxgateRateLimitFilter`
registrations described above.

### StandaloneRateLimitHandler.java

The custom `FluxgateRateLimitHandler` described above, backing both the servlet filters and the
`@RateLimit` aspect.

### advice/RateLimitExceptionHandler.java

The `@RestControllerAdvice` described above.

## Observability

### Endpoints

| Endpoint | Description |
|----------|--------------|
| `/actuator/health` | Application health with FluxGate component status |
| `/actuator/prometheus` | Prometheus metrics endpoint |
| `/actuator/metrics` | Spring Boot metrics |

### Prometheus Metrics

```bash
curl http://localhost:8085/actuator/prometheus | grep fluxgate
```

Key metrics (from `fluxgate-spring-boot2-starter`'s `FluxgateMetrics`):

- `fluxgate_requests_total{result="allowed"}` / `{result="rejected"}` — requests by outcome
- `fluxgate_requests_duration_seconds` — request processing time
- `fluxgate_limiter_failures_total` — limiter failures, tagged with the action taken
- `fluxgate_tokens_remaining` — remaining tokens per rule set

There is deliberately no separate `fluxgate.requests.total` counter — it would export under the
same Prometheus name as `fluxgate.requests` (`fluxgate_requests_total`), which is a metric name
collision. Sum `fluxgate_requests_total` over the `result` tag instead.

## Swagger UI

```
http://localhost:8085/swagger-ui.html
```

Served by `springdoc-openapi-ui` 1.8.0, the Boot 2.x / `javax`-based SpringDoc artifact (the Java
21 sample uses `springdoc-openapi-starter-webmvc-ui` 2.x for Jakarta/Boot 3).

## Learn More

- [FluxGate Samples Overview](../README.md)
- [FluxGate Sample: Standalone (Java 21)](../fluxgate-sample-standalone-java21/README.md)
- [FluxGate Core](../../fluxgate-core/README.md)
- [FluxGate Spring Boot 2.x Starter](../../fluxgate-spring-boot2-starter/README.md)
- [Redis Rate Limiter](../../fluxgate-redis-ratelimiter/README.md)
