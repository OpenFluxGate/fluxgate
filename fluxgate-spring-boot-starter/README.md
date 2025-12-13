# FluxGate Spring Boot Starter

Spring Boot auto-configuration for FluxGate distributed rate limiting.

## Overview

This starter provides automatic configuration for FluxGate components in Spring Boot applications. It supports **role-separated deployments**, allowing you to run different components in different microservices.

### Deployment Patterns

| Pattern | Mongo | Redis | Filter | Use Case |
|---------|-------|-------|--------|----------|
| **Pod A** (Control-plane) | Yes | No | No | Rule management API |
| **Pod B** (Data-plane) | No | Yes | Yes | Rate limiting proxy |
| **Pod C** (Full Gateway) | Yes | Yes | Yes | All-in-one gateway |

## Installation

Add the starter to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot-starter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>
```

### Optional Dependencies

The starter has optional dependencies. Include only what you need:

```xml
<!-- For MongoDB rule storage -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>

<!-- For Redis rate limiting -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>${fluxgate.version}</version>
</dependency>
```

## Configuration

### Full Configuration Reference

```yaml
fluxgate:
  # MongoDB Configuration (Control-plane)
  mongo:
    enabled: false                    # Enable MongoDB integration
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events

  # Redis Configuration (Data-plane)
  redis:
    enabled: false                    # Enable Redis integration
    uri: redis://localhost:6379

  # Rate Limiting Configuration
  ratelimit:
    enabled: true                     # Master switch for rate limiting
    filter-enabled: false             # Enable HTTP filter
    default-rule-set-id: null         # Default rule set ID
    filter-order: -2147483548         # Filter priority (lower = higher)
    include-patterns:                 # URL patterns to rate limit
      - /*
    exclude-patterns: []              # URL patterns to exclude
    client-ip-header: X-Forwarded-For # Header for client IP
    trust-client-ip-header: true      # Trust the IP header
    include-headers: true             # Add rate limit headers to response
```

## Deployment Examples

### Pod A: Control-Plane (Mongo-only)

Use this configuration for a service that manages rate limit rules but doesn't enforce them.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://user:pass@mongo.internal:27017/fluxgate?authSource=admin
    database: fluxgate
  redis:
    enabled: false
  ratelimit:
    filter-enabled: false
```

**Beans created:**
- `MongoClient`
- `MongoDatabase`
- `MongoRateLimitRuleRepository`
- `MongoRuleSetProvider`

**Use case:** Admin API for CRUD operations on rate limit rules.

```java
@RestController
@RequestMapping("/admin/rules")
public class RuleAdminController {

    @Autowired
    private MongoRateLimitRuleRepository ruleRepository;

    @PostMapping
    public void createRule(@RequestBody RateLimitRuleDocument rule) {
        ruleRepository.upsert(rule);
    }
}
```

---

### Pod B: Data-Plane (Redis + Filter)

Use this configuration for a rate limiting proxy that enforces limits but doesn't manage rules.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: false
  redis:
    enabled: true
    uri: redis://redis.internal:6379
  ratelimit:
    enabled: true
    filter-enabled: true
    default-rule-set-id: api-gateway-rules
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
```

**Beans created:**
- `RedisRateLimiterConfig`
- `RedisTokenBucketStore`
- `RedisRateLimiter`
- `FluxgateRateLimitFilter`

**Important:** Without Mongo enabled, you need to provide a custom `RateLimitRuleSetProvider`:

```java
@Configuration
public class RuleSetConfig {

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider() {
        // Load rules from config server, cache, or other source
        return ruleSetId -> {
            // Custom implementation
            return Optional.of(loadRulesFromConfigServer(ruleSetId));
        };
    }
}
```

Or use an external rule cache that's populated by the control-plane.

---

### Pod C: Full Gateway (Mongo + Redis + Filter)

Use this configuration for an all-in-one API gateway.

```yaml
# application.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://user:pass@mongo.internal:27017/fluxgate?authSource=admin
    database: fluxgate
  redis:
    enabled: true
    uri: redis://redis.internal:6379
  ratelimit:
    enabled: true
    filter-enabled: true
    default-rule-set-id: default-limits
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /metrics
      - /actuator/*
    include-headers: true
```

**Beans created:**
- All MongoDB beans
- All Redis beans
- Filter and registration

**Automatic behavior:**
1. Rules loaded from MongoDB via `MongoRuleSetProvider`
2. Rate limiting enforced by `RedisRateLimiter`
3. Filter applies limits to matching requests
4. Standard rate limit headers added to responses

---

## Auto-Configuration Classes

### FluxgateMongoAutoConfiguration

**Condition:** `fluxgate.mongo.enabled=true`

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateMongoClient` | `MongoClient` | MongoDB connection |
| `fluxgateMongoDatabase` | `MongoDatabase` | FluxGate database |
| `fluxgateRuleCollection` | `MongoCollection<Document>` | Rules collection |
| `mongoRateLimitRuleRepository` | `MongoRateLimitRuleRepository` | Rule CRUD |
| `fluxgateKeyResolver` | `KeyResolver` | Default: client IP |
| `mongoRuleSetProvider` | `RateLimitRuleSetProvider` | Rule set loading |

### FluxgateRedisAutoConfiguration

**Condition:** `fluxgate.redis.enabled=true`

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRedisConfig` | `RedisRateLimiterConfig` | Redis connection + Lua scripts |
| `fluxgateTokenBucketStore` | `RedisTokenBucketStore` | Token bucket operations |
| `redisRateLimiter` | `RateLimiter` | Rate limiter implementation |

### FluxgateFilterAutoConfiguration

**Conditions:**
- `fluxgate.ratelimit.filter-enabled=true`
- `RateLimiter` bean exists
- Web application context

Creates:
| Bean | Type | Description |
|------|------|-------------|
| `fluxgateRateLimitFilter` | `FluxgateRateLimitFilter` | HTTP filter |
| `fluxgateRateLimitFilterRegistration` | `FilterRegistrationBean` | Servlet registration |

---

## HTTP Filter Behavior

### Request Flow

```
Request → Filter → Check Exclusions → Load RuleSet → Rate Limit → Response
                         ↓                              ↓
                    Skip if excluded              429 if exceeded
```

### Rate Limit Headers

When `fluxgate.ratelimit.include-headers=true`:

**Successful Response (2xx):**
```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 42
X-RateLimit-Reset: 1701388800
```

**Rate Limited Response (429):**
```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800
Retry-After: 45
Content-Type: application/json

{"error":"Rate limit exceeded","retryAfter":45}
```

### Client IP Extraction

The filter extracts client IP in this order:
1. If `trust-client-ip-header=true`: Use `X-Forwarded-For` header (first IP)
2. Otherwise: Use `request.getRemoteAddr()`

Configure for your proxy setup:
```yaml
fluxgate:
  ratelimit:
    client-ip-header: X-Real-IP        # Your proxy's IP header
    trust-client-ip-header: true       # Set false if not behind proxy
```

---

## Custom KeyResolver

Override the default IP-based key resolver:

```java
@Configuration
public class FluxgateConfig {

    @Bean
    public KeyResolver fluxgateKeyResolver() {
        return context -> {
            // Rate limit by API key instead of IP
            String apiKey = context.getApiKey();
            if (apiKey != null) {
                return new RateLimitKey(apiKey);
            }
            // Fallback to IP
            return new RateLimitKey(context.getClientIp());
        };
    }
}
```

---

## Custom RuleSetProvider

For data-plane pods without Mongo, provide rules via custom provider:

```java
@Configuration
public class RuleSetConfig {

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider() {
        // Example: Load from environment/config
        return ruleSetId -> {
            RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
                .label("100-per-minute")
                .build();

            RateLimitRule rule = RateLimitRule.builder("default-rule")
                .name("Default Rate Limit")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .keyStrategyId("clientIp")
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(band)
                .ruleSetId(ruleSetId)
                .build();

            KeyResolver keyResolver = ctx -> new RateLimitKey(ctx.getClientIp());

            RateLimitRuleSet ruleSet = RateLimitRuleSet.builder(ruleSetId)
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .build();

            return Optional.of(ruleSet);
        };
    }
}
```

---

## Conditional Bean Examples

### Disable Filter Programmatically

```java
@Configuration
@ConditionalOnProperty(name = "app.rate-limiting.enabled", havingValue = "true")
public class ConditionalRateLimitConfig {
    // Only loaded when app.rate-limiting.enabled=true
}
```

### Environment-Specific Configuration

```yaml
# application-dev.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate-dev
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    filter-enabled: false  # Disable filter in dev

---
# application-prod.yml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://mongo-cluster.prod:27017/fluxgate?replicaSet=rs0
  redis:
    enabled: true
    uri: redis://redis-cluster.prod:6379
  ratelimit:
    filter-enabled: true
    default-rule-set-id: production-limits
```

---

## Observability

FluxGate provides comprehensive observability features for production monitoring.

### Structured JSON Logging

FluxGate outputs structured JSON logs with correlation IDs for easy integration with ELK Stack, Splunk, or other log aggregation systems.

**Enable structured logging** by including the FluxGate logback configuration in your `logback-spring.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/fluxgate/spring/logback-spring.xml"/>
</configuration>
```

**Example JSON log output:**

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "level": "INFO",
  "logger": "org.fluxgate.spring.filter.FluxgateRateLimitFilter",
  "message": "Request completed",
  "fluxgate.rule_set": "api-limits",
  "fluxgate.rule_id": "rate-limit-rule-1",
  "fluxgate.allowed": true,
  "fluxgate.remaining_tokens": 9,
  "fluxgate.client_ip": "192.168.1.100",
  "correlation_id": "abc123-def456"
}
```

**Log fields:**

| Field | Description |
|-------|-------------|
| `fluxgate.rule_set` | Rule set ID applied |
| `fluxgate.rule_id` | Specific rule ID matched |
| `fluxgate.allowed` | Whether request was allowed |
| `fluxgate.remaining_tokens` | Tokens remaining after request |
| `fluxgate.client_ip` | Client IP address |
| `correlation_id` | Request correlation ID for tracing |

---

### Prometheus Metrics

FluxGate automatically exposes Micrometer-based metrics when `spring-boot-starter-actuator` is on the classpath.

**Dependencies:**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

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

**Available Metrics:**

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `fluxgate_requests_total` | Counter | endpoint, method, rule_set | Total rate limit requests |
| `fluxgate_tokens_remaining` | Gauge | endpoint, rule_set | Remaining tokens in bucket |

**Example Prometheus output:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/users",method="GET",rule_set="api-limits"} 142.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/users",rule_set="api-limits"} 8.0
```

**Disable metrics:**

```yaml
fluxgate:
  metrics:
    enabled: false
```

---

### Health Checks

FluxGate provides detailed health indicators for Redis and MongoDB connections via Spring Boot Actuator.

**Configuration:**

```yaml
management:
  endpoint:
    health:
      show-details: always      # Show detailed health info
      show-components: always   # Show component breakdown
```

**Access:** `GET /actuator/health`

**Example Response:**

```json
{
  "status": "UP",
  "components": {
    "fluxgate": {
      "status": "UP",
      "details": {
        "rateLimitingEnabled": true,
        "filterEnabled": true,
        "mongo.status": "UP",
        "mongo.message": "MongoDB is healthy",
        "mongo.database": "fluxgate",
        "mongo.latency_ms": 2,
        "mongo.version": "7.0.0",
        "mongo.connections.current": 10,
        "mongo.connections.available": 100,
        "redis.status": "UP",
        "redis.message": "Redis cluster is healthy",
        "redis.mode": "CLUSTER",
        "redis.latency_ms": 1,
        "redis.cluster_state": "ok",
        "redis.cluster_nodes": 6,
        "redis.cluster_masters": 3,
        "redis.cluster_replicas": 3
      }
    }
  }
}
```

**Health Check Details:**

| Component | Details Provided |
|-----------|------------------|
| **MongoDB** | database, version, latency_ms, connections (current/available), replicaSet info |
| **Redis** | mode (STANDALONE/CLUSTER), latency_ms, cluster_state, cluster_nodes, masters/replicas |

**Health Status Values:**

| Status | Description |
|--------|-------------|
| `UP` | Component is healthy |
| `DOWN` | Component has failed |
| `DEGRADED` | Some components have issues |
| `DISABLED` | Component is not enabled |

---

## Troubleshooting

### No RateLimiter Bean

**Error:** Filter auto-config skipped because no RateLimiter bean

**Solution:** Enable Redis:
```yaml
fluxgate:
  redis:
    enabled: true
```

### No RuleSetProvider

**Warning:** `No RateLimitRuleSetProvider bean found`

**Solutions:**
1. Enable Mongo: `fluxgate.mongo.enabled=true`
2. Provide custom `RateLimitRuleSetProvider` bean

### Filter Not Applied

**Checklist:**
1. `fluxgate.ratelimit.filter-enabled=true`
2. `RateLimiter` bean exists (Redis enabled)
3. Request path matches `include-patterns`
4. Request path not in `exclude-patterns`
5. `default-rule-set-id` is configured

### Debugging

Enable debug logging:
```yaml
logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.spring: DEBUG
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Application                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateFilterAutoConfiguration                     │   │
│  │  @ConditionalOnProperty("fluxgate.ratelimit.filter-enabled")     │   │
│  │  @ConditionalOnBean(RateLimiter.class)                           │   │
│  │                                                                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │ FluxgateRateLimitFilter                                     │ │   │
│  │  │   - Extracts client IP                                      │ │   │
│  │  │   - Loads RuleSet from Provider                             │ │   │
│  │  │   - Calls RateLimiter.tryConsume()                          │ │   │
│  │  │   - Returns 429 or passes through                           │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                              │                                           │
│                              │ depends on                                │
│                              ▼                                           │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateRedisAutoConfiguration                      │   │
│  │  @ConditionalOnProperty("fluxgate.redis.enabled")                │   │
│  │                                                                   │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │ RedisConfig     │  │ TokenBucketStore│  │ RedisRateLimiter│  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               FluxgateMongoAutoConfiguration                      │   │
│  │  @ConditionalOnProperty("fluxgate.mongo.enabled")                │   │
│  │                                                                   │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │ MongoClient     │  │ RuleRepository  │  │ RuleSetProvider │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Related Modules

- [`fluxgate-core`](../fluxgate-core) - Core interfaces
- [`fluxgate-mongo-adapter`](../fluxgate-mongo-adapter) - MongoDB integration
- [`fluxgate-redis-ratelimiter`](../fluxgate-redis-ratelimiter) - Redis rate limiter

## License

MIT License
