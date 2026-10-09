# Engine Layer

The Engine Layer resolves a rule set by id and delegates token consumption.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/engine-layer.ko.md)

---

> **There is no rule matching by path or method.** `RateLimitRule` has no `path`, `method` or
> `priority` field, and `RateLimitEngine` contains no matching logic. The engine looks a rule set up
> by id and hands it to a `RateLimiter`, which evaluates **every enabled rule** in it. To vary limits
> by request surface, register several filters with different `include-patterns`, or set
> `default-rule-set-id` per application.

## Components

### RateLimitEngine

The canonical entry point into the core.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/engine/
└── RateLimitEngine.java
```

```java
public RateLimitResult check(String ruleSetId, RequestContext context, long permits)
```

**Responsibilities:**
- Look the rule set up through `RateLimitRuleSetProvider.findById(ruleSetId)`
- Apply `OnMissingRuleSetStrategy` when it is absent
- Delegate to `RateLimiter.tryConsume(context, ruleSet, permits)`
- Never return `null` — a `RateLimiter` that breaks that contract raises `IllegalStateException`

`OnMissingRuleSetStrategy`, which the starter wires from `fluxgate.ratelimit.missing-rule-behavior`:

| Strategy | Result |
|----------|--------|
| `THROW` | `IllegalArgumentException("Unknown ruleSetId: …")` |
| `ALLOW` | fail-open: `RateLimitResult.allowedWithoutRule()`, no rule information |
| `DENY` | fail-closed: rejected, `nanosToWaitForRefill = 0`, synthetic key `missing-rule-set:<id>` so the rejection is traceable in metrics and logs |

The engine never calls the `RateLimiter` on the `ALLOW` or `DENY` path.

### RateLimitRuleSetProvider

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/spi/
└── RateLimitRuleSetProvider.java
```

```java
public interface RateLimitRuleSetProvider {
  Optional<RateLimitRuleSet> findById(String ruleSetId);
}
```

The package is `org.fluxgate.core.spi`, and the method returns an `Optional`.

**Implementations:**

| Implementation | Module |
|----------------|--------|
| `org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider` | fluxgate-mongo-adapter |
| `org.fluxgate.core.reload.CachingRuleSetProvider` | fluxgate-core (decorator) |
| your own lambda | your application |

A rule set carries its **own** `KeyResolver` — `RateLimitRuleSet.Builder.keyResolver(...)` is
required — so a provider you write yourself must set one.

MongoDB error semantics matter here: a socket error, timeout or not-primary becomes
`MongoConnectionException`, any other `MongoException` a retryable `FluxgateOperationException`, and
only a **successful empty query** returns `Optional.empty()`. Before that distinction existed, a
connection failure looked like "no rules exist" and silently turned into a global allow or deny,
depending on `missing-rule-behavior`.

### RuleCache and CaffeineRuleCache

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/reload/
└── RuleCache.java

📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/cache/
└── CaffeineRuleCache.java
```

`RuleCache` is `Optional`-based. The interesting method is:

```java
default Optional<RateLimitRuleSet> getOrLoad(
    String ruleSetId, Function<String, Optional<RateLimitRuleSet>> loader)
```

The default is a plain get/load/put, so concurrent misses can each run the loader.
`CaffeineRuleCache` overrides it with `cache.get(key, mappingFunction)`, which holds a per-key lock
for the duration of the load — concurrent misses for the same id collapse into one load. It also
keeps a second cache of **negative** results, so a rule set that does not exist is not re-queried on
every request.

`CachingRuleSetProvider.findById` goes through `getOrLoad`, which is what lets the cache implement
that behaviour at all.

| Property | Default |
|----------|---------|
| `fluxgate.reload.cache.ttl` | `5m` |
| `fluxgate.reload.cache.max-size` | `1000` |
| `fluxgate.reload.cache.negative-ttl` | `5s` (`0` disables) |

### KeyResolver

Keys are resolved by the **rate limiter**, once per rule, through `ruleSet.getKeyResolver()` — not by
the engine.

```java
public interface KeyResolver {
  RateLimitKey resolve(RequestContext context, RateLimitRule rule);
}
```

| LimitScope | Resolved key value |
|------------|--------------------|
| `GLOBAL` | `global` |
| `PER_IP` | `ip:192.168.1.100` |
| `PER_USER` | `user:user-123` |
| `PER_API_KEY` | `key:abc123` |
| `CUSTOM` | `custom:<attribute value>` |

That is the key **value**, not the whole bucket key. See [Key Resolver](../customization/key-resolver.md).

---

## Related

- [Handler Layer](handler-layer.md)
- [RateLimiter Layer](ratelimiter-layer.md)
- [Key Resolver](../customization/key-resolver.md)
- [Architecture Overview](README.md)
