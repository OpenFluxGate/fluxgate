# FluxGate Samples

This module contains sample applications demonstrating various FluxGate rate limiting configurations and use cases.

## Overview

FluxGate is a distributed rate limiting framework for Spring Boot applications. These samples showcase different deployment patterns and feature combinations.

## Sample Applications

| Sample | Port | Description | Prerequisites |
|--------|------|-------------|---------------|
| [fluxgate-sample-filter](./fluxgate-sample-filter) | 8083 | **Recommended** - Automatic rate limiting with `@EnableFluxgateFilter` annotation | Redis |
| [fluxgate-sample-redis](./fluxgate-sample-redis) | 8082 | Redis-based rate limiting (Data-plane) | Redis |
| [fluxgate-sample-mongo](./fluxgate-sample-mongo) | 8081 | MongoDB rule management (Control-plane) | MongoDB |
| [fluxgate-sample-api](./fluxgate-sample-api) | 8080 | Full API Gateway with MongoDB + Redis | MongoDB, Redis |

## Quick Start

### Prerequisites

```bash
# Start Redis
docker run -d --name redis -p 6379:6379 redis:latest

# Start MongoDB (if needed)
docker run -d --name mongodb -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:latest
```

### Run a Sample

```bash
# From the project root directory
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter
```

### Test Rate Limiting

```bash
# Send 12 requests (default limit is 10/minute)
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
- Automatic rate limiting via HTTP filter
- Dynamic rule updates via Redis
- Modern annotation-based configuration (`@EnableFluxgateFilter`)

### For Control-plane: `fluxgate-sample-mongo`

Use this for:
- Centralized rule management
- REST API for CRUD operations on rules
- Admin dashboard backend

### For Data-plane: `fluxgate-sample-redis`

Use this for:
- High-performance rate limiting
- Distributed rate limiting across multiple instances
- Standalone data-plane deployment

### For Full Integration: `fluxgate-sample-api`

Use this for:
- Complete API gateway solution
- Combined control-plane and data-plane
- Production-like architecture

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        FluxGate Architecture                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐              │
│  │  Control-plane   │         │   Data-plane     │              │
│  │  (MongoDB)       │────────▶│   (Redis)        │              │
│  │                  │  sync   │                  │              │
│  │  - Rule storage  │         │  - Token bucket  │              │
│  │  - CRUD APIs     │         │  - Rate limiting │              │
│  │  - Admin UI      │         │  - HTTP Filter   │              │
│  └──────────────────┘         └──────────────────┘              │
│                                        │                         │
│                                        ▼                         │
│                               ┌──────────────────┐              │
│                               │  Your API        │              │
│                               │  Endpoints       │              │
│                               └──────────────────┘              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
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
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
    filter-order: 1
```

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

| Feature | Filter | Redis | Mongo | API |
|---------|:------:|:-----:|:-----:|:---:|
| Automatic HTTP Filter | ✅ | ✅ | ❌ | ✅ |
| Redis Token Bucket | ✅ | ✅ | ❌ | ✅ |
| MongoDB Rule Storage | ❌ | ❌ | ✅ | ✅ |
| Dynamic Rule Updates | ✅ | ❌ | ✅ | ✅ |
| REST Admin API | ✅ | ❌ | ✅ | ✅ |
| Rate Limit Headers | ✅ | ✅ | ❌ | ✅ |

## Learn More

- [FluxGate Core Documentation](../fluxgate-core/README.md)
- [Redis Rate Limiter](../fluxgate-redis-ratelimiter/README.md)
- [MongoDB Adaptor](../fluxgate-mongo-adaptor/README.md)
- [Spring Boot Starter](../fluxgate-spring-boot-starter/README.md)
