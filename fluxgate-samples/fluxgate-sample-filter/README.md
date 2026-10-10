# FluxGate Sample - Filter (Auto Rate Limiting)

This sample demonstrates **automatic rate limiting** with the `@EnableFluxgateFilter` annotation.
The filter asks an HTTP API for every decision: by default the rate limit check API of
[`fluxgate-sample-redis`](../fluxgate-sample-redis) (`POST /api/ratelimit/check` on port 8082).

## Key Features

- **Annotation-based activation** - `@EnableFluxgateFilter` registers `FluxgateRateLimitFilter`
- **HTTP API-based decisions** - `HttpRateLimitHandler` calls `POST /api/ratelimit/check` on
  `fluxgate.api.url`
- **Zero boilerplate** - no rate limiting code in the controllers
- **Standard headers** - the filter writes `X-RateLimit-*` / `RateLimit-*` and `Retry-After`
- **Fail-open handler** - if the API server is unavailable, `HttpRateLimitHandler` allows the
  request (a demo choice; the starter's own default for limiter failures is to deny)

## Handler Modes

| Mode | Handler | Description |
|------|---------|-------------|
| **HTTP API** (this sample) | `HttpRateLimitHandler` | Calls the rate limit check API of `fluxgate-sample-redis` |
| **Redis Direct** | `EngineBackedRateLimitHandler` (library default) | Direct Redis access; the starter registers the handler automatically when no other `FluxgateRateLimitHandler` bean exists |

## Prerequisites

The HTTP API mode needs `fluxgate-sample-redis` (and therefore Redis):

> Run `./mvnw -B install -DskipTests` (JDK 21) once from the project root first, or
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-filter -am` (JDK 17+):
> the samples depend on the `0.4.0-SNAPSHOT` modules (see [Build Once](../README.md#build-once)).

```bash
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis   # port 8082
```

For Redis Direct mode see [Switching to Redis Direct Mode](#switching-to-redis-direct-mode).

## Quick Start

### 1. Start the Rate Limit API Server

Start Redis and `fluxgate-sample-redis` as shown above. It registers the rule set `api-limits`
(10 requests per 60 seconds per client IP) at startup.

### 2. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter
```

The application starts on port **8083**.

### 3. Test Rate Limiting

```bash
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/api/hello
  echo ""
done
```

Expected output (`api-limits`: 10 per 60 s):
```
Request 1: 200
Request 2: 200
...
Request 10: 200
Request 11: 429  # Rate limited!
Request 12: 429
```

The bucket is keyed by rule set and client IP in `fluxgate-sample-redis`, so it is shared with
direct calls to that sample's `/api/hello` from the same IP. If `fluxgate-sample-redis` is not
running, every request is allowed (fail open) and the handler logs an error.

### 4. Check Rate Limit Headers

```bash
curl -i http://localhost:8083/api/hello
```

An allowed response carries the remaining tokens in both header families:
```
X-RateLimit-Remaining: 9
RateLimit-Remaining: 9
```

A rejected one adds `Retry-After` (whole seconds, at least 1) and the starter's problem document:
```
HTTP/1.1 429
X-RateLimit-Remaining: 0
RateLimit-Remaining: 0
Retry-After: 6
Content-Type: application/problem+json;charset=UTF-8

{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded, retry after 6 seconds","retryAfterMillis":5999}
```

`HttpRateLimitHandler` only knows `allowed`, `remaining` and `retryAfterMs` (the response of
`/api/ratelimit/check`), so `*-Limit`, `*-Reset` and `RateLimit-Policy` are not written.

## Project Structure

```
fluxgate-sample-filter/
├── src/main/java/org/fluxgate/sample/filter/
│   ├── FilterSampleApplication.java    # Main app with @EnableFluxgateFilter
│   ├── handler/
│   │   └── HttpRateLimitHandler.java   # HTTP API handler (active)
│   ├── config/
│   │   ├── OpenApiConfig.java          # Swagger / OpenAPI configuration
│   │   └── RuleSetConfig.java          # Redis direct mode (commented out)
│   └── controller/
│       ├── ApiController.java          # Rate-limited API endpoints
│       ├── HealthController.java       # /health and /ready (not rate limited)
│       └── RuleSetAdminController.java # Admin API for Redis mode (commented out)
├── src/main/resources/
│   └── application.yml                 # Configuration
└── src/test/java/org/fluxgate/sample/filter/
    ├── FilterSampleSmokeTest.java      # context starts, /api/users/{id} binds
    ├── FilterSampleStartupTest.java    # shipped application.yml, filter on, stand-in check API
    └── config/OpenApiConfigTest.java
```

This sample keeps a **custom handler on purpose**, to demonstrate the HTTP API pattern. Most
applications no longer need one: with `fluxgate.redis.enabled=true` (or
`fluxgate.ratelimit.mode=IN_MEMORY`) and a `RateLimitRuleSetProvider` bean, the starter registers
`EngineBackedRateLimitHandler` automatically, and the `handler` attribute of `@EnableFluxgateFilter`
can be left out. The earlier commented-out `RedisRateLimitHandler` in this sample was deleted for
that reason.

The two commented-out classes above show the Redis-direct alternative. Note that they store rules
through `RedisRuleSetStore` / `RuleSetData`, which are **deprecated since 0.4.0** — prefer MongoDB
behind a `RateLimitRuleSetProvider`, or a provider bean of your own, as
`fluxgate-sample-standalone-java21` does.

## How It Works

### 1. Enable the Filter

```java
@SpringBootApplication
@EnableFluxgateFilter(
    handler = HttpRateLimitHandler.class,
    ruleSetId = "api-limits",
    includePatterns = {"/api/*"},
    excludePatterns = {"/health", "/actuator/*", "/swagger-ui/*", "/v3/api-docs/*"})
public class FilterSampleApplication {
  public static void main(String[] args) {
    SpringApplication.run(FilterSampleApplication.class, args);
  }
}
```

`/api/*` matches **one** path segment: `/api/hello`, `/api/users` and `/api/stats` are rate
limited, `/api/users/{id}` is not. Use `/api/**` to cover nested paths. No `fluxgate.ratelimit.*`
pattern keys are set, so the annotation's values apply.

### 2. Configure the API URL

```yaml
server:
  port: 8083

spring:
  application:
    name: fluxgate-sample-filter

# FluxGate Configuration - Automatic Rate Limiting via Filter
fluxgate:
  # HTTP API mode (current) - calls external FluxGate API server
  api:
    url: http://localhost:8082

  # Redis direct mode (optional) - the starter then registers its own handler
  # redis:
  #   enabled: true
  #   uri: redis://localhost:6379

# Rate limiting is configured via @EnableFluxgateFilter annotation:
#   handler = HttpRateLimitHandler.class (omit it to use the library default)
#   ruleSetId = "api-limits"
#   includePatterns = {"/api/*"}
#   excludePatterns = {"/health", "/actuator/*", "/swagger-ui/*", "/v3/api-docs/*"}

logging:
  level:
    org.fluxgate: DEBUG
```

### 3. HTTP Rate Limit Handler

```java
@Component
public class HttpRateLimitHandler implements FluxgateRateLimitHandler {

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    // POST {fluxgate.api.url}/api/ratelimit/check with ruleSetId, clientIp, userId, apiKey,
    // endpoint and method; maps {"allowed", "remaining", "retryAfterMs"} to a RateLimitResponse.
    // Any exception (server down, timeout) allows the request.
  }
}
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.api.url` | `http://localhost:8080` in `HttpRateLimitHandler`; `application.yml` sets `http://localhost:8082` | Base URL of the rate limit check API |

## API Endpoints

| Method | Path | Description | Rate Limited |
|--------|------|-------------|:------------:|
| GET | `/api/hello` | Hello endpoint | ✅ |
| GET | `/api/users` | List of three sample users | ✅ |
| GET | `/api/users/{id}` | One user (1-3), 404 otherwise | ❌ (two segments, outside `/api/*`) |
| GET | `/api/stats` | Request counter | ✅ |
| GET | `/health` | Health check | ❌ |
| GET | `/ready` | Readiness check | ❌ |

Swagger UI: `http://localhost:8083/swagger-ui.html` (not rate limited).

## Switching to Redis Direct Mode

To use direct Redis access instead of the HTTP API:

### 1. Uncomment the Redis dependency in `pom.xml`

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. Update `application.yml`

```yaml
fluxgate:
  # Comment out HTTP API config
  # api:
  #   url: http://localhost:8082

  # Enable Redis
  redis:
    enabled: true
    uri: redis://localhost:6379
```

### 3. Remove the HTTP handler

Drop the `handler` attribute in `FilterSampleApplication.java` and delete `HttpRateLimitHandler`
(or its `@Component`). The starter only registers `EngineBackedRateLimitHandler` when no other
`FluxgateRateLimitHandler` bean exists, and without the attribute the filter would otherwise still
pick up `HttpRateLimitHandler`.

```java
@EnableFluxgateFilter(
    // No handler attribute: the starter registers EngineBackedRateLimitHandler
    ruleSetId = "api-limits",
    // ...
)
```

### 4. Uncomment the Redis-related classes

- `RuleSetConfig.java` (`RateLimitRuleSetProvider` over `RedisRuleSetStore`, seeds `api-limits`)
- `RuleSetAdminController.java` (`/admin/rulesets`)

### 5. Start Redis

```bash
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine
```

## Why Use This Sample?

| Use Case | Recommendation |
|----------|----------------|
| Getting started with FluxGate | Start here |
| Simple rate limiting needs | Good fit |
| Centralized rate limit decisions | Use HTTP API mode |
| Low-latency rate limiting | Use Redis direct mode |
| Complex rule hierarchies | Consider `fluxgate-sample-mongo` |

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-redis](../fluxgate-sample-redis) - The rate limit API this sample calls
- [fluxgate-sample-mongo](../fluxgate-sample-mongo) - For control-plane functionality
- [fluxgate-sample-api](../fluxgate-sample-api) - For full integration
