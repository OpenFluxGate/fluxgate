# FluxGate Sample: Standalone

A complete standalone application demonstrating FluxGate with direct MongoDB and Redis integration.

## Overview

This sample showcases:
- Direct MongoDB connection for rule storage (no external control-plane)
- Direct Redis connection for rate limiting (no external data-plane)
- `@EnableFluxgateFilter` for automatic HTTP filter integration
- Runtime rule management via REST API

## Prerequisites

- Java 21+
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
# From the project root directory
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-standalone
```

The application starts on port **8085**.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/admin/ruleset` | Create rate limit ruleset (10 req/min) |
| GET | `/api/admin/ruleset` | Get current ruleset configuration |
| POST | `/api/admin/sync` | Sync rules to Redis |
| GET | `/api/test` | Rate-limited test endpoint |

## Usage Example

### Step 1: Create Rate Limit Rules

```bash
curl -X POST http://localhost:8085/api/admin/ruleset
```

Response:
```json
{
  "success": true,
  "ruleSetId": "standalone-rules",
  "ruleId": "rate-limit-rule-1",
  "limit": "10 requests per minute per IP"
}
```

### Step 2: Test Rate Limiting

```bash
# Send 12 requests
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/api/test
  echo ""
done
```

Expected output:
```
Request 1: 200
Request 2: 200
...
Request 10: 200
Request 11: 429
Request 12: 429
```

### Step 3: Check Current Rules

```bash
curl http://localhost:8085/api/admin/ruleset | jq
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                 Standalone Application (8085)                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              @EnableFluxgateFilter                       │   │
│  │  ┌───────────────────────────────────────────────────┐  │   │
│  │  │         FluxgateRateLimitFilter                   │  │   │
│  │  │                    │                               │  │   │
│  │  │         StandaloneRateLimitHandler                │  │   │
│  │  └───────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│            ┌─────────────┴─────────────┐                       │
│            │                           │                        │
│            ▼                           ▼                        │
│  ┌──────────────────┐       ┌──────────────────┐               │
│  │     MongoDB      │       │      Redis       │               │
│  │  (Rule Storage)  │       │ (Token Bucket)   │               │
│  │                  │       │                  │               │
│  │  rate_limit_rules│       │  Lua Scripts     │               │
│  └──────────────────┘       └──────────────────┘               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Configuration

### application.yml

```yaml
server:
  port: 8085

fluxgate:
  mongodb:
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
  redis:
    uri: redis://localhost:6379
```

### Docker Profile

For Docker Compose environments:

```yaml
spring:
  config:
    activate:
      on-profile: docker

fluxgate:
  mongodb:
    uri: mongodb://fluxgate:fluxgate123@mongodb:27017/fluxgate?authSource=admin
  redis:
    uri: redis://redis:6379
```

## Key Components

### StandaloneApplication.java

```java
@SpringBootApplication
@EnableFluxgateFilter(
    handler = StandaloneRateLimitHandler.class,
    ruleSetId = "standalone-rules",
    includePatterns = {"/api/test", "/api/test/**"},
    excludePatterns = {"/api/admin/**", "/swagger-ui/**", "/v3/api-docs/**"}
)
public class StandaloneApplication {
    public static void main(String[] args) {
        SpringApplication.run(StandaloneApplication.class, args);
    }
}
```

### StandaloneRateLimitHandler.java

Custom handler that:
1. Looks up rules from MongoDB via `RateLimitRuleSetProvider`
2. Applies rate limiting via Redis using `RedisRateLimiter`
3. Returns rate limit response to the filter

### FluxgateConfig.java

Configuration beans for:
- MongoDB client and collection
- Redis connection and token bucket store
- Rule repository and provider
- Key resolver (IP-based)

## Observability

### Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Application health with FluxGate component status |
| `/actuator/prometheus` | Prometheus metrics endpoint |
| `/actuator/metrics` | Spring Boot metrics |

### Health Check

```bash
curl http://localhost:8085/actuator/health | jq
```

Response:
```json
{
  "status": "UP",
  "components": {
    "fluxgate": {
      "status": "UP",
      "details": {
        "mongo": {
          "status": "UP",
          "message": "MongoDB connection is healthy",
          "details": {
            "version": "7.0.5",
            "connections.current": 5,
            "connections.available": 51195,
            "latencyMs": 2
          }
        },
        "redis": {
          "status": "UP",
          "message": "Redis connection is healthy",
          "details": {
            "mode": "standalone",
            "latencyMs": 1
          }
        }
      }
    }
  }
}
```

### Prometheus Metrics

```bash
curl http://localhost:8085/actuator/prometheus | grep fluxgate
```

Key metrics:
- `fluxgate_requests_total` - Total rate limit requests
- `fluxgate_requests_allowed_total` - Allowed requests
- `fluxgate_requests_rejected_total` - Rejected requests (429)
- `fluxgate_request_duration_seconds` - Request processing time

## Swagger UI

Access the API documentation at:
```
http://localhost:8085/swagger-ui.html
```

## Differences from Other Samples

| Feature | Standalone | Filter | Redis | Mongo |
|---------|:----------:|:------:|:-----:|:-----:|
| Direct MongoDB | ✅ | ❌ | ❌ | ✅ |
| Direct Redis | ✅ | ❌ | ✅ | ❌ |
| HTTP Filter | ✅ | ✅ | ✅ | ❌ |
| Rule Management API | ✅ | ❌ | ❌ | ✅ |
| Self-contained | ✅ | ❌ | ❌ | ❌ |

## Learn More

- [FluxGate Samples Overview](../README.md)
- [FluxGate Core](../../fluxgate-core/README.md)
- [Redis Rate Limiter](../../fluxgate-redis-ratelimiter/README.md)
- [MongoDB Adapter](../../fluxgate-mongo-adapter/README.md)
