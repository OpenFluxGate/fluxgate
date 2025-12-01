# FluxGate Sample - API Gateway (Full Integration)

This sample demonstrates the **complete FluxGate integration** combining MongoDB (control-plane) and Redis (data-plane) for a production-like API gateway architecture.

## Key Features

- **Complete integration** - MongoDB + Redis + HTTP Filter
- **Control-plane operations** - Rule management via MongoDB
- **Data-plane operations** - Rate limiting via Redis
- **Production architecture** - Separated concerns for scalability
- **Full REST API** - Both admin and rate-limited endpoints

## Prerequisites

```bash
# Start Redis
docker run -d --name redis -p 6379:6379 redis:latest

# Start MongoDB
docker run -d --name mongodb -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:latest
```

## Quick Start

### 1. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-api
```

The application starts on port **8080**.

### 2. Initialize Sample Rules

```bash
curl -X POST http://localhost:8080/admin/rules/init
```

Response:
```json
{
  "message": "Sample rules initialized",
  "rules": ["api-rate-limit", "premium-rate-limit"]
}
```

### 3. Test Rate Limiting

```bash
# Send 15 requests (limit is 10 per minute)
for i in {1..15}; do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/hello
  echo ""
done
```

Expected output:
```
Request 1: 200
Request 2: 200
...
Request 10: 200
Request 11: 429  # Rate limited!
Request 12: 429
...
```

### 4. Check Rate Limit Headers

```bash
curl -i http://localhost:8080/api/hello
```

Response headers:
```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 9
X-RateLimit-Reset: 1701234567
```

## Project Structure

```
fluxgate-sample-api/
├── src/main/java/org/fluxgate/sample/api/
│   ├── ApiSampleApplication.java        # Main application
│   ├── config/
│   │   ├── MongoConfig.java             # MongoDB configuration
│   │   ├── RedisConfig.java             # Redis configuration
│   │   └── FilterConfig.java            # HTTP filter configuration
│   └── controller/
│       ├── ApiController.java           # Rate-limited API endpoints
│       └── AdminController.java         # Admin API for rules
└── src/main/resources/
    └── application.yml                  # Configuration
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    FluxGate API Gateway                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│                        ┌────────────────┐                       │
│                        │   Client       │                       │
│                        │   Request      │                       │
│                        └───────┬────────┘                       │
│                                │                                 │
│                                ▼                                 │
│                  ┌─────────────────────────┐                    │
│                  │  FluxgateRateLimitFilter │                    │
│                  │                         │                    │
│                  │  1. Extract client key  │                    │
│                  │  2. Load rules from     │                    │
│                  │     MongoDB             │                    │
│                  │  3. Check rate limit    │                    │
│                  │     via Redis           │                    │
│                  │  4. Allow or reject     │                    │
│                  └───────────┬─────────────┘                    │
│                              │                                   │
│            ┌─────────────────┼─────────────────┐                │
│            │                 │                 │                │
│            ▼                 ▼                 ▼                │
│     ┌──────────┐      ┌──────────┐      ┌──────────┐           │
│     │  200 OK  │      │  429     │      │  Admin   │           │
│     │  API     │      │  Rate    │      │  API     │           │
│     │  Response│      │  Limited │      │  (CRUD)  │           │
│     └──────────┘      └──────────┘      └──────────┘           │
│                                                                  │
│  ┌──────────────────┐         ┌──────────────────┐              │
│  │  MongoDB         │         │   Redis          │              │
│  │  (Control-plane) │◀───────▶│   (Data-plane)   │              │
│  │                  │  sync   │                  │              │
│  │  • Rule storage  │         │  • Token bucket  │              │
│  │  • Audit logs    │         │  • Rate counters │              │
│  └──────────────────┘         └──────────────────┘              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Configuration

### application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: fluxgate-sample-api

