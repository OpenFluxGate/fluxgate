# FluxGate Sample: Standalone

A complete standalone application demonstrating FluxGate with direct MongoDB and Redis integration
on Java 21 / Spring Boot 3.

## Overview

This sample showcases:
- Direct MongoDB connection for rule storage (the starter's `fluxgate.mongo.*` auto-configuration)
- Direct Redis connection for rate limiting (`FluxgateConfig` builds the `RedisRateLimiter`)
- **Multiple rate limit filters with different rule sets**, registered by hand as
  `FilterRegistrationBean`s in `MultipleFiltersConfig` (no `@EnableFluxgateFilter`)
- **LimitScope-based key resolution** (PER_IP, PER_USER, PER_API_KEY, CUSTOM)
- **Composite key support** (IP + User ID combination)
- **RequestContext customization** that derives a composite key attribute
- **AOP-based rate limiting** with `@RateLimit` via `@EnableFluxgateAspect`, and a
  `@RestControllerAdvice` (`RateLimitExceptionHandler`) for `RateLimitExceededException`
- Runtime rule management via REST API
- Actuator health and Prometheus metrics

## Prerequisites

- Java 21+
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
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-standalone-java21 -am` (JDK 21):
> the samples depend on the `0.4.0-SNAPSHOT` modules (see [Build Once](../README.md#build-once)).

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-standalone-java21
```

The application starts on port **8085**.

## API Endpoints

### Rule Management APIs (`AdminController`, `/api/admin`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/admin/rules/standalone` | Create the `standalone-rules` rule (10 req/min, PER_IP) |
| POST | `/api/admin/rules/multi-filter` | Create the 2 `multi-filter-rules` rules (10 + 20 req/min, PER_IP) |
| POST | `/api/admin/rules/composite` | Create the `composite-key-rules` rule (10 req/min per IP+User, CUSTOM) |
| GET | `/api/admin/rules/{ruleSetId}` | Get the rules of a rule set (`exists: false` if there are none) |

There is no endpoint that creates every rule set at once: call the three POSTs. The admin endpoints
are not rate limited.

### Test Endpoints (`TestController`, `/api/test`)

| Method | Endpoint | RuleSet | LimitScope | Limits |
|--------|----------|---------|------------|--------|
| GET | `/api/test` | standalone-rules | PER_IP | 10 req/min |
| GET | `/api/test/multi-filter` | multi-filter-rules | PER_IP | 10 + 20 req/min |
| GET | `/api/test/composite?userId=` | composite-key-rules | CUSTOM (ipUser) | 10 req/min per IP+User |
| GET | `/api/test/info` | standalone-rules | PER_IP | Request counters and setup hints (it is under `/api/test/**`, so `apiFilter` limits it too) |

`userId` is a **required** query parameter of `/api/test/composite` (400 without it); the endpoint
only echoes it. The rate limit key uses the resolved identity instead (see Step 4).

### AOP Test Endpoints (`AopTestController`, `/api/test/aop`)

| Method | Endpoint | RuleSet | On reject |
|--------|----------|---------|-----------|
| GET | `/api/test/aop/aop-test1` | standalone-rules | `@RateLimit(ruleSetId = "standalone-rules")`: the aspect writes the 429 itself |
| GET | `/api/test/aop/aop-test2` | standalone-rules | `@RateLimit(..., throwOnReject = true)`: `RateLimitExceptionHandler` renders `{"error": "RATE_LIMITED", "message", "retryAfterSeconds"}` |

`apiFilter`'s `/api/test/**` include pattern does not exclude `/api/test/aop/**`, so these calls are
checked by **both** the filter and the aspect against the same `standalone-rules` bucket and use it up
twice as fast.

## Quick Start

### Step 1: Create the Rules

`missing-rule-behavior` defaults to `DENY`: until a rule set exists, its endpoints answer 429.

```bash
curl -X POST http://localhost:8085/api/admin/rules/standalone | jq
curl -X POST http://localhost:8085/api/admin/rules/multi-filter | jq
curl -X POST http://localhost:8085/api/admin/rules/composite | jq
```

Response of the first call:
```json
{
  "success": true,
  "ruleSetId": "standalone-rules",
  "ruleId": "standalone-10-per-minute",
  "endpoint": "/api/test",
  "limit": "10 requests per minute per IP",
  "createdAt": "2026-10-01T00:00:00Z"
}
```

### Step 2: Test Standalone Endpoint (10 req/min)

```bash
# Send 12 requests - first 10 succeed, last 2 get 429
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/test
  echo ""
done
```

Expected:
```
Request 1-10: 200
Request 11-12: 429
```

### Step 3: Test Multi-Filter Endpoint (10 req/min + 20 req/min)

```bash
# Send 12 requests - rule1 (10 req/min) will trigger first
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/test/multi-filter
  echo ""
done
```

Expected (rule1 triggers at 11th request):
```
Request 1-10: 200
Request 11-12: 429
```

Note: Both rules apply. Since rule1 (10 req/min) is stricter than rule2 (20 req/min), rule1 triggers first.

### Step 4: Test Composite Key Endpoint (IP + User)

The user id in the composite key comes from the resolved identity, not from the `userId` query
parameter. This sample has no Spring Security, so start the app with
`FLUXGATE_IDENTITY_SOURCE=HEADERS` (**DEMO ONLY**) for the `X-User-Id` header to be used. Without it,
both users fall back to the client IP and share one bucket.

```bash
# User A - gets 10 requests
for i in {1..12}; do
  echo -n "User A Request $i: "
  curl -s -o /dev/null -w "%{http_code}" -H "X-User-Id: user-A" "http://localhost:8085/api/test/composite?userId=user-A"
  echo ""
done

# User B - also gets 10 requests (separate bucket!)
for i in {1..12}; do
  echo -n "User B Request $i: "
  curl -s -o /dev/null -w "%{http_code}" -H "X-User-Id: user-B" "http://localhost:8085/api/test/composite?userId=user-B"
  echo ""
done
```

Expected (with `FLUXGATE_IDENTITY_SOURCE=HEADERS`): User A gets 429 after 10 requests, but User B still has 10 requests available because they have separate buckets (different composite keys: `127.0.0.1:user-A` vs `127.0.0.1:user-B`).

### Step 5: Check Configuration

```bash
curl http://localhost:8085/api/test/info | jq
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                 Servlet Filter Chain                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Order=1  compositeKeyApiFilter                                 │
│           └─ /api/test/composite/** → "composite-key-rules"     │
│              (CUSTOM scope: IP+User composite key)              │
│                          │                                      │
│  Order=2  multiFilterApiFilter                                  │
│           └─ /api/test/multi-filter/** → "multi-filter-rules"   │
│              (PER_IP: 2 rules)                                  │
│                          │                                      │
│  Order=3  apiFilter      ▼                                      │
│           └─ /api/test/** → "standalone-rules"                  │
│              (PER_IP: 1 rule)                                   │
│              ⚠️  excludes: /api/test/composite/**               │
│              ⚠️  excludes: /api/test/multi-filter/**            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

Each registration also sets a servlet URL pattern (`/api/test/composite/*`,
`/api/test/multi-filter/*`, `/api/test/*`); the include/exclude patterns above decide which of those
requests the filter actually limits. All three filters share the `StandaloneRateLimitHandler` bean and
the `RequestContextCustomizer`.

### Important: Excluding Overlapping URL Patterns

When using multiple filters with overlapping URL patterns, you **MUST** exclude the more specific pattern from the broader filter to prevent double rate limiting.

```java
// apiFilter covers /api/test/** but MUST exclude the specific endpoints
new String[] {"/api/test/**"},                                          // includePatterns
new String[] {"/api/test/composite/**", "/api/test/multi-filter/**"},   // excludePatterns - REQUIRED!
```

**Without exclusion**, a request to `/api/test/multi-filter` would be rate-limited by BOTH filters:

1. `multiFilterApiFilter` (order=2) → applies `multi-filter-rules` (2 rules)
2. `apiFilter` (order=3) → applies `standalone-rules` (1 rule) - **UNINTENDED!**

This would result in **3 rules** being applied instead of the intended **2 rules**.

## Rule Configuration

| Rule Set | Rule ID | LimitScope | Limit | Band label | Endpoint |
|----------|---------|------------|-------|------------|----------|
| standalone-rules | standalone-10-per-minute | PER_IP | 10 req/min | per-minute | /api/test |
| multi-filter-rules | multi-filter-10-per-minute | PER_IP | 10 req/min | rule1-per-minute | /api/test/multi-filter |
| multi-filter-rules | multi-filter-20-per-minute | PER_IP | 20 req/min | rule2-per-minute | /api/test/multi-filter |
| composite-key-rules | composite-10-per-minute | CUSTOM (ipUser) | 10 req/min | per-minute | /api/test/composite |

## LimitScope-based Key Resolution

The `LimitScopeKeyResolver` resolves rate limit keys based on the rule's `LimitScope`:

| LimitScope | Key Source | Description |
|------------|------------|-------------|
| `GLOBAL` | `"global"` | Single bucket for all requests |
| `PER_IP` | `RequestContext.clientIp` | One bucket per IP address |
| `PER_USER` | `RequestContext.userId` | One bucket per user (authenticated principal; `X-User-Id` only with the demo opt-in below) |
| `PER_API_KEY` | `RequestContext.apiKey` | One bucket per API key (authenticated principal; `X-API-Key` only with the demo opt-in below) |
| `CUSTOM` | `attributes.get(keyStrategyId)` | Custom key from RequestContext attributes |

## Composite Key (IP + User)

The composite key endpoint demonstrates rate limiting by IP and User combination using `LimitScope.CUSTOM`:

```java
// Rule configuration (AdminController)
RateLimitRule rule = RateLimitRule.builder("composite-10-per-minute")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")  // Looks up context.attributes.get("ipUser")
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
    .ruleSetId("composite-key-rules")
    .build();
```

The `RequestContextCustomizer` (see below) builds the `ipUser` attribute from the resolved client
IP and user id, falling back to the IP alone.

### Redis Key Examples (Composite)

The `CUSTOM` scope prefixes the attribute value with `custom:`. User ids are only present with
`FLUXGATE_IDENTITY_SOURCE=HEADERS` (see Step 4).

| User ID | Composite Key | Redis Key |
|---------|---------------|-----------|
| (none) | `127.0.0.1` | `fluxgate:bucket:{composite-key-rules:composite-10-per-minute:custom:127.0.0.1}:per-minute` |
| `user-A` | `127.0.0.1:user-A` | `fluxgate:bucket:{composite-key-rules:composite-10-per-minute:custom:127.0.0.1:user-A}:per-minute` |
| `user-B` | `127.0.0.1:user-B` | `fluxgate:bucket:{composite-key-rules:composite-10-per-minute:custom:127.0.0.1:user-B}:per-minute` |

## Rate Limiting by User ID or API Key

The rules this sample creates are `PER_IP` and `CUSTOM`. To try `PER_USER` or `PER_API_KEY`, store a
rule with that scope in `standalone-rules` yourself (there is no endpoint for it):

```bash
# 1. PER_IP (default for standalone-rules)
curl http://localhost:8085/api/test

# 2. PER_USER - requires rule with scope=PER_USER
#    userId comes from the authenticated principal. This sample has no Spring Security, so to try it
#    start the app with FLUXGATE_IDENTITY_SOURCE=HEADERS (DEMO ONLY - any caller can then pick its
#    own bucket unless a gateway authenticates it and strips the header):
curl -H "X-User-Id: user-123" http://localhost:8085/api/test

# 3. PER_API_KEY - requires rule with scope=PER_API_KEY
#    Same opt-in (identity.source=HEADERS, DEMO ONLY) for the X-API-Key header
curl -H "X-API-Key: api-key-abc" http://localhost:8085/api/test

# 4. CUSTOM (composite key) - IP+User combination (user part needs FLUXGATE_IDENTITY_SOURCE=HEADERS)
curl -H "X-User-Id: user-123" "http://localhost:8085/api/test/composite?userId=user-123"
```

### Redis Key Format

The Redis key format is: `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandLabel>`. The braces
are a Redis Cluster hash tag, and the bucket hash holds `tokens` and `last_refill_micros`.

The `<keyValue>` is determined by the rule's `LimitScope` and carries a scope prefix. `X-User-Id` and
`X-API-Key` are only used with `FLUXGATE_IDENTITY_SOURCE=HEADERS` (**DEMO ONLY**); otherwise the
key falls back to the client IP:

| LimitScope | Header/Param | Key Value | Redis Key Example |
|------------|--------------|-----------|-------------------|
| PER_IP | (none) | `ip:127.0.0.1` | `fluxgate:bucket:{standalone-rules:standalone-10-per-minute:ip:127.0.0.1}:per-minute` |
| PER_USER | X-User-Id | `user:user-123` | `fluxgate:bucket:{...:user:user-123}:per-minute` |
| PER_API_KEY | X-API-Key | `key:api-key-abc` | `fluxgate:bucket:{...:key:api-key-abc}:per-minute` |
| CUSTOM | X-User-Id (via `ipUser`) | `custom:127.0.0.1:user-123` | `fluxgate:bucket:{...:custom:127.0.0.1:user-123}:per-minute` |

```bash
# List the buckets (SCAN, not KEYS, which blocks the server)
redis-cli --scan --pattern 'fluxgate:bucket:*'
redis-cli HGETALL 'fluxgate:bucket:{standalone-rules:standalone-10-per-minute:ip:127.0.0.1}:per-minute'
```

## RequestContext Customization

Customize request context before rate limiting to set values used by `LimitScopeKeyResolver`.
The builder arrives pre-filled: identity per `fluxgate.ratelimit.identity.source` (default
`PRINCIPAL`, identity headers ignored) and the client IP per `trust-client-ip-header` /
`trusted-proxies`. Do not copy `X-User-Id`, `X-API-Key` or `X-Real-IP` into the context yourself - a
client could then choose its own bucket. This sample only derives a composite key
(`MultipleFiltersConfig`):

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
  return (builder, request) -> {
    // userId/clientIp are already resolved by the starter according to identity.source
    String clientIp = builder.build().getClientIp();
    String userId = builder.build().getUserId();

    // Composite key (IP:userId) for CUSTOM scope with keyStrategyId="ipUser"
    if (userId != null && !userId.isEmpty()) {
      builder.attribute("ipUser", clientIp + ":" + userId);
    } else {
      builder.attribute("ipUser", clientIp);
    }
    return builder;
  };
}
```

To try per-user keys locally, run with `FLUXGATE_IDENTITY_SOURCE=HEADERS` (**demo only**; mirrors
`identity.source=HEADERS` and requires a gateway that authenticates the caller and strips incoming
identity headers). Behind a reverse proxy also set `FLUXGATE_TRUST_CLIENT_IP_HEADER=true` and
`fluxgate.ratelimit.trusted-proxies` (or `FLUXGATE_TRUSTED_PROXIES`).

### Default LimitScopeKeyResolver

The starter registers a `LimitScopeKeyResolver` as the `KeyResolver` when the application defines
none. With the default `fluxgate.ratelimit.missing-key-behavior: FALLBACK_TO_IP`:

```
GLOBAL      -> "global"
PER_IP      -> "ip:" + clientIp
PER_USER    -> "user:" + userId          ("ip:" + clientIp when there is no user id)
PER_API_KEY -> "key:" + apiKey           ("ip:" + clientIp when there is no API key)
CUSTOM      -> "custom:" + attributes.get(rule.getKeyStrategyId())
```

With `missing-key-behavior: REJECT` a missing user id or API key rejects the request instead.

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

### StandaloneJava21Application.java

```java
@SpringBootApplication
@EnableFluxgateAspect
public class StandaloneJava21Application {
  public static void main(String[] args) {
    SpringApplication.run(StandaloneJava21Application.class, args);
  }
}
```

No `@EnableFluxgateFilter` here: the three servlet filters come from `MultipleFiltersConfig`.

### MultipleFiltersConfig.java

Registers the `RequestContextCustomizer` bean and the three `FluxgateRateLimitFilter`
registrations described under [Architecture](#architecture).

### StandaloneRateLimitHandler.java

Custom `FluxgateRateLimitHandler` used by the filters and the `@RateLimit` aspect. It:
1. Looks up the rule set from MongoDB via `RateLimitRuleSetProvider`
2. Applies rate limiting via Redis using `RedisRateLimiter`
3. Applies `fluxgate.ratelimit.missing-rule-behavior` when the rule set does not exist (`DENY`:
   429; `ALLOW`: allowed)

### FluxgateConfig.java

Redis beans only: `RedisRateLimiterConfig` (from `fluxgate.redis.uri`), `RedisTokenBucketStore` and
`RedisRateLimiter`, and closes the connection on shutdown. MongoDB, the rule repository, the rule set
provider and the key resolver come from the starter's auto-configuration.

### advice/RateLimitExceptionHandler.java

`@RestControllerAdvice` ordered just before the starter's `RateLimitExceededExceptionHandler`. It
renders `RateLimitExceededException` (thrown for `throwOnReject = true`, as on
`/api/test/aop/aop-test2`, or outside a servlet request) as 429, or 503 when rate limiting is
unavailable, with `Retry-After` when the wait is known.

## Observability

### Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Application health; the `fluxgate` component and its details only for authorized users |
| `/actuator/prometheus` | Prometheus metrics endpoint |
| `/actuator/metrics` | Spring Boot metrics |
| `/actuator/info` | Application info |

### Health Check

```bash
curl http://localhost:8085/actuator/health
```

`show-details` and `show-components` are `when_authorized`, and this sample has no Spring Security,
so an anonymous call only returns the overall status:

```json
{ "status": "UP" }
```

With details enabled, the `fluxgate` component lists flat keys such as `rateLimitingEnabled`,
`filterEnabled`, `aspectEnabled`, `failureBehavior`, `missingRuleBehavior`, `trustClientIpHeader`,
`mongo.status`, `rateLimiter.ready`, `redis.status` and `dependencyIssues`; its status is
`DEGRADED` when an enabled dependency is unhealthy.

### Prometheus Metrics

```bash
curl http://localhost:8085/actuator/prometheus | grep fluxgate
```

Key metrics:
- `fluxgate_requests_total{result="allowed"}` - Allowed requests
- `fluxgate_requests_total{result="rejected"}` - Rejected requests (429)
- `fluxgate_requests_duration_seconds` - Request processing time
- `fluxgate_limiter_failures_total` - Limiter failures, tagged with the action taken
- `fluxgate_tokens_remaining` - Remaining tokens per rule set

`fluxgate.requests.total` (an untagged counter that older versions also registered) was removed
because it exported under the same Prometheus name as `fluxgate.requests`. Sum over the `result` tag
instead.

### JSON Log File

`logback-spring.xml` also writes `FluxgateRateLimitFilter` logs as JSON to `log/fluxgate.log` and to
Logstash on `localhost:5044`; see [ELK_LOGGING_NOTICE.md](ELK_LOGGING_NOTICE.md).

## Swagger UI

```
http://localhost:8085/swagger-ui.html
```

## Learn More

- [FluxGate Samples Overview](../README.md)
- [FluxGate Sample: Standalone (Java 11)](../fluxgate-sample-standalone-java11/README.md)
- [FluxGate Core](../../fluxgate-core/README.md)
- [Redis Rate Limiter](../../fluxgate-redis-ratelimiter/README.md)
