# FluxGate Sample - MongoDB (Control-plane)

This sample demonstrates **MongoDB-based rule management** for rate limiting. It represents the **control-plane** component that handles rule storage, CRUD operations, and administrative functions.

## Key Features

- **Persistent rule storage** - Rules stored in MongoDB
- **REST API for rule management** - Full CRUD operations
- **Rule versioning** - Track rule changes over time
- **Event logging** - Audit trail for rate limit events
- **Testkit integration** - Easy testing with embedded MongoDB

## Prerequisites

```bash
# Start MongoDB
docker run -d --name mongodb -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:latest
```

## Quick Start

### 1. Run the Application

```bash
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-mongo
```

The application starts on port **8081**.

### 2. Create Sample Rules

```bash
# Initialize sample rules
curl -X POST http://localhost:8081/admin/rules/init
```

### 3. List All Rules

```bash
curl http://localhost:8081/admin/rules
```

Response:
```json
{
  "rules": [
    {
      "id": "api-rate-limit",
      "name": "API Rate Limit",
      "ruleSetId": "api-limits",
      "enabled": true,
      "capacity": 10,
      "windowSeconds": 60
    }
  ],
  "count": 1
}
```

## Project Structure

```
fluxgate-sample-mongo/
├── src/main/java/org/fluxgate/sample/mongo/
│   ├── MongoSampleApplication.java     # Main application
│   ├── config/
│   │   └── MongoConfig.java            # MongoDB configuration
│   └── controller/
│       └── RuleAdminController.java    # REST API for rules
└── src/main/resources/
    └── application.yml                 # Configuration
```

## Configuration

### application.yml

```yaml
server:
  port: 8081

fluxgate:
  mongo:
    enabled: true
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: rate_limit_rules
    event-collection: rate_limit_events
  redis:
    enabled: false
  ratelimit:
    filter-enabled: false  # No rate limiting in control-plane
```

## REST API

### Rules Management

#### List All Rules

```bash
curl http://localhost:8081/admin/rules
```

#### Get Rule by ID

```bash
curl http://localhost:8081/admin/rules/{ruleId}
```

#### Create Rule

```bash
curl -X POST http://localhost:8081/admin/rules \
  -H "Content-Type: application/json" \
  -d '{
    "id": "premium-api-limit",
    "name": "Premium API Rate Limit",
    "ruleSetId": "premium-limits",
    "enabled": true,
    "capacity": 100,
    "windowSeconds": 60,
    "scope": "PER_USER",
    "keyStrategyId": "userId"
  }'
```

#### Update Rule

```bash
curl -X PUT http://localhost:8081/admin/rules/{ruleId} \
  -H "Content-Type: application/json" \
  -d '{
    "capacity": 200,
    "windowSeconds": 60
  }'
```

#### Delete Rule

```bash
curl -X DELETE http://localhost:8081/admin/rules/{ruleId}
```

#### Initialize Sample Rules

```bash
curl -X POST http://localhost:8081/admin/rules/init
```

### RuleSets Management

#### List All RuleSets

```bash
curl http://localhost:8081/admin/rulesets
```

#### Get RuleSet

```bash
curl http://localhost:8081/admin/rulesets/{ruleSetId}
```

## MongoDB Collections

### rate_limit_rules

Stores rate limiting rules:

```json
{
  "_id": "api-rate-limit",
  "name": "API Rate Limit",
  "ruleSetId": "api-limits",
  "enabled": true,
  "scope": "PER_IP",
  "keyStrategyId": "clientIp",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "bands": [
    {
      "label": "10-per-minute",
      "capacity": 10,
      "windowMs": 60000
    }
  ],
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:00Z",
  "version": 1
}
```

### rate_limit_events

Stores rate limit events for auditing:

