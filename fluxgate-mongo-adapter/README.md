# FluxGate MongoDB Adapter

MongoDB persistence adapter for FluxGate rate limiting rules.

## Overview

`fluxgate-mongo-adapter` provides MongoDB-based storage and retrieval for FluxGate rate limiting rules. It implements
the `RateLimitRuleSetProvider` interface, allowing you to store and manage rate limiting configurations in MongoDB.

## Features

- **MongoDB Persistence**: Store rate limiting rules in MongoDB
- **Automatic Conversion**: Seamless conversion between Domain models and MongoDB documents
- **Metrics Recording**: Track rate limit events to MongoDB for analysis
- **Full CRUD Support**: Create, Read, Update, and Delete operations
- **Well-Tested**: Comprehensive unit and integration tests

## Installation

Add the dependency to your `pom.xml`:

```xml

<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>0.1.4</version>
</dependency>

        <!-- MongoDB Driver -->
<dependency>
<groupId>org.mongodb</groupId>
<artifactId>mongodb-driver-sync</artifactId>
<version>5.2.1</version>
</dependency>
```

## Quick Start

### 1. Setup MongoDB Configuration

```java
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.fluxgate.adapter.mongo.config.FluxgateMongoConfig;

// Create MongoDB client
MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");

        // Configure FluxGate MongoDB adapter
        FluxgateMongoConfig config = new FluxgateMongoConfig(
                mongoClient,
                "fluxgate",              // database name
                "rate_limit_rules",      // rule collection name
                "rate_limit_events"      // event collection name
        );
```

### 2. Create and Store Rate Limiting Rules

```java
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;

MongoRateLimitRuleRepository repository = config.ruleRepository();

// Create a rate limit band: 100 requests per second
RateLimitBandDocument band = new RateLimitBandDocument(
        1L,        // window in seconds
        100L,      // capacity
        "per-second"
);

// Create a rate limit rule
RateLimitRuleDocument rule = new RateLimitRuleDocument(
        "api-rate-limit",
        "API Rate Limit",
        true,                                   // enabled
        LimitScope.PER_API_KEY,
        "apiKey",
        OnLimitExceedPolicy.REJECT_REQUEST,
        List.of(band),
        "my-api-ruleset"                       // ruleset ID
);

// Store in MongoDB
repository.

upsert(rule);
```

### 3. Load Rules from MongoDB

```java
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;

// Create provider with a key resolver
RateLimitRuleSetProvider provider = config.ruleSetProvider(yourKeyResolver);

        // Load ruleset from MongoDB
        Optional<RateLimitRuleSet> ruleSet = provider.findById("my-api-ruleset");

if(ruleSet.

        isPresent()){
        // Use the ruleset with RateLimitEngine
        RateLimitEngine engine = RateLimitEngine.builder()
                .ruleSetProvider(provider)
                .rateLimiter(new Bucket4jRateLimiter())
                .build();
}
```

### 4. Record Metrics (Optional)

```java
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;

RateLimitMetricsRecorder metricsRecorder =
        new MongoRateLimitMetricsRecorder(config.eventCollection());

// Use with RuleSet
RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("my-ruleset")
        .keyResolver(keyResolver)
        .rules(rules)
        .metricsRecorder(metricsRecorder)  // Record to MongoDB
        .build();
```

## Architecture

### Component Structure

```
fluxgate-mongo-adapter/
├── config/
│   └── FluxgateMongoConfig        # MongoDB configuration and factory
├── model/
│   ├── RateLimitRuleDocument      # Rule document model
│   └── RateLimitBandDocument      # Band document model
├── converter/
│   └── RateLimitRuleMongoConverter # Domain ↔ DTO ↔ BSON conversion
├── repository/
│   └── MongoRateLimitRuleRepository # MongoDB CRUD operations
├── rule/
│   └── MongoRuleSetProvider        # RuleSetProvider implementation
└── event/
    └── MongoRateLimitMetricsRecorder # Metrics recording
```

### Data Flow

```
Domain (RateLimitRule)
    ↓ Converter
DTO (RateLimitRuleDocument)
    ↓ Converter
BSON (MongoDB Document)
    ↓ Repository
MongoDB
```

