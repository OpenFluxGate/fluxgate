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
- **RequestContext customization**: IP+User composite key building on top of the identity and
  client IP the starter resolves (`fluxgate.ratelimit.identity.source`, `trusted-proxies`)
- **AOP-based rate limiting** with `@RateLimit` via `@EnableFluxgateAspect`
- A `@RestControllerAdvice` (`RateLimitExceptionHandler`) that renders `RateLimitExceededException`
  as a 429 (limit exceeded) or a 503 (rate limiting unavailable) in the application's own JSON shape
- Runtime rule management via REST API
- No manual Redis wiring: unlike the Java 21 sample's `FluxgateConfig`, this module has **no**
  custom `@Configuration` class for Mongo/Redis beans — `fluxgate-spring-boot2-starter`'s
  auto-configuration creates the `RateLimitRuleSetProvider` and `RateLimiter` beans from
  `fluxgate.mongo.*` / `fluxgate.redis.*` properties alone

## Prerequisites

- Java 11+ to run the sample; JDK 17+ to build it (see the note under
  [Running the Application](#running-the-application))
- Docker (for MongoDB and Redis)

### Start Infrastructure

```bash
# Start MongoDB
docker run -d --name mongodb -p 127.0.0.1:27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:7.0.14

# Start Redis
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine
```

## Running the Application

> Run `./mvnw -B install -DskipTests` (JDK 21) once from the project root first, or
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-standalone-java11 -am` (JDK 17+ — the sample itself runs on Java 11):
> the samples depend on the `0.4.0-SNAPSHOT` modules (see [Build Once](../README.md#build-once)).

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

There is no `POST /api/admin/rules/all` convenience endpoint — create each rule set individually
with the three POST calls above. The admin endpoints are not rate limited.

### Filter-based Test Endpoints (`FilterTestController`, `/api/test`)

| Method | Endpoint | Filter | RuleSet |
|--------|----------|--------|---------|
| GET | `/api/test` | `apiFilter` | `standalone-rules` (10 req/min per IP) |
| GET | `/api/test/multi-filter` | `multiFilterApiFilter` | `multi-filter-rules` (10 + 20 req/min per IP; rejected when either is exceeded) |
| GET | `/api/test/composite?userId=` | `compositeKeyApiFilter` | `composite-key-rules` (10 req/min per IP+User) |
| GET | `/api/test/info` | `apiFilter` | `standalone-rules`; returns in-memory request counters for the three endpoints above |

Each endpoint is checked by exactly one filter (see [Servlet Filter Configuration](#servlet-filter-configuration-multiplefiltersconfig)),
so create the matching rule set first: with `missing-rule-behavior: DENY` an endpoint answers 429
until its rule set exists. The `userId` query parameter of `/api/test/composite` is only echoed in
the response; the rate limit key comes from the identity the starter resolves (see below).

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
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/test
  echo ""
done

# 3. The other filters work the same way with their own rule sets
curl -X POST http://localhost:8085/api/admin/rules/multi-filter | jq
curl -s http://localhost:8085/api/test/multi-filter | jq
curl -X POST http://localhost:8085/api/admin/rules/composite | jq
curl -s "http://localhost:8085/api/test/composite?userId=alice" | jq
```

`/api/test` is only checked by `apiFilter`, so it shows the 10 req/min `standalone-rules` limit in
isolation. `missing-rule-behavior` defaults to `DENY`, so before step 1 the same requests answer 429.

## Servlet Filter Configuration (`MultipleFiltersConfig`)

Three `FluxgateRateLimitFilter`s are registered as separate `FilterRegistrationBean`s:

| Order | Filter | Include Pattern | Exclude Pattern | RuleSet |
|-------|--------|------------------|------------------|---------|
| 1 | `compositeKeyApiFilter` | `/api/test/composite/**` | — | `composite-key-rules` |
| 2 | `multiFilterApiFilter` | `/api/test/multi-filter/**` | — | `multi-filter-rules` |
| 3 | `apiFilter` | `/api/test/**` | `/api/test/composite/**`, `/api/test/multi-filter/**` | `standalone-rules` |

Servlet URL patterns (`addUrlPatterns`) choose which requests reach a filter; the filter's own
Ant-style include/exclude patterns then decide which of those it checks. A pattern such as
`/api/test/composite/**` also matches `/api/test/composite` itself, so `apiFilter`'s excludes keep it
from checking the two dedicated endpoints a second time. Patterns are matched against the literal
request path.

## RequestContext Customization

`MultipleFiltersConfig` registers a `RequestContextCustomizer` bean that builds the composite
`ipUser` key. It does **not** read identity or the client IP from request headers: the starter has
already filled the builder according to `fluxgate.ratelimit.identity.source` (default `PRINCIPAL`)
and `trust-client-ip-header` / `trusted-proxies`, so a client-supplied `X-User-Id`, `X-API-Key` or
`X-Real-IP` cannot choose its own bucket.

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
  return (builder, request) -> {
    // Composite "ipUser" attribute = clientIp + ":" + userId, for CUSTOM scope keyStrategyId="ipUser"
    ...
  };
}
```

This sample has no Spring Security, so by default there is no user and the composite key degrades
to the IP. To try per-user keys locally, opt in explicitly (**demo only**):

```bash
FLUXGATE_IDENTITY_SOURCE=HEADERS ./mvnw -pl fluxgate-samples/fluxgate-sample-standalone-java11 spring-boot:run
```

`GET /api/test/composite` sits behind `compositeKeyApiFilter`, whose `composite-key-rules` rule
(`CUSTOM` scope, `keyStrategyId="ipUser"`) is keyed by this attribute. With the application above
running:

```bash
curl -X POST http://localhost:8085/api/admin/rules/composite
curl -H "X-User-Id: user-123" "http://localhost:8085/api/test/composite?userId=user-123"
```

`HEADERS` mirrors `identity.source=HEADERS` and is only safe behind a gateway that authenticates the
caller and strips any incoming `X-User-Id`. Pair it with `FLUXGATE_TRUST_CLIENT_IP_HEADER=true` and
`fluxgate.ratelimit.trusted-proxies` when a reverse proxy sits in front. The starter logs a warning at
startup when identity headers are enabled. API keys are never logged.

The composite key is what `composite-key-rules` (created via `POST /api/admin/rules/composite`)
looks up through `keyStrategyId="ipUser"`.

## Rate Limit Handler (`StandaloneRateLimitHandler`)

A custom `FluxgateRateLimitHandler` bean that:

1. Looks up the rule set from MongoDB via `RateLimitRuleSetProvider`
2. Applies rate limiting via Redis using `RateLimiter`
3. Falls back to `fluxgate.ratelimit.missing-rule-behavior` (`DENY` by default; `ALLOW` is demo-only,
   via `FLUXGATE_MISSING_RULE_BEHAVIOR`) when the rule set isn't found yet - create the rules with
   `POST /api/admin/rules/*` first

## Rate Limit Exception Handling (`RateLimitExceptionHandler`)

```java
@RestControllerAdvice
@Order(RateLimitExceededExceptionHandler.ORDER - 1)
public class RateLimitExceptionHandler {

  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException e) {
    // 503 when e.isServiceUnavailable(), otherwise 429; Retry-After when the wait is known;
    // a { "error", "message", "retryAfterSeconds" } body
  }
}
```

`RateLimitExceededException` is thrown by the `RateLimitAspect` instead of writing the 429
response itself when a `@RateLimit`-annotated method uses `throwOnReject = true`, or whenever the
invocation has no servlet response to write to (a scheduled task, a message listener). The starter
already maps it to 429, or 503 when `isServiceUnavailable()` is `true`, with its own problem document;
this advice is ordered just before it (`RateLimitExceededExceptionHandler.ORDER - 1`; an unordered advice
runs after the starter's and never sees the exception) to render the application's own body for both cases.

The sample's only `@RateLimit` endpoint, `/api/test/aop/aop-test1`, does **not** set
`throwOnReject = true` — under normal HTTP handling the aspect writes its own 429 response
directly, so this advice doesn't fire for it today. It's included so the handler is ready as soon
as a `throwOnReject = true` endpoint (or a non-servlet `@RateLimit` invocation) is added.

## Configuration

### application.yml

The default profile (comments shortened; see `src/main/resources/application.yml`):

```yaml
server:
  port: 8085

spring:
  application:
    name: fluxgate-sample-standalone

fluxgate:
  mongo:
    enabled: true
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events
    ddl-auto: create        # builds the collections and the unique (ruleSetId, id) index
  redis:
    enabled: true
    uri: redis://localhost:6379
    #uri: redis://127.0.0.1:7100,redis://127.0.0.1:7101,redis://127.0.0.1:7102   # docker/redis-cluster.yml
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
      enabled: false
      failure-threshold: 5
      wait-duration-in-open-state: 30s
      permitted-calls-in-half-open-state: 3
  ratelimit:
    enabled: true
    missing-rule-behavior: ${FLUXGATE_MISSING_RULE_BEHAVIOR:DENY}   # ALLOW is demo only
    failure-behavior: DENY                                          # Redis/store failure: DENY (default) or ALLOW
    identity:
      source: ${FLUXGATE_IDENTITY_SOURCE:PRINCIPAL}                 # HEADERS is demo only
    client-ip-header: X-Forwarded-For
    trust-client-ip-header: ${FLUXGATE_TRUST_CLIENT_IP_HEADER:false}
    trusted-proxies: ${FLUXGATE_TRUSTED_PROXIES:}
  reload:
    enabled: true
    strategy: AUTO           # Pub/Sub when a secret is set or allow-unsigned=true, otherwise polling
    pubsub:
      channel: fluxgate:rule-reload
      secret: ${FLUXGATE_RELOAD_SECRET:}                    # same value as fluxgate.control.secret
      allow-unsigned: ${FLUXGATE_RELOAD_ALLOW_UNSIGNED:false}  # local demo only

logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.sample.standalone: DEBUG
    root: info

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: when_authorized
      show-components: when_authorized
    prometheus:
      enabled: true

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    tags-sorter: alpha
    operations-sorter: alpha
```

Whether a request is allowed or denied while Redis or the rule store is failing (including while
the circuit is open) is `fluxgate.ratelimit.failure-behavior` (default `DENY`). The older
`circuit-breaker.fallback` key is deprecated, has no effect, and is not set here.

`reload.strategy: AUTO` lets the sample start with no secret: without `FLUXGATE_RELOAD_SECRET` and
without `FLUXGATE_RELOAD_ALLOW_UNSIGNED=true` it picks up rule changes by polling (a WARN at startup
says so); with the secret set — the same value as the control plane's `fluxgate.control.secret` — it
switches to signed Redis Pub/Sub with a polling backstop, and with `FLUXGATE_RELOAD_ALLOW_UNSIGNED=true`
alone to unsigned Pub/Sub (local demo only). `strategy: PUBSUB` refuses to start without a secret unless
`FLUXGATE_RELOAD_ALLOW_UNSIGNED=true` (local demo only).

`fluxgate.resilience.*` is bound by `FluxgateResilienceProperties`
(`fluxgate-spring-boot2-starter/src/main/java/org/fluxgate/spring/properties/FluxgateResilienceProperties.java`),
a sibling of `FluxgateProperties`, not a nested block of it.

### Docker Profile

For Docker Compose environments (`--spring.profiles.active=docker`; `mongo` and `redis` are the
service names in `docker/full.yml`):

```yaml
spring:
  config:
    activate:
      on-profile: docker

fluxgate:
  mongo:
    enabled: true
    uri: mongodb://${MONGO_USER:fluxgate}:${MONGO_PASSWORD:fluxgate123}@mongo:27017/fluxgate?authSource=admin
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events
    ddl-auto: create
  redis:
    uri: redis://redis:6379
  resilience:
    retry:
      enabled: true
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
| `/actuator/health` | Application health; the `fluxgate` component and its details only for authorized users (`when_authorized`; this sample has no Spring Security, so anonymous calls see `{"status": "UP"}`) |
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
