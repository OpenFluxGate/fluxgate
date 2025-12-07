# FluxGate TestKit

Testing utilities and integration test support for FluxGate rate limiting framework.

## Overview

FluxGate TestKit provides:

- **Integration test base classes** with pre-configured test containers
- **Test utilities** for rate limiter verification
- **Mock implementations** for unit testing
- **Sample test configurations** for different scenarios

## Dependencies

Add the testkit dependency to your test scope:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-testkit</artifactId>
    <version>${fluxgate.version}</version>
    <scope>test</scope>
</dependency>
```

## Test Containers Support

The testkit uses [Testcontainers](https://www.testcontainers.org/) for integration testing with real Redis and MongoDB instances.

### Redis Integration Tests

```java
@Testcontainers
class RedisRateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        String redisUri = String.format("redis://%s:%d",
                redis.getHost(), redis.getMappedPort(6379));

        RedisRateLimiterConfig config = new RedisRateLimiterConfig(redisUri);
        rateLimiter = new RedisRateLimiter(config.tokenBucketStore());
    }

    @Test
    void shouldEnforceRateLimit() {
        // Given
        RateLimitRuleSet ruleSet = createRuleSet(10, Duration.ofMinutes(1));
        RequestContext context = RequestContext.builder()
                .clientIp("192.168.1.1")
                .build();

        // When - consume all tokens
        for (int i = 0; i < 10; i++) {
            RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
            assertThat(result.isAllowed()).isTrue();
        }

        // Then - next request should be rejected
        RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);
        assertThat(result.isAllowed()).isFalse();
    }
}
```

### MongoDB Integration Tests

```java
@Testcontainers
class MongoRuleStoreIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:6.0");

    private MongoRuleSetStore ruleStore;

    @BeforeEach
    void setUp() {
        MongoClient client = MongoClients.create(mongo.getConnectionString());
        MongoDatabase database = client.getDatabase("fluxgate-test");
        ruleStore = new MongoRuleSetStore(database.getCollection("rules"));
    }

    @Test
    void shouldSaveAndRetrieveRule() {
        // Given
        RateLimitRule rule = RateLimitRule.builder("test-rule")
                .name("Test Rule")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())
                .build();

        // When
        ruleStore.save(rule);
        Optional<RateLimitRule> retrieved = ruleStore.findById("test-rule");

        // Then
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getName()).isEqualTo("Test Rule");
    }
}
```

## Running Tests

### Run All Tests

```bash
# From project root
./mvnw test -pl fluxgate-testkit

# With verbose output
./mvnw test -pl fluxgate-testkit -Dtest.verbose=true
```

### Run Integration Tests Only

```bash
./mvnw verify -pl fluxgate-testkit -DskipUnitTests
```

### Run Specific Test Class

```bash
./mvnw test -pl fluxgate-testkit -Dtest=RedisRateLimiterIntegrationTest
```

## Test Categories

Tests are organized using JUnit 5 tags:

| Tag | Description |
|-----|-------------|
| `@Tag("unit")` | Fast unit tests (no external dependencies) |
| `@Tag("integration")` | Integration tests (require test containers) |
| `@Tag("slow")` | Long-running tests (performance, stress) |

### Running Tests by Category

```bash
# Unit tests only
./mvnw test -pl fluxgate-testkit -Dgroups=unit

# Integration tests only
./mvnw test -pl fluxgate-testkit -Dgroups=integration

# Exclude slow tests
./mvnw test -pl fluxgate-testkit -DexcludedGroups=slow
```

## Test Utilities

### RateLimiterTestHelper

Utility class for common test operations:

```java
public class RateLimiterTestHelper {

    /**
     * Creates a simple rule set for testing.
     */
    public static RateLimitRuleSet createRuleSet(long capacity, Duration window) {
        RateLimitRule rule = RateLimitRule.builder("test-rule")
                .name("Test Rule")
                .enabled(true)
                .scope(LimitScope.PER_IP)
                .addBand(RateLimitBand.builder(window, capacity).build())
                .build();

        return RateLimitRuleSet.builder("test-ruleset")
                .rules(List.of(rule))
                .keyResolver(ctx -> new RateLimitKey(ctx.getClientIp()))
                .build();
    }

    /**
     * Consumes all available tokens.
     */
    public static void exhaustTokens(RateLimiter limiter,
                                      RequestContext context,
                                      RateLimitRuleSet ruleSet) {
        RateLimitResult result;
        do {
            result = limiter.tryConsume(context, ruleSet, 1);
        } while (result.isAllowed());
    }