## MongoDB Schema

### Rate Limit Rule Collection

```json
{
  "_id": ObjectId(
  "..."
  ),
  "id": "api-rate-limit",
  "name": "API Rate Limit",
  "enabled": true,
  "scope": "PER_API_KEY",
  "keyStrategyId": "apiKey",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "ruleSetId": "my-api-ruleset",
  "bands": [
    {
      "windowSeconds": 1,
      "capacity": 100,
      "label": "per-second"
    },
    {
      "windowSeconds": 60,
      "capacity": 1000,
      "label": "per-minute"
    }
  ]
}
```

There is no `path`, `method` or `priority` field: a rule has a scope and bands, and every enabled rule
of a rule set is evaluated. See [Engine Layer](../docs/en/architecture/engine-layer.md).

### Indexes

`fluxgate.mongo.ddl-auto=create` creates the collections **and** these indexes:

```javascript
db.rate_limit_rules.createIndex({ "ruleSetId": 1 })
db.rate_limit_rules.createIndex({ "ruleSetId": 1, "id": 1 }, { unique: true })
```

The first serves the only query on the hot path (`findByRuleSetId`); the second makes a duplicate rule
id inside one rule set a write error rather than a silently shadowed rule.

Index creation failures are logged at WARN and are **not** fatal — a replica set that is mid-election,
or a user without `createIndex` rights, should not stop the application from starting. Check the log
on first start and create the indexes manually if you run with `ddl-auto=validate`:

| `ddl-auto` | Behaviour |
|------------|-----------|
| `validate` (default) | Verifies the collections exist. Throws if one is missing. Creates no indexes |
| `create` | Creates missing collections, then the two indexes above |

`create` is a convenience for development. In production, create the collections and indexes with your
migration tooling and keep `validate`.

### Error semantics

A failing query no longer looks like "no rules exist". That distinction is the point:

| Situation | Result |
|-----------|--------|
| Socket error, timeout, or not-primary | `org.fluxgate.core.exception.MongoConnectionException` |
| Any other `MongoException` | `FluxgateOperationException`, marked retryable |
| Query succeeded and matched nothing | `Optional.empty()`, WARNed once per rule set id |

Before this, a connection failure returned `Optional.empty()`, which the engine read as "no rule set"
and turned into a global allow or a global deny depending on
`fluxgate.ratelimit.missing-rule-behavior`. An outage silently became an availability or a security
incident, with nothing in the logs to say which.

`MongoRuleSetProvider` WARNs **once per rule set id** for a genuinely missing rule set, then falls
silent, so a misconfigured id is visible without flooding the log on the hot path.

### Rate Limit Event Collection (Metrics)

```json
{
  "_id": ObjectId(
  "..."
  ),
  "timestamp": 1701234567890,
  "allowed": true,
  "remainingTokens": 95,
  "nanosToWaitForRefill": 0,
  "ruleSetId": "my-api-ruleset",
  "ruleId": "api-rate-limit",
  "endpoint": "/api/users",
  "method": "GET",
  "clientIp": "192.168.1.100",
  "attributes": {
    "apiKey": "key-123",
    "userId": "user-456"
  }
}
```

## Repository Operations

### Upsert (Insert or Update)

```java
repository.upsert(ruleDocument);  // Creates or updates by ID
```

### Find by RuleSet ID

```java
List<RateLimitRuleDocument> rules = repository.findByRuleSetId("my-ruleset");
```

### Delete by ID

```java
repository.deleteById("api-rate-limit");
```

## Multi-Band Rate Limiting

Configure multiple time windows for a single rule:

```java
List<RateLimitBandDocument> bands = List.of(
        new RateLimitBandDocument(1L, 10L, "10 per second"),
        new RateLimitBandDocument(60L, 100L, "100 per minute"),
        new RateLimitBandDocument(3600L, 1000L, "1000 per hour")
);

RateLimitRuleDocument rule = new RateLimitRuleDocument(
        "multi-band-rule",
        "Multi-Band Rate Limit",
        true,
        LimitScope.PER_API_KEY,
        "apiKey",
        OnLimitExceedPolicy.REJECT_REQUEST,
        bands,
        "multi-band-ruleset"
);

repository.

upsert(rule);
```

