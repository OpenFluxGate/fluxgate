# FluxGate Sample - API Gateway (Control-plane + Data-plane)

This sample ties the two other samples together over HTTP. Rules are managed in the
**Control-plane** ([fluxgate-sample-mongo](../fluxgate-sample-mongo), MongoDB, port 8081) and enforced
by the **Data-plane** ([fluxgate-sample-redis](../fluxgate-sample-redis), Redis, port 8082). This
application uses neither database itself: it calls both services with Spring's `RestClient`.

## Key Features

- **Control-plane calls** - create the sample rules, list and delete rules in MongoDB through
  `fluxgate-sample-mongo`
- **Sync** - register a MongoDB rule set in the Redis Data-plane
- **Data-plane calls** - rate-limited requests proxied to `fluxgate-sample-redis`
- **Swagger UI** - every endpoint is documented with OpenAPI annotations

## Prerequisites

> This sample has no FluxGate module dependency; it only needs the parent POMs. Run
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-api -am` (JDK 17+) once from
> the project root first (see [Build Once](../README.md#build-once)). The samples it calls,
> `fluxgate-sample-mongo` and `fluxgate-sample-redis`, need the `0.4.0-SNAPSHOT` modules.

```bash
# MongoDB for the Control-plane
docker run -d --name mongodb -p 127.0.0.1:27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:7.0.14

# Redis for the Data-plane
docker run -d --name redis -p 127.0.0.1:6379:6379 redis:7.2.5-alpine

# The two services this sample calls
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo   # 8081
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis   # 8082
```

## Quick Start

### 1. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-api
```

The application starts on port **8080**.

### 2. Create Sample Rules in MongoDB

Calls the Control-plane's `POST /admin/rules/sample`, which creates three rules in rule set
`api-gateway-rules`:

```bash
curl -X POST http://localhost:8080/admin/rules/init
```

### 3. Sync the Rule Set to Redis

Reads the rule set from the Control-plane and registers it in the Data-plane. This is simplified:
only the capacity and window of the **first band of the first rule** are synced. The Control-plane
returns `RateLimitRule` JSON, where a band's window is the `Duration` property `window` (ISO-8601,
e.g. `"PT1M"`; a number of seconds is accepted too, and `windowSeconds` as a fallback). Fractions
of a second are rounded up (`PT0.5S` -> 1 s). A rule set with no rules is answered with 400; a first
band without a positive capacity and window is answered with 422 and nothing is registered.

```bash
curl -X POST "http://localhost:8080/admin/sync?ruleSetId=api-gateway-rules"
```

Response (the first sample rule is normally `api-rate-limit-100rpm`, 100 per 60 s; MongoDB returns
the rules in natural order):

```json
{
  "message": "Rules synced successfully",
  "ruleSetId": "api-gateway-rules",
  "rulesFromMongo": 3,
  "capacity": 100,
  "windowSeconds": 60,
  "redisResponse": {
    "message": "RuleSet created successfully",
    "ruleSetId": "api-gateway-rules",
    "capacity": 100,
    "windowSeconds": 60
  }
}
```

### 4. Test Rate Limiting

Send one request more than the synced `capacity` (101 for 100 per 60 s):

```bash
for i in {1..101}; do
  curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/test?ruleSetId=api-gateway-rules"
done | sort | uniq -c
```

Expected:
```
 100 200
   1 429
```

