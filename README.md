# FluxGate

[![Java](https://img.shields.io/badge/Java-21%2B-blue.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/OpenFluxGate/fluxgate/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/OpenFluxGate/fluxgate/actions)
[![Admin UI](https://img.shields.io/badge/Admin%20UI-FluxGate%20Studio-orange.svg)](https://github.com/OpenFluxGate/fluxgate-studio)

English | [한국어](README.ko.md)

**FluxGate** is a production-ready, distributed rate limiting framework for Java applications. Built on top of [Bucket4j](https://github.com/bucket4j/bucket4j), it provides enterprise-grade features including Redis-backed distributed rate limiting, MongoDB rule management, and seamless Spring Boot integration.

## Key Features

- **Distributed Rate Limiting** - Redis-backed token bucket algorithm with atomic Lua scripts
- **Multi-Band Support** - Multiple rate limit tiers (e.g., 100/sec + 1000/min + 10000/hour)
- **Dynamic Rule Management** - Store and update rules in MongoDB without restart
- **Spring Boot Auto-Configuration** - Zero-config setup with sensible defaults
- **Flexible Key Strategies** - Rate limit by IP, User ID, API Key, or custom keys
- **Production-Safe Design** - Uses Redis server time (no clock drift), integer arithmetic only
- **HTTP API Mode** - Centralized rate limiting service via REST API
- **Pluggable Architecture** - Easy to extend with custom handlers and stores
- **Structured Logging** - JSON logging with correlation IDs for ELK/Splunk integration
- **Prometheus Metrics** - Built-in Micrometer integration for monitoring and alerting

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         FluxGate Architecture                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐  │
│  │   Client     │───▶│ Spring Boot  │───▶│   FluxGate Filter        │  │
│  │  Application │    │  Application │    │  (Auto Rate Limiting)    │  │
│  └──────────────┘    └──────────────┘    └───────────┬──────────────┘  │
│                                                      │                  │
│                      ┌───────────────────────────────┼───────────────┐  │
│                      │                               ▼               │  │
│                      │  ┌─────────────────────────────────────────┐  │  │
│                      │  │            RateLimitHandler             │  │  │
│                      │  │  ┌─────────────┐  ┌──────────────────┐  │  │  │
│                      │  │  │   Direct    │  │    HTTP API      │  │  │  │
│                      │  │  │   Redis     │  │    (REST Call)   │  │  │  │
│                      │  │  └──────┬──────┘  └────────┬─────────┘  │  │  │
│                      │  └─────────┼──────────────────┼────────────┘  │  │
│                      │            │                  │               │  │
│                      └────────────┼──────────────────┼───────────────┘  │
│                                   │                  │                  │
│                                   ▼                  ▼                  │
│  ┌────────────────────────────────────┐    ┌────────────────────────┐   │
│  │             Redis                  │    │  Rate Limit Service    │  │
│  │  ┌──────────────────────────────┐  │    │  (fluxgate-sample-     │  │
│  │  │   Token Bucket State         │  │    │   redis on port 8082)  │  │
│  │  │   (Lua Script - Atomic)      │  │◀───│                        │  │
│  │  └──────────────────────────────┘  │    └────────────────────────┘  │
│  └────────────────────────────────────┘                                 │
│                                                                         │
│  ┌────────────────────────────────────┐                                 │
│  │           MongoDB                  │                                 │
│  │  ┌──────────────────────────────┐  │                                 │
│  │  │   Rate Limit Rules           │  │                                 │
│  │  │   (Dynamic Configuration)    │  │                                 │
│  │  └──────────────────────────────┘  │                                 │
│  └────────────────────────────────────┘                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Modules

| Module | Description |
|--------|-------------|
| **fluxgate-core** | Core rate limiting engine with Bucket4j integration |
| **fluxgate-redis-ratelimiter** | Redis-backed distributed rate limiter with Lua scripts |
| **fluxgate-mongo-adapter** | MongoDB adapter for dynamic rule management |
| **fluxgate-spring-boot-starter** | Spring Boot auto-configuration and filter support |
| **fluxgate-testkit** | Integration testing utilities |
| **fluxgate-samples** | Sample applications demonstrating various use cases |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- Redis 6.0+ (for distributed rate limiting)
- MongoDB 4.4+ (optional, for rule management)

### 1. Add Dependencies

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- For Redis-backed rate limiting -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>0.2.0</version>
</dependency>

<!-- For MongoDB rule management (optional) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>0.2.0</version>
</dependency>
```

### 2. Configure Application

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    filter-enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
```

### 3. Enable Rate Limiting Filter

```java
@SpringBootApplication
@EnableFluxgateFilter(handler = HttpRateLimitHandler.class)
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 4. Test Rate Limiting

```bash
# Send 12 requests (with 10 req/min limit)
for i in {1..12}; do
  curl -s -o /dev/null -w "Request $i: %{http_code}\n" http://localhost:8080/api/hello
done

# Expected output:
# Request 1-10: 200
# Request 11-12: 429 (Too Many Requests)
```

## Deployment Patterns

### Pattern 1: Direct Redis Access

Best for simple deployments where each application instance connects directly to Redis.

```
┌─────────────┐     ┌─────────────┐
│   App #1    │────▶│             │
├─────────────┤     │    Redis    │
│   App #2    │────▶│             │
├─────────────┤     │             │
│   App #N    │────▶│             │
└─────────────┘     └─────────────┘
```

### Pattern 2: HTTP API Mode (Centralized)

Best for microservices architecture where you want a dedicated rate limiting service.

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────┐
│   App #1    │────▶│                 │     │             │
├─────────────┤     │  Rate Limit     │────▶│    Redis    │
│   App #2    │────▶│  Service (8082) │     │             │
├─────────────┤     │                 │     │             │
│   App #N    │────▶│                 │     │             │
└─────────────┘     └─────────────────┘     └─────────────┘
```

```yaml
# Client application configuration
fluxgate:
  api:
    url: http://rate-limit-service:8082
  ratelimit:
    filter-enabled: true
```

## Sample Applications

| Sample | Port | Description |
|--------|------|-------------|
| **fluxgate-sample-standalone** | 8085 | Full stack with direct MongoDB + Redis integration |
| **fluxgate-sample-redis** | 8082 | Rate limit service with Redis backend |
| **fluxgate-sample-mongo** | 8081 | Rule management with MongoDB |
| **fluxgate-sample-filter** | 8083 | Client app with auto rate limiting filter |
| **fluxgate-sample-api** | 8084 | REST API for rate limit checking |

### Running Samples

```bash
# Start infrastructure
docker-compose up -d redis mongodb

# Start rate limit service
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis

# Start client application (in another terminal)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter

# Test rate limiting
curl http://localhost:8083/api/hello
```

## Configuration Reference

### FluxGate Properties

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.redis.enabled` | `false` | Enable Redis rate limiter |
| `fluxgate.redis.uri` | `redis://localhost:6379` | Redis connection URI |
| `fluxgate.redis.mode` | `auto` | Redis mode: `standalone`, `cluster`, or `auto` (auto-detect) |
| `fluxgate.mongo.enabled` | `false` | Enable MongoDB adapter |
| `fluxgate.mongo.uri` | `mongodb://localhost:27017/fluxgate` | MongoDB connection URI |
| `fluxgate.mongo.database` | `fluxgate` | MongoDB database name |
| `fluxgate.mongo.rule-collection` | `rate_limit_rules` | Collection name for rate limit rules |
| `fluxgate.mongo.event-collection` | - | Collection name for events (optional) |
| `fluxgate.mongo.ddl-auto` | `validate` | DDL mode: `validate` or `create` |
| `fluxgate.ratelimit.filter-enabled` | `false` | Enable rate limit filter |
| `fluxgate.ratelimit.default-rule-set-id` | `default` | Default rule set ID |
| `fluxgate.ratelimit.include-patterns` | `[/api/*]` | URL patterns to rate limit |
| `fluxgate.ratelimit.exclude-patterns` | `[]` | URL patterns to exclude |
| `fluxgate.api.url` | - | External rate limit API URL |
| `fluxgate.metrics.enabled` | `true` | Enable Prometheus/Micrometer metrics |

### MongoDB DDL Auto Mode

The `fluxgate.mongo.ddl-auto` property controls how FluxGate handles MongoDB collections:

| Mode | Description |
|------|-------------|
| `validate` | (Default) Validates that collections exist. Throws an error if missing. |
| `create` | Automatically creates collections if they don't exist. |

**Example configuration:**

```yaml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
    rule-collection: my_rate_limit_rules    # Custom collection name
    event-collection: my_rate_limit_events  # Optional: enable event logging
    ddl-auto: create                        # Auto-create collections
```

### Rate Limit Rule Configuration

```java
RateLimitRule rule = RateLimitRule.builder("api-rule")
    .name("API Rate Limit")
    .enabled(true)
    .scope(LimitScope.PER_IP)
    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
        .label("10-per-second")
        .build())
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100-per-minute")
        .build())
    .build();
```

## Observability

FluxGate provides comprehensive observability features out of the box.

### Structured Logging

FluxGate outputs JSON-formatted logs with correlation IDs for easy integration with log aggregation systems like ELK Stack or Splunk.

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

Enable structured logging by including `logback-spring.xml` in your application:

```xml
<include resource="org/fluxgate/spring/logback-spring.xml"/>
```

### Prometheus Metrics

FluxGate automatically exposes Micrometer-based metrics when `spring-boot-starter-actuator` is on the classpath.

**Available Metrics:**

| Metric | Type | Description |
|--------|------|-------------|
| `fluxgate_requests_total` | Counter | Total rate limit requests by endpoint, method, and rule_set |
| `fluxgate_tokens_remaining` | Gauge | Remaining tokens in the bucket |

**Example Prometheus output:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/test",method="GET",rule_set="api-limits"} 42.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/test",rule_set="api-limits"} 8.0
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

## Building from Source

```bash
# Clone the repository
git clone https://github.com/OpenFluxGate/fluxgate.git
cd fluxgate

# Build all modules
./mvnw clean install

# Run tests
./mvnw test

# Build without tests
./mvnw clean install -DskipTests
```

## Documentation

- [FluxGate Core](fluxgate-core/README.md) - Core rate limiting concepts and API
- [Redis Rate Limiter](fluxgate-redis-ratelimiter/README.md) - Distributed rate limiting with Redis
- [MongoDB Adapter](fluxgate-mongo-adapter/README.md) - Dynamic rule management
- [Spring Boot Starter](fluxgate-spring-boot-starter/README.md) - Auto-configuration guide
- [Extending FluxGate](HOW_TO_EXTEND_RATELIMITER.md) - Custom implementations
- [Contributing Guide](CONTRIBUTING.md) - Contribute Guide

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Related Projects

| Project | Description |
|---------|-------------|
| [FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio) | Web-based admin UI for managing rate limit rules |

## Roadmap

- [ ] Sliding window rate limiting algorithm
- [x] Prometheus metrics integration
- [x] Redis Cluster support
- [x] Structured JSON logging with correlation IDs
- [ ] gRPC API support
- [x] Rate limit quota management UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
- [ ] Circuit breaker integration

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Bucket4j](https://github.com/bucket4j/bucket4j) - The underlying rate limiting library
- [Lettuce](https://lettuce.io/) - Redis client for Java
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework

---

**FluxGate** - Distributed Rate Limiting Made Simple
