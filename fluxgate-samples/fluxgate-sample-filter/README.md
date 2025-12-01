# FluxGate Sample - Filter (Auto Rate Limiting)

This sample demonstrates **automatic rate limiting** using the `@EnableFluxgateFilter` annotation with HTTP API-based rate limiting.

## Key Features

- **Annotation-based activation** - Simply add `@EnableFluxgateFilter` to enable rate limiting
- **HTTP API-based rate limiting** - Calls external FluxGate API server for rate limit decisions
- **Zero boilerplate** - No rate limiting code in your controllers
- **Fail-open design** - If the API server is unavailable, requests are allowed

## Handler Modes

This sample supports two handler modes:

| Mode | Handler | Description |
|------|---------|-------------|
| **HTTP API** (default) | `HttpRateLimitHandler` | Calls external FluxGate API server |
| **Redis Direct** | `RedisRateLimitHandler` | Direct Redis access (requires additional setup) |

## Prerequisites

### HTTP API Mode (Default)

```bash
# Start FluxGate API server (e.g., fluxgate-sample-mongo)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo
```

### Redis Direct Mode (Optional)

See [Switching to Redis Direct Mode](#switching-to-redis-direct-mode) section below.

## Quick Start

### 1. Start FluxGate API Server

```bash
# Start the API server first (provides rate limit API)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo
```

### 2. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter
```

The application starts on port **8083**.

### 3. Test Rate Limiting

```bash
# Send 12 requests (limit depends on API server configuration)
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/api/hello
  echo ""
done
```

Expected output (with default 10 req/min limit):
```
Request 1: 200
Request 2: 200
...
Request 10: 200
Request 11: 429  # Rate limited!
Request 12: 429
```

### 4. Check Rate Limit Headers

```bash
curl -i http://localhost:8083/api/hello
```

Response headers include:
```
X-RateLimit-Remaining: 9
X-RateLimit-Reset-Ms: 1234
```

## Project Structure

```
fluxgate-sample-filter/
├── src/main/java/org/fluxgate/sample/filter/
│   ├── FilterSampleApplication.java    # Main app with @EnableFluxgateFilter
│   ├── handler/
│   │   ├── HttpRateLimitHandler.java   # HTTP API handler (active)
│   │   └── RedisRateLimitHandler.java  # Redis handler (commented)
│   ├── config/
│   │   └── RuleSetConfig.java          # Redis config (commented)
│   └── controller/
│       ├── ApiController.java          # Rate-limited API endpoints
│       └── RuleSetAdminController.java # Admin API (commented)
└── src/main/resources/
    └── application.yml                 # Configuration
```

## How It Works

### 1. Enable the Filter

```java
@SpringBootApplication
@EnableFluxgateFilter(
    handler = HttpRateLimitHandler.class,
    ruleSetId = "api-limits",
    includePatterns = {"/api/*"},
    excludePatterns = {"/health", "/actuator/*", "/swagger-ui/*", "/v3/api-docs/*"}
)
public class FilterSampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(FilterSampleApplication.class, args);
    }
}
```

### 2. Configure API URL

```yaml
# application.yml
fluxgate:
  api:
    url: http://localhost:8080  # FluxGate API server URL
```

### 3. HTTP Rate Limit Handler

The `HttpRateLimitHandler` calls the FluxGate API server:

```java
@Component
public class HttpRateLimitHandler implements FluxgateRateLimitHandler {

    @Override
    public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
        // Calls POST /api/ratelimit/check on the API server
        // Returns allowed/rejected with remaining tokens and retry info
    }
}
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.api.url` | `http://localhost:8080` | FluxGate API server URL |

## API Endpoints

| Method | Path | Description | Rate Limited |
|--------|------|-------------|:------------:|
| GET | `/api/hello` | Hello endpoint | Y |
| GET | `/api/users` | Users endpoint | Y |
| GET | `/health` | Health check | N |

## Switching to Redis Direct Mode

To use direct Redis access instead of HTTP API:

### 1. Uncomment Redis dependency in `pom.xml`

```xml
<dependency>
    <groupId>org.fluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. Update `application.yml`

```yaml
fluxgate:
  # Comment out HTTP API config
  # api:
  #   url: http://localhost:8080

  # Enable Redis
  redis:
    enabled: true
    uri: redis://localhost:6379
```

### 3. Change handler in `FilterSampleApplication.java`

```java
@EnableFluxgateFilter(
    handler = RedisRateLimitHandler.class,  // Change from HttpRateLimitHandler
    ruleSetId = "api-limits",
    // ...
)
```

### 4. Uncomment Redis-related classes

- `RedisRateLimitHandler.java`
- `RuleSetConfig.java`
- `RuleSetAdminController.java`

### 5. Start Redis

```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

## Why Use This Sample?

| Use Case | Recommendation |
|----------|----------------|
| Getting started with FluxGate | Start here |
| Simple rate limiting needs | Perfect fit |
| Centralized rate limit management | Use HTTP API mode |
| Low-latency rate limiting | Use Redis direct mode |
| Complex rule hierarchies | Consider `fluxgate-sample-mongo` |

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-mongo](../fluxgate-sample-mongo) - For control-plane functionality
- [fluxgate-sample-api](../fluxgate-sample-api) - For full integration
