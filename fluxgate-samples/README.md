# FluxGate Samples

This module contains sample applications demonstrating various FluxGate rate limiting configurations and use cases.

## Overview

FluxGate is a distributed rate limiting framework for Spring Boot applications. These samples showcase different deployment patterns and feature combinations.

## Sample Applications

| Sample | Port | Description | Prerequisites |
|--------|------|-------------|---------------|
| [fluxgate-sample-standalone-java21](./fluxgate-sample-standalone-java21) | 8085 | **Full Stack** - Direct MongoDB + Redis integration, three `FluxgateRateLimitFilter`s registered as `FilterRegistrationBean`s, the `@RateLimit` aspect and a `@RestControllerAdvice` for `RateLimitExceededException` | MongoDB, Redis |
| [fluxgate-sample-standalone-java11](./fluxgate-sample-standalone-java11) | 8085 | The same stack on Java 11 / Spring Boot 2.7 (boot2 starter) | MongoDB, Redis |
| [fluxgate-sample-filter](./fluxgate-sample-filter) | 8083 | **Recommended** - Automatic rate limiting with the `@EnableFluxgateFilter` annotation; decisions come from `fluxgate-sample-redis` over HTTP | `fluxgate-sample-redis` (and its Redis) |
| [fluxgate-sample-redis](./fluxgate-sample-redis) | 8082 | Data-plane: Redis rate limiting through the raw `RateLimiter` API, a rate limit check API and rule set admin API | Redis |
| [fluxgate-sample-mongo](./fluxgate-sample-mongo) | 8081 | Control-plane: rules stored in MongoDB with an admin API; no rate limiting | MongoDB |
| [fluxgate-sample-api](./fluxgate-sample-api) | 8080 | HTTP demo that ties the Mongo and Redis samples together (no FluxGate dependency) | `fluxgate-sample-mongo`, `fluxgate-sample-redis` (and their MongoDB, Redis) |

## Quick Start

### Build Once

The samples depend on the FluxGate modules at the current `0.4.0-SNAPSHOT` version, which a fresh
clone does not have in `~/.m2` yet. Install them once from the project root before the first
`spring-boot:run` (and again after pulling changes to the library modules).

The full build needs **JDK 21**: the Boot 3 starter targets Java 17 and
`fluxgate-sample-standalone-java21` targets Java 21.

```bash
./mvnw -B install -DskipTests
```

Or build one sample and only the modules it depends on:

```bash
./mvnw -B install -DskipTests -pl fluxgate-samples/<sample> -am
```

| Sample | JDK needed to build it this way |
|--------|---------------------------------|
| `fluxgate-sample-standalone-java21` | 21 |
| `fluxgate-sample-filter`, `-redis`, `-mongo`, `-api` | 17+ (Spring Boot 3) |
| `fluxgate-sample-standalone-java11` | 17+: `-am` also builds `fluxgate-control-support` (a test dependency of the Boot 2 starter), which is compiled against Spring Boot 3. The result runs on Java 11 |

### Prerequisites

```bash
# Start Redis
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine

# Start MongoDB (if needed)
docker run -d --name mongodb -p 127.0.0.1:27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:7.0.14
```

Or use the compose files (from the project root), which bind every port to `127.0.0.1` and are
labelled local development only:

```bash
docker compose -f docker/redis-standalone.yml -f docker/mongo.yml up -d
```

### Run a Sample

`fluxgate-sample-filter` asks `fluxgate-sample-redis` for every decision, so start both:

```bash
# From the project root directory, in two terminals
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis    # 8082
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter   # 8083
```

### Test Rate Limiting

