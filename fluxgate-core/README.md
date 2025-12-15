# FluxGate Core 🚀

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](../LICENSE)
[![Bucket4j](https://img.shields.io/badge/Bucket4j-8.15.0-purple.svg)](https://github.com/bucket4j/bucket4j)

A **framework-agnostic**, **storage-agnostic** rate limiting engine for modern Java applications. Built on top of [Bucket4j](https://github.com/bucket4j/bucket4j), FluxGate Core provides a clean, extensible abstraction layer for implementing sophisticated rate limiting strategies without coupling to specific HTTP frameworks or storage backends.

---

## 🎯 Why FluxGate Core?

### The Problem

Rate limiting is critical for protecting APIs from abuse, managing resource consumption, and ensuring fair usage across clients. However, most existing solutions suffer from one or more issues:

- **Framework Lock-in**: Tightly coupled to Spring Boot, Micronaut, or other frameworks
- **Storage Coupling**: Hard-wired to Redis, Hazelcast, or specific data stores
- **Limited Flexibility**: Difficult to implement multi-level or custom rate limiting strategies
- **Poor Testability**: Hard to test rate limiting logic in isolation

### The Solution

**FluxGate Core** was created to solve these problems by providing:

✅ **Pure Java Rate Limiting Engine** - No framework dependencies
✅ **Storage Independence** - Works with in-memory, Redis, Hazelcast, or any backend
✅ **Multi-Level Rate Limiting** - Combine IP-based, API key-based, user-based limits seamlessly
✅ **Extensible Architecture** - Plugin your own key resolution strategies and metrics collectors
✅ **Production-Ready** - Built on battle-tested Bucket4j with proper concurrency handling

---

## 🌟 Key Features

### 🔑 Flexible Key Strategies

Apply rate limits based on any combination of:
- **IP Address** - Limit requests per client IP
- **API Key** - Limit requests per API key
- **User ID** - Limit requests per authenticated user
- **Custom Keys** - Region + User, Tenant + Endpoint, or any custom logic

### ⏱️ Multi-Band Time Windows

Configure multiple time windows simultaneously:
```java
// Limit: 100 requests/minute AND 5000 requests/hour
rule.addBand(Duration.ofMinutes(1), 100)
    .addBand(Duration.ofHours(1), 5000);
```

### 📊 Built-in Metrics & Observability

```java
RateLimitMetricsRecorder recorder = (context, result) -> {
    if (result.isAllowed()) {
        metrics.increment("rate_limit.allowed");
    } else {
        metrics.increment("rate_limit.rejected");
        metrics.gauge("rate_limit.wait_time_seconds",
                      result.getNanosToWaitForRefill() / 1_000_000_000.0);
    }
};
```

### 🎚️ Multi-Level Rate Limiting

Combine different rate limiting strategies:
```java
// 1st Level: IP-based (100 req/min per IP)
// 2nd Level: Service-wide (10,000 req/10min total)
boolean allowed = checkIpLimit(ctx) && checkServiceLimit(ctx);
```

### 🔌 Storage Agnostic

- **In-Memory** - Single-node applications (included)
- **Redis** - Distributed rate limiting (bring your own adapter)
- **Hazelcast** - In-memory data grid (bring your own adapter)
- **Custom** - Implement `RateLimitBackend` SPI

---

## 🚀 Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-core</artifactId>
    <version>0.1.4</version>
</dependency>
```

### Basic Usage

```java

import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.config.*;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.*;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;

import java.time.Duration;
import java.util.List;

// 1. Define rate limit rules
RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100 requests per minute")
        .build();

        RateLimitRule rule = RateLimitRule.builder("api-rate-limit")
                .name("API Rate Limit")
                .scope(LimitScope.PER_IP)
                .addBand(band)
                .build();

        // 2. Configure key resolution strategy
        KeyResolver keyResolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

        // 3. Create rule set
        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("my-api-limiter")
                .description("Limit API requests by IP")
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .build();

        // 4. Initialize rate limiter
        RateLimiter rateLimiter = new Bucket4jRateLimiter();

        // 5. Check rate limit
        RequestContext context = RequestContext.builder()
                .clientIp("192.168.1.100")
                .endpoint("/api/data")
                .method("GET")
                .build();

        RateLimitResult result = rateLimiter.tryConsume(context, ruleSet);

if(result.

        isAllowed()){
        System.out.

        println("✅ Request allowed. Remaining: "+result.getRemainingTokens());
        }else{
        System.out.

        println("❌ Rate limit exceeded. Retry after: "+
                result.getNanosToWaitForRefill() /1_000_000_000+" seconds");
        }
```

---

## 🏗️ Architecture

### Core Concepts

```
┌─────────────────────────────────────────────────────────────┐
│                     Application Layer                       │
│                   (Your HTTP Framework)                     │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                   FluxGate Core                         │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ RateLimiter  │  │  RuleSet     │  │  KeyResolver    │  │
│  │ (Interface)  │→ │  (Config)    │→ │  (Strategy)     │  │
│  └──────────────┘  └──────────────┘  └─────────────────┘  │
│         ▲                                                   │
│         │                                                   │
│  ┌──────┴─────────┐                                        │
│  │ Bucket4j       │  (In-Memory Implementation)            │
│  │ RateLimiter    │                                        │
│  └────────────────┘                                        │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                    Storage Backend                          │
│   [In-Memory] [Redis] [Hazelcast] [Custom]                 │
└─────────────────────────────────────────────────────────────┘
```

### Component Overview

| Component | Purpose | Extensible |
|-----------|---------|------------|
| `RateLimiter` | Core rate limiting engine interface | ✅ Yes |
| `RateLimitRuleSet` | Configuration container (rules + strategy) | ❌ No |
| `RateLimitRule` | Individual rate limit rule definition | ❌ No |
| `RateLimitBand` | Time window + capacity configuration | ❌ No |
| `KeyResolver` | Strategy for extracting rate limit keys | ✅ Yes |
| `RateLimitMetricsRecorder` | Metrics collection hook | ✅ Yes |
| `RequestContext` | Request metadata container | ❌ No |
| `RateLimitResult` | Rate limit decision result | ❌ No |

---

## 📖 Advanced Usage

### Multi-Level Rate Limiting

Implement cascading rate limits for IP + Service quotas:

```java
// Level 1: IP-based limit (100 req/min per IP)
RateLimitRuleSet ipRuleSet = createIpRateLimit();

// Level 2: Service-wide limit (10,000 req/10min total)
RateLimitRuleSet serviceRuleSet = createServiceRateLimit();

public boolean processRequest(RequestContext ctx) {
    // Check IP limit first
    RateLimitResult ipCheck = rateLimiter.tryConsume(ctx, ipRuleSet);
    if (!ipCheck.isAllowed()) {
        return false; // Rejected: IP quota exceeded
    }

    // Check service limit
    RateLimitResult serviceCheck = rateLimiter.tryConsume(ctx, serviceRuleSet);
    if (!serviceCheck.isAllowed()) {
        return false; // Rejected: Service quota exceeded
    }

    return true; // Allowed
}
```

### Custom Key Resolution Strategy

Create complex key resolution logic:

```java
// Example: Region + User ID composite key
KeyResolver regionUserResolver = ctx -> {
    String region = (String) ctx.getAttributes().get("region");
    String userId = ctx.getUserId();
    return RateLimitKey.of(region + ":" + userId);
};

// Example: Tenant + Endpoint key
KeyResolver tenantEndpointResolver = ctx -> {
    String tenantId = (String) ctx.getAttributes().get("tenantId");
    String endpoint = ctx.getEndpoint();
    return RateLimitKey.of(tenantId + ":" + endpoint);
};
```

### Multiple Time Windows (Bands)

Enforce limits across different time scales:

```java
RateLimitRule multiWindowRule = RateLimitRule.builder("multi-window")
        .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())   // 10/sec
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())  // 100/min
        .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).build())   // 1000/hour
        .addBand(RateLimitBand.builder(Duration.ofDays(1), 10000).build())   // 10k/day
        .build();