fluxgate:
  # MongoDB for rule storage (control-plane)
  mongo:
    enabled: true
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events

  # Redis for rate limiting (data-plane)
  redis:
    enabled: true
    uri: redis://localhost:6379

  # Rate limiting configuration
  ratelimit:
    enabled: true
    filter-enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
    exclude-patterns:
      - /admin/**
      - /actuator/**
      - /health
    client-ip-header: X-Forwarded-For
    trust-client-ip-header: true
    include-headers: true
```

## REST API

### Admin Endpoints (Not Rate Limited)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/rules` | List all rules |
| GET | `/admin/rules/{id}` | Get rule by ID |
| POST | `/admin/rules` | Create rule |
| PUT | `/admin/rules/{id}` | Update rule |
| DELETE | `/admin/rules/{id}` | Delete rule |
| POST | `/admin/rules/init` | Initialize sample rules |
| GET | `/admin/rulesets` | List all RuleSets |
| GET | `/admin/stats` | Get rate limiting statistics |

### API Endpoints (Rate Limited)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/hello` | Hello endpoint |
| GET | `/api/users` | Users endpoint |
| GET | `/api/products` | Products endpoint |
| POST | `/api/orders` | Orders endpoint |

### Health Endpoints (Not Rate Limited)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| GET | `/actuator/health` | Actuator health |

## Usage Examples

### Create a Custom Rule

```bash
curl -X POST http://localhost:8080/admin/rules \
  -H "Content-Type: application/json" \
  -d '{
    "id": "vip-api-limit",
    "name": "VIP API Rate Limit",
    "ruleSetId": "vip-limits",
    "enabled": true,
    "capacity": 1000,
    "windowSeconds": 60,
    "scope": "PER_USER",
    "keyStrategyId": "userId"
  }'
```

### Update Existing Rule

```bash
curl -X PUT http://localhost:8080/admin/rules/api-rate-limit \
  -H "Content-Type: application/json" \
  -d '{
    "capacity": 20,
    "windowSeconds": 60
  }'
```

### Get Rate Limit Statistics

```bash
curl http://localhost:8080/admin/stats
```

Response:
```json
{
  "totalRequests": 1500,
  "allowedRequests": 1200,
  "rejectedRequests": 300,
  "averageLatencyMs": 5.2,
  "activeRules": 3
}
```

## Rule Synchronization

Rules are synchronized from MongoDB to Redis automatically:

```
┌──────────────────────────────────────────────────────────┐
│                   Rule Synchronization                    │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  1. Admin creates/updates rule via REST API               │
│                        │                                  │
│                        ▼                                  │
│  2. Rule persisted to MongoDB                            │
│                        │                                  │
│                        ▼                                  │
│  3. Change event triggers sync                           │
│                        │                                  │
│                        ▼                                  │
│  4. Rule config pushed to Redis                          │
│                        │                                  │
│                        ▼                                  │
│  5. Filter uses updated rules immediately                │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

## Multi-tier Rate Limiting

```bash
# Example: Different limits for different user tiers
{
  "rules": [
    {
      "id": "free-tier",
      "ruleSetId": "free-limits",
      "capacity": 10,
      "windowSeconds": 60,
      "scope": "PER_USER"
    },
    {
      "id": "pro-tier",
      "ruleSetId": "pro-limits",
      "capacity": 100,
      "windowSeconds": 60,
      "scope": "PER_USER"
    },
    {
      "id": "enterprise-tier",
      "ruleSetId": "enterprise-limits",
      "capacity": 10000,
      "windowSeconds": 60,
      "scope": "PER_USER"
    }
  ]
}
```

## Production Deployment

### Docker Compose

```yaml
version: '3.8'
services:
  api:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - FLUXGATE_MONGO_URI=mongodb://mongodb:27017/fluxgate
      - FLUXGATE_REDIS_URI=redis://redis:6379
    depends_on:
      - mongodb
      - redis

  mongodb:
    image: mongo:latest
    volumes:
      - mongo-data:/data/db

  redis:
    image: redis:latest
    volumes:
      - redis-data:/data

volumes:
  mongo-data:
  redis-data:
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fluxgate-api
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: api
          image: fluxgate-sample-api:latest
          env:
            - name: FLUXGATE_MONGO_URI
              valueFrom:
                secretKeyRef:
                  name: fluxgate-secrets
                  key: mongo-uri
            - name: FLUXGATE_REDIS_URI
              valueFrom:
                secretKeyRef:
                  name: fluxgate-secrets
                  key: redis-uri
```

## Comparison with Other Samples

| Feature | API (this) | Filter | Redis | Mongo |
|---------|:----------:|:------:|:-----:|:-----:|
| MongoDB integration | ✅ | ❌ | ❌ | ✅ |
| Redis integration | ✅ | ✅ | ✅ | ❌ |
| HTTP Filter | ✅ | ✅ | ✅ | ❌ |
| Dynamic rules | ✅ | ✅ | ❌ | ✅ |
| Admin API | ✅ | ✅ | ❌ | ✅ |
| Rate limit headers | ✅ | ✅ | ✅ | ❌ |
| Event logging | ✅ | ❌ | ❌ | ✅ |
| Production-ready | ✅ | ✅ | ⚠️ | ⚠️ |

## When to Use This Sample

- **Full-featured API gateway** with rate limiting
- **Production deployments** requiring both control and data planes
- **Multi-tier rate limiting** based on user subscription
- **Audit requirements** for rate limit events
- **Scalable architecture** with separated concerns

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-filter](../fluxgate-sample-filter) - For simpler setups
- [FluxGate Core Documentation](../../fluxgate-core/README.md)
