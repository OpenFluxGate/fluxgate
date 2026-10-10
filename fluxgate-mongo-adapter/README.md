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
    <version>0.4.0</version>
</dependency>

<!-- MongoDB driver -->
<dependency>
    <groupId>org.mongodb</groupId>
    <artifactId>mongodb-driver-sync</artifactId>
    <version>5.2.1</version>
</dependency>
```

With a FluxGate Spring Boot starter, set `fluxgate.mongo.enabled=true` instead of wiring the classes
below by hand.

## Quick Start

### 1. Configure the collections

```java
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.fluxgate.adapter.mongo.config.FluxgateMongoConfig;

MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");

FluxgateMongoConfig config =
    new FluxgateMongoConfig(
        mongoClient,
        "fluxgate",           // database name
        "rate_limit_rules",   // rule collection name
        "rate_limit_events"); // event collection name
```

### 2. Create the indexes and store rules

```java
import java.time.Duration;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;

MongoRateLimitRuleRepository repository = config.ruleRepository();

// Once per collection (idempotent): the unique (ruleSetId, id) index is required, see below.
repository.ensureIndexes();

RateLimitRule rule =
    RateLimitRule.builder("api-rate-limit")
        .name("API Rate Limit")
        .scope(LimitScope.PER_API_KEY)
        .keyStrategyId("apiKey")
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
        .ruleSetId("my-api-ruleset")
        .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 100).label("per-second").build())
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1_000).label("per-minute").build())
        .build();

repository.save(rule); // inserts or updates the rule ("my-api-ruleset", "api-rate-limit")
```

### 3. Load rule sets

```java
import java.util.Optional;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

RateLimitRuleSetProvider provider = config.ruleSetProvider(yourKeyResolver);

Optional<RateLimitRuleSet> ruleSet = provider.findById("my-api-ruleset");
```

`MongoRuleSetProvider` attaches the rule set's access control (allow/deny lists) through
`RuleSetAccessControlSource`. `MongoRateLimitRuleRepository` implements it; if you wrap the
repository in a decorator, implement the interface on the decorator or pass a source to the
four-argument constructor, otherwise the access control is not loaded. The Spring Boot starters pick
up a `RuleSetAccessControlSource` bean and WARN once when there is none.

### 4. Record events (optional)

```java
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;

MongoRateLimitMetricsRecorder recorder =
    new MongoRateLimitMetricsRecorder(config.eventCollection());

RateLimitRuleSet withEvents =
    RateLimitRuleSet.builder("my-ruleset")
        .keyResolver(keyResolver)
        .rules(rules)
        .metricsRecorder(recorder)
        .build();

// on shutdown: writes what is queued (for up to five seconds), then stops the writer thread
recorder.close();
```

`record()` never blocks a request: it queues the event for a background writer and drops it (counted
by `getDroppedEvents()`) when the queue is full or the recorder is closed.

## Architecture

### Component Structure

```
fluxgate-mongo-adapter/
├── config/
│   └── FluxgateMongoConfig            # Collections and factory methods
├── model/
│   ├── RateLimitRuleDocument          # Rule document model
│   └── RateLimitBandDocument          # Band document model
├── converter/
│   ├── RateLimitRuleConverter         # Domain ↔ DTO conversion
│   ├── RateLimitRuleMongoConverter    # DTO ↔ BSON (and Domain ↔ DTO) conversion
│   └── InvalidRuleDocumentException   # A stored document that cannot be converted
├── repository/
│   └── MongoRateLimitRuleRepository   # Rule CRUD, access control, indexes
├── rule/
│   └── MongoRuleSetProvider           # RateLimitRuleSetProvider implementation
├── spi/
│   └── RuleSetAccessControlSource     # Where the rule set's access control comes from
└── event/
    └── MongoRateLimitMetricsRecorder  # Asynchronous event recording
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

## Rule identity: `(ruleSetId, id)`

A rule is identified by the pair `(ruleSetId, id)`, not by `id` alone: the same rule id may exist in
several rule sets.