```

### Consume Multiple Permits

Support weighted rate limiting:

```java
// Heavy operation consumes 5 permits
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 5);
```

---

## 🔧 Configuration

### Limit Scopes

| Scope | Description | Use Case |
|-------|-------------|----------|
| `GLOBAL` | Single shared bucket for all requests | Global API throttling |
| `PER_IP` | One bucket per IP address | Prevent IP-based abuse |
| `PER_USER` | One bucket per authenticated user | Fair per-user quotas |
| `PER_API_KEY` | One bucket per API key | API key tier limits |
| `CUSTOM` | Custom key resolution logic | Complex multi-tenant scenarios |

### Policies

| Policy | Behavior |
|--------|----------|
| `REJECT_REQUEST` | Immediately reject when limit exceeded (default) |
| `WAIT_FOR_REFILL` | Signal to wait until tokens available (client implements waiting) |

---

## 🧪 Testing

### Run Tests

```bash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest=Bucket4jRateLimiterTest

# Run feature demo
./mvnw test -Dtest=FeatureDemoTest

# Run multi-level rate limiting examples
./mvnw test -Dtest=MultiLevelRateLimitTest
```

### Test Coverage

- ✅ Basic rate limiting (allow/reject)
- ✅ Multi-band time windows
- ✅ Independent buckets per key
- ✅ Metrics recording
- ✅ Multi-level rate limiting
- ✅ Custom key resolution
- ✅ Multiple permit consumption

---

## 🎯 Use Cases

### 1. Public API Protection

```java
// Protect public API from abuse
// 100 requests/minute per IP
RateLimitRule publicApiRule = RateLimitRule.builder("public-api")
        .scope(LimitScope.PER_IP)
        .addBand(Duration.ofMinutes(1), 100)
        .build();
