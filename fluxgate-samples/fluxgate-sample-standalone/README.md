# FluxGate Sample: Standalone

A complete standalone application demonstrating FluxGate with direct MongoDB and Redis integration.

## Overview

This sample showcases:
- Direct MongoDB connection for rule storage
- Direct Redis connection for rate limiting
- **Multiple rate limit filters with different rule sets**
- **RequestContext customization** for IP override and attribute injection
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
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-standalone
```

The application starts on port **8085**.

## API Endpoints

### Rule Management APIs

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/admin/rules/standalone` | Create rule for /api/test (10 req/min) |
| POST | `/api/admin/rules/multi-filter` | Create 2 rules for /api/test/multi-filter |
| POST | `/api/admin/rules/all` | Create all rules at once |
| GET | `/api/admin/rules/{ruleSetId}` | Get rules by rule set ID |

### Test Endpoints

| Method | Endpoint | RuleSet | Limits |
|--------|----------|---------|--------|
| GET | `/api/test` | standalone-rules | 10 req/min (1 rule) |
| GET | `/api/test/multi-filter` | multi-filter-rules | 10 req/min + 20 req/min (2 rules) |
| GET | `/api/test/info` | - | Shows configuration |

## Quick Start

### Step 1: Create All Rules

```bash
curl -X POST http://localhost:8085/api/admin/rules/all | jq
```

Response:
```json
{
  "success": true,
  "ruleSets": {
    "standalone-rules": {
      "endpoint": "/api/test",
      "rules": 1,
      "limit": "10 req/min"
    },
    "multi-filter-rules": {
      "endpoint": "/api/test/multi-filter",
      "rules": 2,
      "limits": "10 req/min + 20 req/min"
    }
  },
  "totalRules": 3
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

### Step 4: Check Configuration

```bash
curl http://localhost:8085/api/test/info | jq
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                 Servlet Filter Chain                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Order=1  multiFilterApiFilter                                  │
│           └─ /api/test/multi-filter/** → "multi-filter-rules"   │
│              (2 rules: 10 req/min + 20 req/min)                 │
│                          │                                      │
│  Order=2  apiFilter      ▼                                      │
│           └─ /api/test/** → "standalone-rules"                  │
│              (1 rule: 10 req/min)                               │
│              ⚠️  excludes: /api/test/multi-filter/**            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Important: Excluding Overlapping URL Patterns

When using multiple filters with overlapping URL patterns, you **MUST** exclude the more specific pattern from the broader filter to prevent double rate limiting.

```java
// apiFilter covers /api/test/** but MUST exclude /api/test/multi-filter/**
new String[] {"/api/test/**"},              // includePatterns
new String[] {"/api/test/multi-filter/**"}, // excludePatterns - REQUIRED!
```

**Without exclusion**, a request to `/api/test/multi-filter` would be rate-limited by BOTH filters:

1. `multiFilterApiFilter` (order=1) → applies `multi-filter-rules` (2 rules)
2. `apiFilter` (order=2) → applies `standalone-rules` (1 rule) - **UNINTENDED!**

This would result in **3 rules** being applied instead of the intended **2 rules**.

## Rule Configuration

| Rule Set | Rule ID | Limit | Endpoint |
|----------|---------|-------|----------|
| standalone-rules | standalone-10-per-minute | 10 req/min | /api/test |
| multi-filter-rules | multi-filter-10-per-minute | 10 req/min | /api/test/multi-filter |
| multi-filter-rules | multi-filter-20-per-minute | 20 req/min | /api/test/multi-filter |

## RequestContext Customization

Customize request context before rate limiting:

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        // Override client IP from proxy header
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null) {
            builder.clientIp(realIp);
        }

        // Add custom attributes
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));

        // Remove sensitive headers before logging
        builder.getHeaders().remove("Authorization");

        return builder;
    };
}
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
    event-collection: rate_limit_events
    ddl-auto: create
  redis:
    uri: redis://localhost:6379
```

## Swagger UI

```
http://localhost:8085/swagger-ui.html
```

## Learn More

- [FluxGate Samples Overview](../README.md)
- [FluxGate Core](../../fluxgate-core/README.md)
- [Redis Rate Limiter](../../fluxgate-redis-ratelimiter/README.md)