| Operation | Behaviour |
|-----------|-----------|
| `save(rule)` | Inserts or updates the document `(rule.ruleSetId, rule.id)`. Saving rule `r1` into rule set B **inserts a second document**; it no longer moves `r1` out of rule set A |
| `moveRule(id, fromRuleSetId, toRuleSetId)` | The explicit move. Returns `false` when the source has no such rule, throws `IllegalStateException` when the target already has one. The moved rule adopts the target rule set's access control |
| `findById(ruleSetId, id)`, `existsById(ruleSetId, id)`, `deleteById(ruleSetId, id)` | Address exactly one rule |
| `findByRuleSetId(ruleSetId)`, `deleteByRuleSetId(ruleSetId)` | Every rule of one rule set |

### Deprecated id-only methods

`findById(id)`, `existsById(id)` and `deleteById(id)` come from `RateLimitRuleRepository` and are
`@Deprecated`; `upsert(RateLimitRuleDocument)` and `findDocumentsByRuleSetId` are deprecated too
(use `save` and `findByRuleSetId`). For an id that exists in several rule sets:

| Method | Result |
|--------|--------|
| `findById(id)` | throws `IllegalStateException` (which rule would it be?) |
| `deleteById(id)` | throws `IllegalStateException` and deletes nothing |
| `existsById(id)` | returns `true` — it is a yes/no question |

Move to the `(ruleSetId, id)` overloads.

## MongoDB Schema

### Rate Limit Rule Collection

```json
{
  "_id": ObjectId("..."),
  "id": "api-rate-limit",
  "name": "API Rate Limit",
  "enabled": true,
  "scope": "PER_API_KEY",
  "keyStrategyId": "apiKey",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "ruleSetId": "my-api-ruleset",
  "bands": [
    { "windowSeconds": 1, "capacity": 100, "label": "per-second", "algorithm": "TOKEN_BUCKET" },
    { "windowSeconds": 60, "capacity": 1000, "label": "per-minute", "algorithm": "TOKEN_BUCKET" }
  ],
  "allowedIps": ["192.168.0.0/16"],
  "deniedKeys": ["user:blocked"]
}
```