```

### 2. Tiered API Access

```java
// Free tier: 1000 requests/day
// Pro tier: 100,000 requests/day
KeyResolver tierResolver = ctx -> {
    String tier = (String) ctx.getAttributes().get("tier");
    return RateLimitKey.of("tier:" + tier + ":" + ctx.getApiKey());
};

RateLimitRule freeRule = RateLimitRule.builder("free-tier")
        .addBand(Duration.ofDays(1), 1000).build();

RateLimitRule proRule = RateLimitRule.builder("pro-tier")
        .addBand(Duration.ofDays(1), 100000).build();
```

### 3. Multi-Tenant SaaS

```java
// Per-tenant rate limiting
KeyResolver tenantResolver = ctx -> {
    String tenantId = (String) ctx.getAttributes().get("tenantId");
    return RateLimitKey.of("tenant:" + tenantId);
};

RateLimitRule tenantRule = RateLimitRule.builder("tenant-limit")
        .addBand(Duration.ofMinutes(1), 1000)  // 1000/min per tenant
        .addBand(Duration.ofHours(1), 50000)   // 50k/hour per tenant
        .build();
```

### 4. DDoS Protection

```java
// Aggressive global + IP limits
RateLimitRuleSet globalLimit = ...; // 100k req/min globally
RateLimitRuleSet ipLimit = ...;     // 100 req/min per IP

// Check both
boolean allowed = checkGlobalLimit(ctx) && checkIpLimit(ctx);
```

---

## 🛠️ Extending FluxGate

FluxGate Core is designed to be extended for custom implementations.

### Create Custom RateLimiter Implementations

You can implement the `RateLimiter` interface or extend `Bucket4jRateLimiter`:

- **Redis-based distributed rate limiting**
- **Hazelcast in-memory data grid**
- **Custom storage backends** (PostgreSQL, DynamoDB, etc.)
- **Decorator pattern** for logging, auditing, metrics
- **Circuit breaker** for fault tolerance

📖 **See the complete guide**: [How to Extend RateLimiter](../docs/HOW_TO_EXTEND_RATELIMITER.md)

### Custom Storage Backend Example

```java
public class RedisRateLimiter implements RateLimiter {
    private final RedisTemplate<String, Long> redisTemplate;