```json
{
  "_id": "evt-123456",
  "ruleId": "api-rate-limit",
  "key": "192.168.1.100",
  "allowed": false,
  "remaining": 0,
  "resetAt": "2024-01-01T00:01:00Z",
  "timestamp": "2024-01-01T00:00:30Z"
}
```

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
│  │ - _id (String)          # Rule ID              │   │
│  │ - name (String)         # Display name         │   │
│  │ - ruleSetId (String)    # Group identifier     │   │
│  │ - enabled (Boolean)     # Active flag          │   │
│  │ - scope (String)        # PER_IP, PER_USER     │   │
│  │ - keyStrategyId (String)# Key extraction       │   │
│  │ - bands (Array)         # Rate limit bands     │   │
│  │ - createdAt (DateTime)  # Creation timestamp   │   │
│  │ - updatedAt (DateTime)  # Last update          │   │
│  │ - version (Integer)     # Optimistic locking   │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │ Collection: rate_limit_events                   │   │
│  │                                                 │   │
│  │ - _id (ObjectId)        # Event ID             │   │
│  │ - ruleId (String)       # Associated rule      │   │
│  │ - key (String)          # Rate limit key       │   │
│  │ - allowed (Boolean)     # Was request allowed  │   │
│  │ - remaining (Integer)   # Tokens remaining     │   │
│  │ - timestamp (DateTime)  # Event timestamp      │   │
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
| `scope` | Enum | `PER_IP`, `PER_USER`, `GLOBAL` |
| `keyStrategyId` | String | Key extraction strategy |
| `onLimitExceedPolicy` | Enum | `REJECT_REQUEST`, `QUEUE`, `LOG_ONLY` |
| `bands` | Array | Rate limit bands |

### RateLimitBand Fields

| Field | Type | Description |
|-------|------|-------------|
| `label` | String | Band identifier |
| `capacity` | Long | Max tokens |
| `windowMs` | Long | Window in milliseconds |

## Testing with FluxGate Testkit

```java
@SpringBootTest
@AutoConfigureFluxgateMongo  // From fluxgate-testkit
class RuleAdminControllerTest {

    @Autowired
    private RateLimitRuleRepository ruleRepository;

    @Test
    void shouldCreateRule() {
        // Test with embedded MongoDB
        RateLimitRule rule = createTestRule();
        ruleRepository.save(rule);

        assertThat(ruleRepository.findById(rule.getId()))
            .isPresent();
    }
}
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `fluxgate.mongo.enabled` | `false` | Enable MongoDB |
| `fluxgate.mongo.uri` | - | MongoDB connection URI |
| `fluxgate.mongo.database` | `fluxgate` | Database name |
| `fluxgate.mongo.rule-collection` | `rate_limit_rules` | Rules collection |
| `fluxgate.mongo.event-collection` | `rate_limit_events` | Events collection |

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
│  │  • CRUD APIs         │                               │
│  │  • Admin dashboard   │                               │
│  │  • Event logging     │                               │
│  └──────────┬───────────┘                               │
│             │                                            │
│             │ Sync rules                                 │
│             ▼                                            │
│  ┌──────────────────────┐                               │
│  │   Data-plane         │  ◀── fluxgate-sample-redis   │
│  │   (Redis)            │                               │
│  │                      │                               │
│  │  • Token bucket      │                               │
│  │  • Rate limiting     │                               │
│  │  • HTTP filter       │                               │
│  └──────────────────────┘                               │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/rules` | List all rules |
| GET | `/admin/rules/{id}` | Get rule by ID |
| POST | `/admin/rules` | Create rule |
| PUT | `/admin/rules/{id}` | Update rule |
| DELETE | `/admin/rules/{id}` | Delete rule |
| POST | `/admin/rules/init` | Initialize sample rules |
| GET | `/admin/rulesets` | List all RuleSets |
| GET | `/admin/rulesets/{id}` | Get RuleSet |

## When to Use This Sample

- **Centralized rule management** with persistent storage
- **Admin dashboard backend** for rule configuration
- **Audit trail** requirements for rate limit events
- **Control-plane** in a distributed architecture

## Next Steps

- [FluxGate Samples Overview](../README.md)
- [fluxgate-sample-redis](../fluxgate-sample-redis) - For data-plane functionality
- [fluxgate-sample-api](../fluxgate-sample-api) - For full integration
