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
    <groupId>org.fluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
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

### Run Unit Tests

```bash
mvn test -pl fluxgate-mongo-adapter
```

### Run Integration Tests

Integration tests require a running MongoDB instance:

```bash
# Using Docker
docker run -d -p 27017:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=fluxgate \
  -e MONGO_INITDB_ROOT_PASSWORD=fluxgate123 \
  mongo:latest

# Run tests
mvn test -pl fluxgate-mongo-adapter
```

### Configure MongoDB Connection

Set MongoDB connection via environment variables or system properties:

```bash
# Environment variables
export FLUXGATE_MONGO_URI="mongodb://localhost:27017/fluxgate"
export FLUXGATE_MONGO_DB="fluxgate"

# Or system properties
mvn test -Dfluxgate.mongo.uri="mongodb://localhost:27017/fluxgate" \
         -Dfluxgate.mongo.db="fluxgate"
```

## Advanced Usage

### Custom KeyResolver

```java
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.context.RequestContext;

KeyResolver customResolver = (RequestContext context) -> {
    String apiKey = context.getApiKey();
    String clientIp = context.getClientIp();
    return new RateLimitKey(apiKey + ":" + clientIp);
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

- Java 21+
- MongoDB 4.0+
- Maven 3.8+

## License

Licensed under the MIT License. See [LICENSE](../LICENSE) for details.

## Related Projects

- [fluxgate-core](../fluxgate-core) - Core rate limiting engine
- [FluxGate](../) - Parent project

## Contributing

Contributions are welcome! Please read the contributing guidelines in the main project.