    @Override
    public RateLimitResult tryConsume(RequestContext context,
                                      RateLimitRuleSet ruleSet,
                                      long permits) {
        // Implement using Redis
    }
}
```

### Custom Metrics Recorder

Integrate with your observability stack:

```java
RateLimitMetricsRecorder prometheusRecorder = (ctx, result) -> {
    prometheusRegistry.counter("rate_limit_checks_total",
        "status", result.isAllowed() ? "allowed" : "rejected",
        "endpoint", ctx.getEndpoint()
    ).increment();

    if (!result.isAllowed()) {
        prometheusRegistry.histogram("rate_limit_wait_time_seconds")
            .observe(result.getNanosToWaitForRefill() / 1e9);
    }
};
```

### Custom Decision Listener

React to rate limiting decisions:

```java
public class AuditRateLimitListener implements RateLimitDecisionListener {
    @Override
    public void onEvaluated(RequestContext context, RateLimitResult result) {
        if (!result.isAllowed()) {
            auditLog.warn("Rate limit exceeded: IP={}, Endpoint={}",
                          context.getClientIp(),
                          context.getEndpoint());
        }
    }
}
```

---

## 📊 Performance

### In-Memory Performance

- **Throughput**: ~1M+ checks/second (single-threaded)
- **Latency**: <1μs per check (local bucket)
- **Memory**: ~1KB per unique key bucket
- **Concurrency**: Thread-safe (ConcurrentHashMap)

### Distributed Performance (Redis)

- **Throughput**: ~10K-100K checks/second (depends on network)
- **Latency**: ~1-10ms per check (network overhead)
- **Consistency**: Lua scripts for atomic operations

---

## 🗺️ Roadmap

### v0.1.1 (Current)
- ✅ In-memory Bucket4j implementation
- ✅ Multi-band time windows
- ✅ Flexible key resolution strategies
- ✅ Metrics recording hooks
- ✅ Multi-level rate limiting

### v0.2.0 (Planned)
- 🔄 Redis distributed backend
- 🔄 Hazelcast distributed backend
- 🔄 Rule-level `enabled` flag support
- 🔄 Bucket cache eviction strategies
- 🔄 `WAIT_FOR_REFILL` policy implementation

### v1.0.0 (Future)
- 🔮 Dynamic rule updates (no restart)
- 🔮 Admin REST API for rule management
- 🔮 Grafana dashboard templates
- 🔮 Spring Boot auto-configuration
- 🔮 Micronaut integration

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Setup

```bash
# Clone repository
git clone https://github.com/fluxgate/fluxgate-core.git
cd fluxgate-core

# Build project
./mvnw clean install

# Run tests
./mvnw test

# Check code style
./mvnw checkstyle:check
```

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](../LICENSE) file for details.

---

## 🙏 Acknowledgments

- Built on top of [Bucket4j](https://github.com/bucket4j/bucket4j) - An excellent Java rate limiting library
- Inspired by Kong, Nginx rate limiting, and AWS API Gateway throttling

---

## 📞 Contact

- **Author**: Jaeseong Ro
- **GitHub**: [fluxgate/fluxgate-core](https://github.com/fluxgate/fluxgate-core)
- **Issues**: [GitHub Issues](https://github.com/fluxgate/fluxgate-core/issues)

---

<div align="center">

**[Documentation](#-quick-start)** • **[Examples](src/test/java/org/fluxgate/core/FeatureDemoTest.java)** • **[Extend Guide](../docs/HOW_TO_EXTEND_RATELIMITER.md)** • **[Contributing](#-contributing)** • **[License](#-license)**

Made with ❤️ by the FluxGate team

</div>