## Testing

FluxGate has two test tiers, so a clean checkout tests fully with no infrastructure.

### Unit tier — no MongoDB, no Docker

```bash
./mvnw test -pl fluxgate-mongo-adapter
```

### Integration tier

```bash
# Unit tier + integration tier (failsafe runs *IntegrationTest / *IT)
./mvnw verify -pl fluxgate-mongo-adapter

# Integration tier skipped explicitly
./mvnw verify -pl fluxgate-mongo-adapter -DskipITs

# One class
./mvnw verify -pl fluxgate-mongo-adapter -Dit.test=MongoRuleSetProviderIntegrationTest
```

`./mvnw test` no longer runs the integration tests. This module's integration classes are
`MongoRuleSetProviderIntegrationTest` and `MongoRateLimitMetricsRecorderIntegrationTest`.

### How the integration tier finds MongoDB

In this order:

1. a URI supplied through the environment or a system property,
2. a disposable [Testcontainers](https://testcontainers.com/) `mongo:7.0`,
3. **skip** — a JUnit assumption aborts the test. Integration tests never *fail* for want of a
   database.

| Variable | Purpose |
|----------|---------|
| `FLUXGATE_MONGO_URI` (or `-Dfluxgate.mongo.uri`) | Use an existing MongoDB |
| `FLUXGATE_MONGO_DB` (or `-Dfluxgate.mongo.db`) | Database name to use with that URI |

```bash
FLUXGATE_MONGO_URI='mongodb://fluxgate:secret@localhost:27017/fluxgate?authSource=admin' \
FLUXGATE_MONGO_DB=fluxgate \
  ./mvnw verify -pl fluxgate-mongo-adapter
```

Local MongoDB if you want it — this compose file binds the port to `127.0.0.1` and is labelled local
development only:

```bash
docker compose -f ../docker/mongo.yml up -d
```

### Pointing the tests at a shared database is safe

Each test creates its **own** uniquely named collection and drops only that collection in
`@AfterEach`. No test drops a collection it did not create, and no test issues
`deleteMany(new Document())`. Earlier versions called `ruleCollection.drop()` against `localhost` with
no guard, which destroyed developer and CI data.

## Advanced Usage

### Custom KeyResolver

```java
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.context.RequestContext;

// KeyResolver takes (context, rule) - context first.
KeyResolver customResolver = (RequestContext context, RateLimitRule rule) -> {
    String apiKey = context.getApiKey();
    String clientIp = context.getClientIp();
    // Prefix each component so the parts stay unambiguous after sanitisation.
    return RateLimitKey.of("key:" + apiKey + ":ip:" + clientIp);
};

RateLimitRuleSetProvider provider = config.ruleSetProvider(customResolver);
```

### Query Metrics

```java
import com.mongodb.client.MongoCollection;
import org.bson.Document;

MongoCollection<Document> events = config.eventCollection();

// Find rejected requests in the last hour
long oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();

for(
Document event :events.

find(
        Filters.and(
                Filters.eq("allowed", false),
        Filters.

gte("timestamp",oneHourAgo)
    )
            )){
            System.out.

println("Rejected: "+event.getString("clientIp"));
        }
```

## Dependencies

- **fluxgate-core**: Core rate limiting engine
- **mongodb-driver-sync**: MongoDB Java driver (5.2.1+)
- **slf4j-api**: Logging facade

## Requirements

- Java 11+
- MongoDB 4.0+
- Maven 3.8+

## License

Licensed under the MIT License. See [LICENSE](../LICENSE) for details.

## Related Projects

- [fluxgate-core](../fluxgate-core/README.md) - Core rate limiting engine
- [fluxgate-redis-ratelimiter](../fluxgate-redis-ratelimiter/README.md) - Distributed bucket storage
- [fluxgate-spring-boot3-starter](../fluxgate-spring-boot3-starter/README.md) - Auto-configuration
- [Storage Layer](../docs/en/architecture/storage-layer.md) - How this module fits the architecture
- [FluxGate](../README.md) - Parent project

## Contributing

Contributions are welcome! Please read the contributing guidelines in the main project.
