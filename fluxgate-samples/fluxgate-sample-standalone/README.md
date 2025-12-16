# FluxGate Sample: Standalone

A complete standalone application demonstrating FluxGate with direct MongoDB and Redis integration.

## Overview

This sample showcases:
- Direct MongoDB connection for rule storage
- Direct Redis connection for rate limiting
- **Multiple rate limit filters with different rule sets**
- **LimitScope-based key resolution** (PER_IP, PER_USER, PER_API_KEY, CUSTOM)
- **Composite key support** (IP + User ID combination)
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
| POST | `/api/admin/rules/standalone` | Create rule for /api/test (10 req/min, PER_IP) |
| POST | `/api/admin/rules/multi-filter` | Create 2 rules for /api/test/multi-filter |
| POST | `/api/admin/rules/composite` | Create rule for /api/test/composite (10 req/min, IP+User) |
| GET | `/api/admin/rules/{ruleSetId}` | Get rules by rule set ID |

### Test Endpoints

| Method | Endpoint | RuleSet | LimitScope | Limits |
|--------|----------|---------|------------|--------|
| GET | `/api/test` | standalone-rules | PER_IP | 10 req/min |
| GET | `/api/test/multi-filter` | multi-filter-rules | PER_IP | 10 + 20 req/min |
| GET | `/api/test/composite` | composite-key-rules | CUSTOM (ipUser) | 10 req/min per IP+User |
| GET | `/api/test/info` | - | - | Shows configuration |

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

### Step 4: Test Composite Key Endpoint (IP + User)

```bash
# Create the composite key rule
curl -X POST http://localhost:8085/api/admin/rules/composite | jq

# User A - gets 10 requests
for i in {1..12}; do
  echo -n "User A Request $i: "
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:8085/api/test/composite?userId=user-A"
  echo ""
done

# User B - also gets 10 requests (separate bucket!)
for i in {1..12}; do
  echo -n "User B Request $i: "
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:8085/api/test/composite?userId=user-B"
  echo ""
done
```

Expected: User A gets 429 after 10 requests, but User B still has 10 requests available because they have separate buckets (different composite keys: `127.0.0.1:user-A` vs `127.0.0.1:user-B`).

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

| Rule Set | Rule ID | LimitScope | Limit | Endpoint |
|----------|---------|------------|-------|----------|
| standalone-rules | standalone-10-per-minute | PER_IP | 10 req/min | /api/test |
| multi-filter-rules | multi-filter-10-per-minute | PER_IP | 10 req/min | /api/test/multi-filter |
| multi-filter-rules | multi-filter-20-per-minute | PER_IP | 20 req/min | /api/test/multi-filter |
| composite-key-rules | composite-10-per-minute | CUSTOM (ipUser) | 10 req/min | /api/test/composite |

## LimitScope-based Key Resolution

The `LimitScopeKeyResolver` resolves rate limit keys based on the rule's `LimitScope`:

| LimitScope | Key Source | Description |
|------------|------------|-------------|
| `GLOBAL` | `"global"` | Single bucket for all requests |
| `PER_IP` | `RequestContext.clientIp` | One bucket per IP address |
| `PER_USER` | `RequestContext.userId` | One bucket per user (from X-User-Id header) |
| `PER_API_KEY` | `RequestContext.apiKey` | One bucket per API key (from X-API-Key header) |
| `CUSTOM` | `attributes.get(keyStrategyId)` | Custom key from RequestContext attributes |

## Composite Key (IP + User)

The composite key endpoint demonstrates rate limiting by IP and User combination using `LimitScope.CUSTOM`:

```java
// Rule configuration
RateLimitRule rule = RateLimitRule.builder("composite-10-per-minute")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")  // Looks up context.attributes.get("ipUser")
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();
```

The `RequestContextCustomizer` builds the composite key:

```java
// Build composite key: "192.168.1.100:user-123"
String clientIp = builder.build().getClientIp();
if (userId != null && !userId.isEmpty()) {
    builder.attribute("ipUser", clientIp + ":" + userId);
} else {
    builder.attribute("ipUser", clientIp);  // Fallback to IP only
}
```