Once the limit is used up the Data-plane answers 429, which this sample passes on with its own JSON
body (the Data-plane's `Retry-After` header is not forwarded):

```json
{
  "status": "REJECTED",
  "error": "Rate limit exceeded",
  "ruleSetId": "api-gateway-rules",
  "message": "Too many requests. Please try again later."
}
```

A rule set the Data-plane does not know is reported as 400 with a hint to call `/admin/sync`.
The Data-plane limits per client IP, and the IP it sees is this gateway's, so every caller of
this sample shares one bucket.

## Project Structure

```
fluxgate-sample-api/
├── src/main/java/org/fluxgate/sample/api/
│   ├── ApiSampleApplication.java        # Main application
│   ├── config/
│   │   ├── OpenApiConfig.java           # Swagger / OpenAPI configuration
│   │   ├── RestClientConfig.java        # RestClients for the Control- and Data-plane
│   │   └── ServiceProperties.java       # fluxgate.services.* URLs
│   └── controller/
│       ├── AdminController.java         # Rule management and sync
│       └── ApiController.java           # Requests proxied to the Data-plane
├── src/main/resources/
│   └── application.yml                  # Configuration
└── src/test/java/org/fluxgate/sample/api/
    ├── ApiSampleStartupTest.java        # shipped application.yml
    ├── config/OpenApiConfigTest.java
    └── controller/
        ├── AdminControllerParsingTest.java
        └── AdminControllerSyncTest.java
```

## Architecture

```
                       ┌──────────────────────────────┐
     client  ────────▶ │  fluxgate-sample-api (8080)  │
                       │  AdminController             │
                       │  ApiController               │
                       └──────┬────────────────┬──────┘
               /admin/rules*  │                │  /admin/rules (sync), /api/*
                              ▼                ▼
          ┌───────────────────────────┐  ┌───────────────────────────┐
          │ fluxgate-sample-mongo     │  │ fluxgate-sample-redis     │
          │ (Control-plane, 8081)     │  │ (Data-plane, 8082)        │
          │ rules in MongoDB          │  │ rate limiting in Redis    │
          └───────────────────────────┘  └───────────────────────────┘
```

## Configuration

### application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: fluxgate-sample-api

# External service URLs
fluxgate:
  services:
    control-plane-url: http://localhost:8081   # fluxgate-sample-mongo
    data-plane-url: http://localhost:8082      # fluxgate-sample-redis

logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.sample: DEBUG
```

Both URLs default to the same values in `ServiceProperties` when the keys are absent. The OpenAPI
version shown in Swagger UI (`/swagger-ui.html`) is the Maven project version, read from the
`META-INF/build-info.properties` that the `build-info` goal of `spring-boot-maven-plugin` writes.

## REST API

### Admin Endpoints (`AdminController`)

| Method | Path | Calls | Description |
|--------|------|-------|-------------|
| POST | `/admin/rules/init` | Control-plane `POST /admin/rules/sample` | Create the sample rules in MongoDB (201) |
| GET | `/admin/rules?ruleSetId=` | Control-plane `GET /admin/rules?ruleSetId=` | List the rules of a rule set (default `api-gateway-rules`) |
| DELETE | `/admin/rules/{ruleSetId}/{id}` | Control-plane `DELETE /admin/rules/{ruleSetId}/{id}` | Delete one rule (204) |
| POST | `/admin/sync?ruleSetId=` | Control-plane `GET /admin/rules?ruleSetId=`, Data-plane `POST /admin/rules` | Register a MongoDB rule set in Redis (default `api-gateway-rules`; 200, 400 without rules, 422 without a positive capacity/window) |
| GET | `/admin/redis/rules` | Data-plane `GET /admin/rules` | List the rule sets registered in Redis (`{"ruleSets": [...], "count": n}`) |

### API Endpoints (`ApiController`)

| Method | Path | Calls | Description |
|--------|------|-------|-------------|
| GET | `/api/test?ruleSetId=` | Data-plane `GET /api/test?ruleSetId=` | Rate-limited request against a synced rule set (default `api-gateway-rules`); 200, 429 or 400 |
| GET | `/api/hello` | Data-plane `GET /api/test?ruleSetId=api-limits` | Same, with `api-limits`: the default rule set `fluxgate-sample-redis` registers at startup (10 per 60 s per IP), so no sync is needed |
| GET | `/api/status` | Data-plane `GET /api/status` | Data-plane status, not rate limited |

### Delete a Rule

```bash
curl -X DELETE http://localhost:8080/admin/rules/api-gateway-rules/api-rate-limit-100rpm
```

## Comparison with Other Samples

This sample has no FluxGate dependency at all: it only calls the other two samples over HTTP.

| Feature | API (this) | Filter | Redis | Mongo |
|---------|:----------:|:------:|:-----:|:-----:|
| FluxGate dependencies | none | `spring-boot3-starter` | `spring-boot3-starter`, `redis-ratelimiter` | `spring-boot3-starter`, `mongo-adapter` |
| MongoDB access | ❌ (via HTTP to Mongo) | ❌ | ❌ | ✅ |
| Redis access | ❌ (via HTTP to Redis) | ❌ (via HTTP to Redis) | ✅ | ❌ |
| HTTP filter (`@EnableFluxgateFilter`) | ❌ | ✅ | ❌ (limits in the controller) | ❌ |
| Dynamic rules | via Mongo + `/admin/sync` | ❌ (rules live in the Redis sample) | ✅ (`/admin/rules`, `DynamicRuleSetProvider`) | ✅ (stored, not enforced) |
| Admin API | ✅ (proxy) | ❌ | ✅ (`RuleAdminController`) | ✅ (`RuleAdminController`) |
| Rate limit headers | ❌ | ✅ (`X-RateLimit-*`, `RateLimit-*`) | `Retry-After` only | ❌ |

## When to Use This Sample

- **Separated control and data planes**: see how rules managed in MongoDB reach a Redis-backed
  rate limiter
- **Service-to-service calls** between a gateway and the FluxGate samples

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-filter](../fluxgate-sample-filter) - For simpler setups
- [FluxGate Core Documentation](../../fluxgate-core/README.md)
