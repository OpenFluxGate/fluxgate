# FluxGate End-to-End Integration Test Guide

## Overview

The `MongoRedisRateLimitIntegrationTest` demonstrates the complete FluxGate architecture by combining:
- **MongoDB** for rule storage (via `fluxgate-mongo-adapter`)
- **Redis** for distributed rate limiting enforcement (via `fluxgate-redis-ratelimiter`)

## Test Flow

```
┌─────────────────────────────────────────────────────────┐
│  1. Store Rule in MongoDB                               │
│     (PER_IP, 100 requests/minute)                       │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│  2. Load Rule using MongoRuleSetProvider                │
│     (Reads from MongoDB → RateLimitRuleSet)             │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│  3. Create RedisRateLimiter                             │
│     (Connects to Redis with Lettuce client)             │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│  4. Make 100 Requests                                   │
│     tryConsume(context, ruleSet, 1) × 100               │
│     → All ALLOWED ✓                                     │
│     → Redis stores token bucket state                   │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│  5. Make 101st Request                                  │
│     tryConsume(context, ruleSet, 1)                     │
│     → REJECTED ✗                                        │
│     → Rate limit enforced                               │
└─────────────────────────────────────────────────────────┘
```

## File Structure

```
fluxgate-testkit/
└── src/test/java/org/fluxgate/testkit/integration/
    └── MongoRedisRateLimitIntegrationTest.java
```

## Prerequisites

### 1. MongoDB Running
```bash
docker run -d \
  --name fluxgate-mongo \
  -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:7.0
```

### 2. Redis Running
```bash
docker run -d \
  --name fluxgate-redis \
  -p 6379:6379 \
  redis:7-alpine
```

## Running the Tests

### Option 1: Run All Integration Tests
```bash
./mvnw test -pl fluxgate-testkit
```

### Option 2: Run Specific Test Class
```bash
./mvnw test -pl fluxgate-testkit \
  -Dtest=MongoRedisRateLimitIntegrationTest
```

### Option 3: Run Single Test Method
```bash
./mvnw test -pl fluxgate-testkit \
  -Dtest=MongoRedisRateLimitIntegrationTest#shouldEnforceRateLimitFromMongoRuleStoredInRedis
```

### Option 4: With Custom URIs
```bash
./mvnw test -pl fluxgate-testkit \
  -Dfluxgate.mongo.uri=mongodb://localhost:27017 \
  -Dfluxgate.redis.uri=redis://localhost:6379
```

## Test Methods

### 1. `shouldEnforceRateLimitFromMongoRuleStoredInRedis()`
**Primary end-to-end test**
- Stores PER_IP rule (100 req/min) in MongoDB
- Loads rule via MongoRuleSetProvider
- Makes 100 requests → all ALLOWED
- Makes 101st request → REJECTED
- Verifies matched rule and wait time

**Expected Output:**
```
=== Test: MongoDB Rule Storage → Redis Enforcement ===

STEP 1: Storing rule in MongoDB
  Rule: PER_IP, 100 requests per minute
  ✓ Rule stored in MongoDB with ID: per-ip-100-per-minute

STEP 2: Loading rule from MongoDB
  ✓ RuleSet loaded: e2e-test-ruleset
  ✓ Rules count: 1

STEP 3: Creating RequestContext
  Client IP: 203.0.113.10
  ✓ RequestContext created

STEP 4: Making 100 requests (should all be allowed)
  Request #1: ALLOWED (remaining: 99 tokens)
  Request #20: ALLOWED (remaining: 80 tokens)
  Request #40: ALLOWED (remaining: 60 tokens)
  Request #60: ALLOWED (remaining: 40 tokens)
  Request #80: ALLOWED (remaining: 20 tokens)
  Request #100: ALLOWED (remaining: 0 tokens)
  ✓ All 100 requests were allowed

STEP 5: Making 101st request (should be rejected)
  Request #101: REJECTED (wait: 600 ms)
  ✓ Rate limit correctly enforced

STEP 6: Verifying rejection details
  ✓ Matched rule: E2E Test: 100 requests per minute per IP
  ✓ Wait time: 600 ms

=== Test PASSED ===
```

### 2. `shouldIsolateRateLimitsByIp()`
**IP isolation test**
- Verifies different IPs have independent rate limits
- IP1 exhausts limit → REJECTED
- IP2 still has full limit → ALLOWED

### 3. `shouldCreateCorrectRedisKeysWithTTL()`
**Redis key structure verification**
- Checks Redis key format: `fluxgate:{ruleSetId}:{ruleId}:{ip}:{bandLabel}`
- Verifies TTL is set correctly
- Inspects hash fields: `tokens`, `last_refill_nanos`

## Verifying Redis State

While tests are running, you can inspect Redis:

```bash
# List all FluxGate keys
docker exec fluxgate-redis redis-cli keys "fluxgate:*"

# Output:
# fluxgate:e2e-test-ruleset:per-ip-100-per-minute:203.0.113.10:100-per-minute

# Inspect key contents
docker exec fluxgate-redis redis-cli hgetall \
  "fluxgate:e2e-test-ruleset:per-ip-100-per-minute:203.0.113.10:100-per-minute"

# Output:
# tokens
# 0
# last_refill_nanos
# 1701234567890123456

# Check TTL
docker exec fluxgate-redis redis-cli ttl \
  "fluxgate:e2e-test-ruleset:per-ip-100-per-minute:203.0.113.10:100-per-minute"

# Output: 42 (seconds remaining until expiration)
```