Each band always stores `algorithm` (`TOKEN_BUCKET` by default). The other 0.4 band fields are
written only when they differ from the defaults: `quotaPeriod` (absent: no calendar alignment),
`zoneId` (absent: UTC — omitted under any UTC spelling such as `Z`, `UTC` or `Etc/UTC`; older
documents that stored `Z` still load) and `slidingWindowBuckets` (absent: 10). See the
[sample-mongo field table](../fluxgate-samples/fluxgate-sample-mongo/README.md#ratelimitband-fields).

Optional fields (`attributes`, `methods`, `pathPatterns`, ...) are omitted when empty. The rule set's
access control (`allowedIps`, `deniedIps`, `allowedKeys`, `deniedKeys`) is embedded on every rule
document of the rule set; write it with `saveAccessControl(...)` after the rules exist. Every write
of it (`saveAccessControl`, inserting a new rule, `moveRule`) also sets the marker field
`aclUpdatedAt`; empty lists are removed. Only the marker's presence matters: `saveAccessControl` and
`moveRule` write the server time (`$currentDate`), the insert of a new rule writes the client time
(`$currentDate` cannot be used in `$setOnInsert`). Reads merge every copy - every document of the rule set
that carries `aclUpdatedAt` or a non-empty list - fail-closed: union of each deny list, intersection
of each allow list, where a copy without that list counts as empty. A document with neither marker
nor list (written by 0.3.x or by hand) holds no access control and is ignored. Copies diverge only
when a `saveAccessControl` was interrupted; an interrupted clear therefore leaves an empty allow
list, never the revoked one. Allow lists only grant a bypass, so copies with no entry in common (or
one copy lacking the list) merge to an empty allow list that grants no bypass, logged at WARN with
the rule set id; call `saveAccessControl` again to repair them.

### Required indexes

The repository needs a **unique index on `{ruleSetId: 1, id: 1}`**. Without it two concurrent first
saves of the same rule can create duplicates, and a rule set becomes ambiguous.
`MongoRateLimitRuleRepository#ensureIndexes()` creates, idempotently:

| Index | Keys | Purpose |
|-------|------|---------|
| `ruleSetId_1_id_1_unique` (unique) | `{ruleSetId: 1, id: 1}` | Rule set loads and the identity of every write |
| `id_1` | `{id: 1}` | The deprecated id-only lookups |

It fails loudly: duplicated `(ruleSetId, id)` pairs or a conflicting index throw
`IllegalStateException`, listing up to ten duplicated pairs to clean up first. To create the index by
hand:

```javascript
db.rate_limit_rules.createIndex({ ruleSetId: 1, id: 1 }, { unique: true, name: "ruleSetId_1_id_1_unique" })
```

With a Spring Boot starter:

| `fluxgate.mongo.ddl-auto` | Behaviour |
|---------------------------|-----------|
| `validate` (default) | Fails startup unless the collections exist and a unique index on `{ruleSetId: 1, id: 1}` exists (any name) |
| `create` | Creates missing collections and calls `ensureIndexes()`; duplicates fail startup |

`create` is a convenience for development. In production, create the collections and indexes with your
migration tooling and keep `validate`.

### Error semantics

A failing query no longer looks like "no rules exist". That distinction is the point:

| Situation | Result |
|-----------|--------|
| Socket error, timeout, or not-primary | `org.fluxgate.core.exception.MongoConnectionException` |
| Any other `MongoException` | `FluxgateOperationException`, marked retryable |
| Stored access control is invalid (bad CIDR) | `FluxgateOperationException`, not retryable |
| Query succeeded and matched nothing | `Optional.empty()`, WARNed once per rule set id |

Before this, a connection failure returned `Optional.empty()`, which the engine read as "no rule set"
and turned into a global allow or a global deny depending on
`fluxgate.ratelimit.missing-rule-behavior`. An outage silently became an availability or a security
incident, with nothing in the logs to say which.

`MongoRuleSetProvider` WARNs **once per rule set id** for a genuinely missing rule set, then falls
silent, so a misconfigured id is visible without flooding the log on the hot path.

### Rate Limit Event Collection

```json
{
  "_id": ObjectId("..."),
  "timestamp": 1701234567890,
  "timestampIso": "2023-11-29T05:09:27.890Z",
  "createdAt": ISODate("2023-11-29T05:09:27.890Z"),
  "allowed": true,
  "remainingTokens": 95,
  "nanosToWaitForRefill": 0,
  "retryAfterMs": 0,
  "ruleSetId": "my-api-ruleset",
  "ruleId": "api-rate-limit",
  "ruleName": "API Rate Limit",
  "onLimitExceedPolicy": "REJECT_REQUEST",
  "keyStrategyId": "apiKey",
  "endpoint": "/api/users",
  "method": "GET",
  "clientIp": "192.168.1.100",
  "userId": "user-456",
  "apiKey": "sha256:1f2e3d4c5b6a7980",
  "headers": { "X-Request-Id": "abc" },
  "attributes": { "tenant%2Eid": "t-1" }
}
```

- The API key is never stored in clear: `apiKey` is `sha256:` plus the first 16 hex characters of its
  SHA-256. `userId` can be fingerprinted the same way (`new MongoRateLimitMetricsRecorder(collection,
  true)`).
- `createdAt` is a BSON date for a TTL index (the starters create one, `fluxgate.mongo.event-retention`,
  30 days by default).
- Header and attribute names are BSON field names, so `.`, `$` and NUL are percent-escaped (`%2E`,
  `%24`, `%00`), `%` itself becomes `%25`, and an empty name is stored as `%`. The escape is
  reversible, so `a.b` and `a_b` stay distinct fields.

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

`./mvnw test` does not run the integration tests; they are the `*IntegrationTest` classes in
`src/test/java/org/fluxgate/adapter/mongo`.

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
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;

// KeyResolver takes (context, rule) - context first.
KeyResolver customResolver =
    (RequestContext context, RateLimitRule matchedRule) -> {
      String ip = context.getClientIp();
      ip = ip != null && !ip.isEmpty() ? ip : "unknown";
      String apiKey = context.getApiKey();
      if (apiKey == null || apiKey.isEmpty()) {
        return RateLimitKey.of("ip:", ip); // "ip:10.0.0.1"
      }
      // Prefixed form: the prefix survives sanitisation; the free-form API key goes last.
      return RateLimitKey.of("ip:", ip + ":key:" + apiKey); // "ip:10.0.0.1:key:abc"
    };

RateLimitRuleSetProvider provider = config.ruleSetProvider(customResolver);
```

### Query events

```java
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bson.Document;

MongoCollection<Document> events = config.eventCollection();

// Rejected requests in the last hour
long oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS).toEpochMilli();
for (Document event :
    events.find(Filters.and(Filters.eq("allowed", false), Filters.gte("timestamp", oneHourAgo)))) {
  System.out.println("Rejected: " + event.getString("clientIp"));
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