```bash
# Send 12 requests (rule set api-limits: 10 per 60 seconds per client IP)
for i in {1..12}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8083/api/hello
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

## Choosing the Right Sample

### For Most Use Cases: `fluxgate-sample-filter`

Start here if you want:
- Simple setup with minimal code
- Automatic rate limiting via HTTP filter, with `X-RateLimit-*` / `RateLimit-*` headers
- Decisions delegated to a central rate limit API (`fluxgate-sample-redis`), or Redis directly
  after the switch its README describes
- Annotation-based configuration (`@EnableFluxgateFilter`)

### For Control-plane: `fluxgate-sample-mongo`

Use this for:
- Centralized rule storage in MongoDB
- An admin API to list, get and delete rules and create sample rules (no create/update endpoint)
- An admin backend; it does not rate limit requests itself

### For Data-plane: `fluxgate-sample-redis`

Use this for:
- Calling the `RateLimiter` API directly from your own code (no servlet filter)
- Distributed rate limiting across multiple instances
- A data-plane with a rate limit check API (`POST /api/ratelimit/check`) and runtime rule sets
  (`/admin/rules`)

### For Full Integration: `fluxgate-sample-api`

Use this for:
- An HTTP demo that ties the two samples above together: rules created in `fluxgate-sample-mongo`
  are synced (`POST /admin/sync`, first band of the first rule) into `fluxgate-sample-redis`, and
  requests are proxied to it
- Seeing a separate control plane and data plane talk to each other; this application itself has no
  FluxGate dependency and uses neither database

### For Standalone Integration: `fluxgate-sample-standalone-java21` / `-java11`

Use this for:
- Direct MongoDB + Redis integration in one application (no other sample needed)
- Several filters with their own rule sets, plus the `@RateLimit` aspect
- Runtime rule creation via REST API (rules are written to MongoDB and read on demand)
- Actuator health and Prometheus metrics

## Architecture

How the HTTP samples connect (the standalone samples do all of this in one process):

```
┌──────────────────────┐   GET /admin/rules    ┌──────────────────────┐
│ fluxgate-sample-api  │──────────────────────▶│ fluxgate-sample-mongo│
│ (8080, no FluxGate)  │                       │ Control-plane (8081) │
│ /admin/sync          │                       │ - rules in MongoDB   │
│ /api/* proxy         │                       │ - admin API          │
└──────────┬───────────┘                       └──────────────────────┘
           │ POST /admin/rules (sync), GET /api/*
           ▼
┌──────────────────────┐  POST /api/ratelimit/check  ┌──────────────────────┐
│ fluxgate-sample-redis│◀────────────────────────────│ fluxgate-sample-     │
│ Data-plane (8082)    │                             │ filter (8083)        │
│ - token buckets      │                             │ @EnableFluxgateFilter│
│ - rule sets in Redis │                             │ HttpRateLimitHandler │
└──────────────────────┘                             └──────────────────────┘
```

## Configuration Reference

### application.yml

```yaml
fluxgate:
  # Redis Configuration
  redis:
    enabled: true
    uri: redis://localhost:6379

  # MongoDB Configuration (optional)
  mongo:
    enabled: false
    uri: mongodb://user:pass@localhost:27017/fluxgate
    database: fluxgate

  # Rate Limiting Configuration
  ratelimit:
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**        # /* matches ONE segment only
    exclude-patterns:
      - /health
      - /actuator/**
    filter-order: 1
```

No handler class is needed. With a `RateLimiter` (Redis, or `mode: IN_MEMORY`) and a
`RateLimitRuleSetProvider` on the context, the starter registers `EngineBackedRateLimitHandler`
automatically. `fluxgate-sample-filter` keeps a handler on purpose, to demonstrate the HTTP API
pattern.

### Annotations

```java
@SpringBootApplication
@EnableFluxgateFilter  // Enables automatic rate limiting
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

## Key Features Demonstrated

| Feature | Standalone | Filter | Redis | Mongo | API |
|---------|:----------:|:------:|:-----:|:-----:|:---:|
| FluxGate servlet filter | ✅ (`FilterRegistrationBean`s) | ✅ (`@EnableFluxgateFilter`) | ❌ (limits in the controller) | ❌ | ❌ |
| `@RateLimit` aspect | ✅ | ❌ | ❌ | ❌ | ❌ |
| Redis token bucket | ✅ | ❌ (via HTTP to Redis) | ✅ | ❌ | ❌ (via HTTP to Redis) |
| MongoDB rule storage | ✅ | ❌ | ❌ | ✅ | ❌ (via HTTP to Mongo) |
| Dynamic rule updates | ✅ (`/api/admin/rules/*`) | ❌ (rules live in the Redis sample) | ✅ (`/admin/rules`, `DynamicRuleSetProvider`) | ✅ (stored, not enforced) | via Mongo + `/admin/sync` |
| REST admin API | ✅ | ❌ | ✅ (`RuleAdminController`) | ✅ (`RuleAdminController`) | ✅ (proxy) |
| Rate limit headers | ✅ | ✅ | `Retry-After` only | ❌ | ❌ |
| MongoDB and Redis in one process | ✅ | ❌ | ❌ | ❌ | ❌ |

## Learn More

- [FluxGate Core Documentation](../fluxgate-core/README.md)
- [Redis Rate Limiter](../fluxgate-redis-ratelimiter/README.md)
- [MongoDB Adapter](../fluxgate-mongo-adapter/README.md)
- [Spring Boot Starter](../fluxgate-spring-boot3-starter/README.md)
