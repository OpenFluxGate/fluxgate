# FluxGate Sample - MongoDB (Control-plane)

This sample demonstrates **MongoDB-based rule management** for rate limiting. It represents the
**control-plane**: it stores rules and serves them over a small admin API. It does not rate limit
any request itself (no `@EnableFluxgateFilter`, no `@RateLimit`).

## Key Features

- **Persistent rule storage** - rules stored in MongoDB through `MongoRateLimitRuleRepository`
- **Admin REST API** - list, get and delete rules, delete a rule set, and create three sample
  rules; there is no create or update endpoint for arbitrary rules
- **`(ruleSetId, id)` identity** - the single-rule endpoints address a rule by rule set and id,
  backed by the unique index that `ddl-auto: create` builds
- **Event collection configured** - `event-collection: rate_limit_events` is set, but events are only
  written by an application that rate limits with this configuration; this sample does not, so the
  collection stays empty here

## Prerequisites

```bash
# Start MongoDB
docker run -d --name mongodb -p 127.0.0.1:27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:7.0.14
```

## Quick Start

### 1. Run the Application

> Run `./mvnw -B install -DskipTests` (JDK 21) once from the project root first, or
> `./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-mongo -am` (JDK 17+):
> the samples depend on the `0.4.0-SNAPSHOT` modules (see [Build Once](../README.md#build-once)).

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo
```

The application starts on port **8081**.

### 2. Create Sample Rules

```bash
# Initialize sample rules
curl -X POST http://localhost:8081/admin/rules/sample
```

### 3. List All Rules

```bash
curl http://localhost:8081/admin/rules/all
```

Response: a JSON array of `RateLimitRule` as Jackson serializes it. The rule set id is the
property `ruleSetIdOrNull`, a band's window is the `Duration` property `window` (ISO-8601), and the
matcher fields are nested under `matcher`. First element (the other two rules are left out):

```json
[
  {
    "id": "api-rate-limit-100rpm",
    "name": "API Rate Limit - 100 RPM",
    "enabled": true,
    "scope": "PER_IP",
    "keyStrategyId": "clientIp",
    "onLimitExceedPolicy": "REJECT_REQUEST",
    "bands": [
      {
        "window": "PT1M",
        "capacity": 100,
        "label": "100-per-minute",
        "algorithm": "TOKEN_BUCKET",
        "quotaPeriod": null,
        "zoneId": "Z",
        "slidingWindowBuckets": 10,
        "keyLabel": "100-per-minute"
      }
    ],
    "attributes": {},
    "priority": 0,
    "matcher": {
      "methods": [],
      "pathPatterns": [],
      "excludePathPatterns": [],
      "headerEquals": {},
      "headerPresent": []
    },
    "ruleSetIdOrNull": "api-gateway-rules"
  }
]
```

The three sample rules (rule set `api-gateway-rules`) are `api-rate-limit-100rpm` (100 per 60 s,
`PER_IP`), `api-burst-limit-10rps` (10 per 1 s, `PER_IP`) and `user-hourly-limit` (1000 per 3600 s,
`PER_USER`).

## Project Structure

```
fluxgate-sample-mongo/
├── src/main/java/org/fluxgate/sample/mongo/
│   ├── MongoSampleApplication.java     # Main application
│   ├── config/
│   │   └── OpenApiConfig.java          # Swagger / OpenAPI configuration
│   └── controller/
│       └── RuleAdminController.java    # REST API for rules
├── src/main/resources/
│   └── application.yml                 # Configuration
└── src/test/java/org/fluxgate/sample/mongo/
    ├── MongoSampleStartupTest.java     # shipped application.yml, Testcontainers MongoDB
    ├── config/OpenApiConfigTest.java
    └── controller/RuleAdminControllerTest.java   # MockMvc, mocked repository
```

## Configuration

### application.yml

```yaml
server:
  port: 8081

spring:
  application:
    name: fluxgate-sample-mongo

# FluxGate Configuration - Control-plane (MongoDB only)
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://${MONGO_USER:fluxgate}:${MONGO_PASSWORD:fluxgate123}@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: rate_limit_rules
    # Optional: Enable event logging (comment out to disable)
    event-collection: rate_limit_events
    # DDL auto mode: validate (default) or create
    ddl-auto: create
  redis:
    enabled: false  # No RateLimiter in control-plane: this sample only manages rules

logging:
  level:
    org.fluxgate: DEBUG
    org.fluxgate.sample: DEBUG
```

`ddl-auto: create` creates the collections and the unique `(ruleSetId, id)` index on startup, so a
fresh MongoDB works; with the default `validate` the application refuses to start until they exist.
The sample does no rate limiting: with `fluxgate.redis.enabled: false` there is no `RateLimiter`, and
it declares neither `@EnableFluxgateFilter` nor `@EnableFluxgateAspect`, so no `fluxgate.ratelimit.*`
setting is needed. Credentials come from `MONGO_USER` / `MONGO_PASSWORD` (defaults match the
`docker run` above).

## REST API

The admin API lives under `/admin/rules` (`RuleAdminController`); Swagger UI documents it as well.
Rules are created through the repository (`POST /admin/rules/sample`), not through a create or update
endpoint.

#### Create Sample Rules

Creates three rules in rule set `api-gateway-rules`:

```bash
curl -X POST http://localhost:8081/admin/rules/sample
```

#### List Rules of a Rule Set

```bash
curl "http://localhost:8081/admin/rules?ruleSetId=api-gateway-rules"
```

#### List All Rules

```bash
curl http://localhost:8081/admin/rules/all
```

#### Get a Rule

A rule is identified by `(ruleSetId, id)`, so the single-rule endpoints take both:

```bash
curl http://localhost:8081/admin/rules/api-gateway-rules/api-rate-limit-100rpm
```

#### Delete a Rule

```bash
curl -X DELETE http://localhost:8081/admin/rules/api-gateway-rules/api-rate-limit-100rpm
```

#### Delete a Rule Set

```bash
curl -X DELETE "http://localhost:8081/admin/rules?ruleSetId=api-gateway-rules"
```

Response:
```json
{ "message": "Rules deleted successfully", "ruleSetId": "api-gateway-rules", "rulesDeleted": 3 }
```

## MongoDB Collections

### rate_limit_rules

Stores rate limiting rules:

```json
{
  "_id": ObjectId("..."),
  "id": "api-rate-limit",
  "name": "API Rate Limit",
  "ruleSetId": "api-limits",
  "enabled": true,
  "scope": "PER_IP",
  "keyStrategyId": "clientIp",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "bands": [
    {
      "windowSeconds": 60,
      "capacity": 10,
      "label": "10-per-minute",
      "algorithm": "TOKEN_BUCKET"
    }
  ],
  "priority": 0,
  "pathPatterns": ["/api/**"]
}
```

A rule is identified by `(ruleSetId, id)` (unique index `ruleSetId_1_id_1_unique`); `_id` is the
MongoDB-generated `ObjectId`. Empty optional fields (`attributes`, `methods`, `pathPatterns`, ...) are
omitted. The rule set's access control (`allowedIps`, `deniedIps`, `allowedKeys`, `deniedKeys`, plus
the `aclUpdatedAt` marker) is stored on every rule document of the rule set; see
[fluxgate-mongo-adapter](../../fluxgate-mongo-adapter/README.md#mongodb-schema).

### rate_limit_events

Stores rate limit events for auditing. Nothing writes to it in this sample, which does not rate
limit; an application that rate limits with the same `fluxgate.mongo.event-collection` does:

```json
{
  "_id": ObjectId("..."),
  "timestamp": 1704067230000,
  "createdAt": ISODate("2024-01-01T00:00:30Z"),
  "allowed": false,
  "remainingTokens": 0,
  "ruleSetId": "api-limits",
  "ruleId": "api-rate-limit",
  "clientIp": "192.168.1.100"
}
```

Abridged; the full event document is described in
[fluxgate-mongo-adapter](../../fluxgate-mongo-adapter/README.md#rate-limit-event-collection).

## MongoDB Schema

```
┌─────────────────────────────────────────────────────────┐
│                    MongoDB Schema                       │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Database: fluxgate                                     │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Collection: rate_limit_rules                    │   │
│  │                                                 │   │
│  │ - _id (ObjectId)        # MongoDB id           │   │
│  │ - id (String)           # Rule ID              │   │
│  │ - ruleSetId (String)    # Group identifier     │   │
│  │   unique (ruleSetId, id)                       │   │
│  │ - name (String)         # Display name         │   │
│  │ - enabled (Boolean)     # Active flag          │   │
│  │ - scope (String)        # PER_IP, PER_USER ... │   │
│  │ - keyStrategyId (String)# Key extraction       │   │
│  │ - bands (Array)         # Rate limit bands     │   │
│  │ - priority, methods,    # 0.4 matcher fields   │   │
│  │   pathPatterns, ...                            │   │
│  │ - allowedIps, ...       # Rule set ACL copy    │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Collection: rate_limit_events                   │   │
│  │                                                 │   │
│  │ - _id (ObjectId)        # Event ID             │   │
│  │ - ruleSetId, ruleId     # Associated rule      │   │
│  │ - allowed (Boolean)     # Was request allowed  │   │
│  │ - remainingTokens (Long)# Tokens remaining     │   │
│  │ - timestamp (Long)      # Epoch millis         │   │
│  │ - createdAt (Date)      # TTL index field      │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Rule Structure

### RateLimitRule Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | String | Unique rule identifier |
| `name` | String | Human-readable name |
| `ruleSetId` | String | RuleSet grouping |
| `enabled` | Boolean | Whether rule is active |
| `scope` | Enum | `GLOBAL`, `PER_API_KEY`, `PER_USER`, `PER_IP`, `CUSTOM` |
| `keyStrategyId` | String | Key extraction strategy |
| `onLimitExceedPolicy` | Enum | `REJECT_REQUEST`, `WAIT_FOR_REFILL` |
| `bands` | Array | Rate limit bands |
| `priority` | Int | Evaluation order among matching rules (higher first) |
| `methods`, `pathPatterns`, `excludePathPatterns`, `headerEquals`, `headerPresent` | Matcher | Which requests the rule applies to (empty = all) |

### RateLimitBand Fields

| Field | Type | Description |
|-------|------|-------------|
| `windowSeconds` | Long | Window in seconds (the API's `window` property) |
| `capacity` | Long | Max tokens |
| `label` | String | Band identifier |
| `algorithm` | String | `TOKEN_BUCKET` (default), or another `RateLimitAlgorithm` |
| `quotaPeriod`, `zoneId`, `slidingWindowBuckets` | | Only stored when they differ from the defaults (`zoneId` is omitted for UTC under any spelling — `Z`, `UTC`, `Etc/UTC`; older documents that stored `Z` still load) |

## Testing against MongoDB

`fluxgate-testkit` has no MongoDB support: its `InMemoryRateLimitHandler` and
`FluxgateInMemoryExtension` replace the data plane, not the rule store. To test the rule admin API
against a real MongoDB, start one with Testcontainers. This sample's own `RuleAdminControllerTest`
uses a mocked repository instead; the test below is an example you can add. Test dependencies
(versions from the Spring Boot and Testcontainers BOMs): `spring-boot-starter-test`,
`org.testcontainers:junit-jupiter` and `org.testcontainers:mongodb`.

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class MongoRuleRepositoryIT {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer(DockerImageName.parse("mongo:7.0.14"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("fluxgate.mongo.uri", () -> mongo.getReplicaSetUrl("fluxgate"));
        registry.add("fluxgate.mongo.database", () -> "fluxgate");
        registry.add("fluxgate.mongo.ddl-auto", () -> "create"); // builds the unique (ruleSetId, id) index
    }

    // The starter registers the bean as RateLimitRuleRepository; the (ruleSetId, id) methods are
    // on the MongoDB implementation.
    @Autowired
    private RateLimitRuleRepository ruleRepository;

    @Test
    void shouldCreateRule() {
        MongoRateLimitRuleRepository repository = (MongoRateLimitRuleRepository) ruleRepository;
        RateLimitRule rule =
            RateLimitRule.builder("api-rate-limit")
                .name("API Rate Limit")
                .scope(LimitScope.PER_IP)
                .keyStrategyId("clientIp")
                .ruleSetId("api-limits")
                .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("10-per-minute").build())
                .build();

        repository.save(rule);

        assertThat(repository.findById("api-limits", "api-rate-limit")).isPresent();
        assertThat(repository.findByRuleSetId("api-limits")).hasSize(1);
    }
}
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.mongo.enabled` | `false` | Enable MongoDB |
| `fluxgate.mongo.uri` | `mongodb://localhost:27017/fluxgate` | MongoDB connection URI |
| `fluxgate.mongo.database` | `fluxgate` | Database name |
| `fluxgate.mongo.rule-collection` | `rate_limit_rules` | Rules collection |
| `fluxgate.mongo.event-collection` | unset (no event logging) | Events collection (this sample sets `rate_limit_events`) |
| `fluxgate.mongo.ddl-auto` | `validate` | `validate` or `create` (this sample sets `create`) |
| `fluxgate.mongo.event-retention` | `30d` | TTL of event documents |

## Control-plane vs Data-plane

```
┌─────────────────────────────────────────────────────────┐
│                  Architecture Overview                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────────┐                               │
│  │   Control-plane      │  ◀── This sample             │
│  │   (MongoDB)          │                               │
│  │                      │                               │
│  │  • Rule storage      │                               │
│  │  • Admin API         │                               │
│  └──────────┬───────────┘                               │
│             │                                            │
│             │ Sync: fluxgate-sample-api /admin/sync      │
│             ▼                                            │
│  ┌──────────────────────┐                               │
│  │   Data-plane         │  ◀── fluxgate-sample-redis   │
│  │   (Redis)            │                               │
│  │                      │                               │
│  │  • Token bucket      │                               │
│  │  • Rate limiting     │                               │
│  │  • Rate-limit API    │                               │
│  └──────────────────────┘                               │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/rules?ruleSetId=` | List the rules of a rule set (`ruleSetId` required) |
| GET | `/admin/rules/all` | List all rules |
| GET | `/admin/rules/{ruleSetId}/{id}` | Get one rule; 404 if unknown |
| DELETE | `/admin/rules/{ruleSetId}/{id}` | Delete one rule; 204, or 404 if unknown |
| DELETE | `/admin/rules?ruleSetId=` | Delete every rule of a rule set; 200 with `rulesDeleted` |
| POST | `/admin/rules/sample` | Create the three sample rules; 201 |

Swagger UI: `http://localhost:8081/swagger-ui.html`.

## When to Use This Sample

- **Centralized rule management** with persistent storage
- **Admin backend** for rule configuration
- **Control-plane** in a distributed architecture

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-redis](../fluxgate-sample-redis) - For data-plane functionality
- [fluxgate-sample-api](../fluxgate-sample-api) - For full integration