### Redis Key Examples (Composite)

| User ID | Composite Key | Redis Key |
|---------|---------------|-----------|
| (none) | `127.0.0.1` | `fluxgate:composite-key-rules:composite-10-per-minute:127.0.0.1:per-minute` |
| `user-A` | `127.0.0.1:user-A` | `fluxgate:composite-key-rules:composite-10-per-minute:127.0.0.1:user-A:per-minute` |
| `user-B` | `127.0.0.1:user-B` | `fluxgate:composite-key-rules:composite-10-per-minute:127.0.0.1:user-B:per-minute` |

## Rate Limiting by User ID or API Key

This sample demonstrates rate limiting by different keys using **LimitScope**:

### Testing Different LimitScopes

```bash
# 1. PER_IP (default for standalone-rules)
curl http://localhost:8085/api/test

# 2. PER_USER - requires rule with scope=PER_USER
#    The X-User-Id header sets RequestContext.userId
curl -H "X-User-Id: user-123" http://localhost:8085/api/test

# 3. PER_API_KEY - requires rule with scope=PER_API_KEY
#    The X-API-Key header sets RequestContext.apiKey
curl -H "X-API-Key: api-key-abc" http://localhost:8085/api/test

# 4. CUSTOM (composite key) - IP+User combination
curl "http://localhost:8085/api/test/composite?userId=user-123"
```

### Redis Key Format

The Redis key format is: `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}`

The `{keyValue}` is determined by the rule's `LimitScope`:

| LimitScope | Header/Param | Key Value | Redis Key Example |
|------------|--------------|-----------|-------------------|
| PER_IP | (none) | `127.0.0.1` | `fluxgate:standalone-rules:...:127.0.0.1:per-minute` |
| PER_USER | X-User-Id | `user-123` | `fluxgate:...:user-123:per-minute` |
| PER_API_KEY | X-API-Key | `api-key-abc` | `fluxgate:...:api-key-abc:per-minute` |
| CUSTOM | userId param | `127.0.0.1:user-123` | `fluxgate:...:127.0.0.1:user-123:per-minute` |

### Test: Different Users with Composite Key

```bash
# User A - 10 requests allowed (key: 127.0.0.1:user-A)
for i in {1..12}; do
  echo -n "User A Request $i: "
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:8085/api/test/composite?userId=user-A"
  echo ""
done

# User B - also has 10 requests (separate bucket, key: 127.0.0.1:user-B)
for i in {1..12}; do
  echo -n "User B Request $i: "
  curl -s -o /dev/null -w "%{http_code}" "http://localhost:8085/api/test/composite?userId=user-B"
  echo ""
done
```

Expected: User A gets 429 after 10 requests, but User B still has 10 requests available.

## RequestContext Customization

Customize request context before rate limiting to set values used by `LimitScopeKeyResolver`:

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        // Set userId for PER_USER scope
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            builder.userId(userId);
        }

        // Set apiKey for PER_API_KEY scope
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }

        // Override client IP from proxy header (for PER_IP scope)
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null) {
            builder.clientIp(realIp);
        }

        // Build composite key for CUSTOM scope with keyStrategyId="ipUser"
        String clientIp = builder.build().getClientIp();
        if (userId != null && !userId.isEmpty()) {
            builder.attribute("ipUser", clientIp + ":" + userId);
        } else {
            builder.attribute("ipUser", clientIp);
        }

        return builder;
    };
}
```

### Default LimitScopeKeyResolver

The default `LimitScopeKeyResolver` resolves keys based on the rule's `LimitScope`:

```java
// The default KeyResolver is automatically configured:
@Bean
public KeyResolver keyResolver() {
    return new LimitScopeKeyResolver();
}

// LimitScopeKeyResolver logic:
// - GLOBAL → "global"
// - PER_IP → context.getClientIp()
// - PER_USER → context.getUserId() (fallback to clientIp)
// - PER_API_KEY → context.getApiKey() (fallback to clientIp)
// - CUSTOM → context.getAttributes().get(rule.getKeyStrategyId())
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