## Verifying MongoDB State

```bash
# Connect to MongoDB
docker exec -it fluxgate-mongo mongosh \
  -u fluxgate -p fluxgate123 --authenticationDatabase admin

# Switch to fluxgate database
use fluxgate

# View stored rules
db.rate_limit_rules.find().pretty()

# Output:
# {
#   "_id": ObjectId("..."),
#   "id": "per-ip-100-per-minute",
#   "name": "E2E Test: 100 requests per minute per IP",
#   "enabled": true,
#   "scope": "PER_IP",
#   "keyStrategyId": "clientIp",
#   "onLimitExceedPolicy": "REJECT_REQUEST",
#   "bands": [
#     {
#       "windowSeconds": 60,
#       "capacity": 100,
#       "label": "100-per-minute"
#     }
#   ],
#   "ruleSetId": "e2e-test-ruleset"
# }
```

## Maven Dependencies Added

The following dependency was added to `fluxgate-testkit/pom.xml`:

```xml
<!-- redis-ratelimiter -->
<dependency>
    <groupId>org.fluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${project.version}</version>
</dependency>
```

This allows the testkit to use `RedisRateLimiter` and `RedisRateLimiterConfig`.

## Key Concepts Demonstrated

### 1. Rule Storage Layer (MongoDB)
- Rules are persisted in MongoDB
- `MongoRateLimitRuleRepository` handles CRUD operations
- `RateLimitRule` → `RateLimitRuleDocument` conversion

### 2. Rule Loading Layer (Provider Pattern)
- `MongoRuleSetProvider` implements `RateLimitRuleSetProvider`
- Loads rules from MongoDB into `RateLimitRuleSet`
- Attaches `KeyResolver` for request context → key mapping

### 3. Enforcement Layer (Redis)
- `RedisRateLimiter` implements `RateLimiter` interface
- Uses Redis for distributed token bucket storage
- Lua scripts ensure atomic refill + consume operations

### 4. Request Flow
```java
// 1. Create context
RequestContext context = RequestContext.builder()
    .clientIp("203.0.113.10")
    .endpoint("/api/test")
    .method("GET")
    .build();

// 2. Try consume
RateLimitResult result = redisRateLimiter.tryConsume(context, ruleSet, 1);

// 3. Check result
if (result.isAllowed()) {
    // Allow request
    long remaining = result.getRemainingTokens();
} else {
    // Reject request
    long waitNanos = result.getNanosToWaitForRefill();
}
```

## Troubleshooting

### MongoDB Connection Error
```
Error: Unable to connect to MongoDB
```
**Solution:**
```bash
# Check MongoDB is running
docker ps | grep mongo

# Check logs
docker logs fluxgate-mongo

# Restart if needed
docker restart fluxgate-mongo
```

### Redis Connection Error
```
Error: Unable to connect to Redis
```
**Solution:**
```bash
# Check Redis is running
docker ps | grep redis

# Test connection
docker exec fluxgate-redis redis-cli ping
# Should return: PONG

# Restart if needed
docker restart fluxgate-redis
```

### Test Fails on 101st Request
```
Expected: false but was: true
```
**Possible causes:**
1. Redis was cleared between test runs → token bucket reset
2. Clock skew → refill happened faster than expected
3. Different IP used → check `TEST_IP` constant

**Solution:**
- Tests clean Redis in `@BeforeEach`
- Ensure MongoDB and Redis are on localhost
- Use fixed IP: `203.0.113.10`

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest

    services:
      mongodb:
        image: mongo:7.0
        env:
          MONGO_INITDB_ROOT_USERNAME: fluxgate
          MONGO_INITDB_ROOT_PASSWORD: fluxgate123
        ports:
          - 27017:27017

      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run Integration Tests
        run: ./mvnw test -pl fluxgate-testkit
        env:
          FLUXGATE_MONGO_URI: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
          FLUXGATE_REDIS_URI: redis://localhost:6379
```

## Performance Notes

- **100 requests**: ~50-100ms (depends on Redis latency)
- **MongoDB read**: ~5-10ms (one-time per test)
- **Redis operations**: ~0.5-2ms per request (local)
- **Total test duration**: ~200-300ms

## Next Steps

1. **Add Metrics Verification**: Verify `MongoRateLimitMetricsRecorder` logs events
2. **Multi-Band Tests**: Test rules with multiple bands (e.g., 10/sec AND 100/min)
3. **Refill Tests**: Wait for window to pass, verify tokens refill
4. **Concurrent Tests**: Simulate multiple threads/instances

## References

- Test Class: `org.fluxgate.testkit.integration.MongoRedisRateLimitIntegrationTest`
- MongoDB Adapter: `fluxgate-mongo-adapter`
- Redis Limiter: `fluxgate-redis-ratelimiter`
- Core Abstractions: `fluxgate-core`