    /**
     * Verifies rate limit is enforced after capacity exhausted.
     */
    public static void assertRateLimitEnforced(RateLimiter limiter,
                                                RequestContext context,
                                                RateLimitRuleSet ruleSet,
                                                int expectedCapacity) {
        // Consume expected capacity
        for (int i = 0; i < expectedCapacity; i++) {
            RateLimitResult result = limiter.tryConsume(context, ruleSet, 1);
            assertThat(result.isAllowed())
                    .withFailMessage("Request %d should be allowed", i + 1)
                    .isTrue();
        }

        // Next request should be rejected
        RateLimitResult result = limiter.tryConsume(context, ruleSet, 1);
        assertThat(result.isAllowed())
                .withFailMessage("Request after capacity should be rejected")
                .isFalse();
    }
}
```

### MockRateLimiter

A mock implementation for unit testing without Redis:

```java
public class MockRateLimiter implements RateLimiter {

    private boolean allowAll = true;
    private long remainingTokens = 100;
    private final List<RateLimitResult> recordedResults = new ArrayList<>();

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                       RateLimitRuleSet ruleSet,
                                       long tokens) {
        RateLimitResult result;
        if (allowAll) {
            remainingTokens -= tokens;
            result = RateLimitResult.allowed(remainingTokens);
        } else {
            result = RateLimitResult.rejected(0, Duration.ofSeconds(60).toNanos());
        }
        recordedResults.add(result);
        return result;
    }

    public void setAllowAll(boolean allowAll) {
        this.allowAll = allowAll;
    }

    public List<RateLimitResult> getRecordedResults() {
        return Collections.unmodifiableList(recordedResults);
    }
}
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Integration Tests

on: [push, pull_request]

jobs:
  integration-tests:
    runs-on: ubuntu-latest

    services:
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379

      mongo:
        image: mongo:6.0
        ports:
          - 27017:27017

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Run Integration Tests
        run: ./mvnw verify -pl fluxgate-testkit
```

### Docker Compose for Local Testing

```yaml
# docker-compose.test.yml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  mongodb:
    image: mongo:6.0
    ports:
      - "27017:27017"
    environment:
      MONGO_INITDB_ROOT_USERNAME: admin
      MONGO_INITDB_ROOT_PASSWORD: admin
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
      interval: 5s
      timeout: 3s
      retries: 5
```

```bash
# Start test infrastructure
docker-compose -f docker-compose.test.yml up -d

# Run tests
./mvnw verify -pl fluxgate-testkit

# Stop infrastructure
docker-compose -f docker-compose.test.yml down
```

## Writing New Tests

### Test Naming Convention

Follow the pattern: `should<ExpectedBehavior>When<Condition>`

```java
@Test
void shouldRejectRequestWhenRateLimitExceeded() { }

@Test
void shouldAllowRequestWhenTokensAvailable() { }

@Test
void shouldRefillTokensAfterWindowExpires() { }
```

### Test Structure (AAA Pattern)

```java
@Test
void shouldRejectRequestWhenRateLimitExceeded() {
    // Arrange (Given)
    RateLimitRuleSet ruleSet = createRuleSet(10, Duration.ofMinutes(1));
    RequestContext context = createContext("192.168.1.1");
    exhaustTokens(rateLimiter, context, ruleSet);

    // Act (When)
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

    // Assert (Then)
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getRemainingTokens()).isZero();
}
```

## Troubleshooting

### Test Containers Not Starting

```bash
# Check Docker is running
docker info

# Check for port conflicts
lsof -i :6379
lsof -i :27017

# Increase Docker memory (for Mac/Windows)
# Docker Desktop > Settings > Resources > Memory
```

### Flaky Tests

- Use `@RepeatedTest` to identify intermittent failures
- Add proper waits for async operations
- Isolate tests with unique keys per test method

### Slow Test Execution

- Use parallel test execution:
  ```xml
  <configuration>
      <parallel>methods</parallel>
      <threadCount>4</threadCount>
  </configuration>
  ```

- Reuse containers across tests with `@Container` at class level

## Related Documentation

- [FluxGate Core](../fluxgate-core/README.md)
- [Integration Test Guide](../INTEGRATION_TEST_GUIDE.md)
- [Contributing Guide](../CONTRIBUTING.md)
