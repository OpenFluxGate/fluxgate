# Hot Reload

FluxGate reloads rate limit rules without a restart.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/hot-reload.ko.md)

---

## Configuration

Reload properties live under `fluxgate.reload`, not `fluxgate.ratelimit.reload`:

```yaml
fluxgate:
  reload:
    enabled: true
    strategy: AUTO          # AUTO | POLLING | PUBSUB | NONE
    cache:
      ttl: 5m
      max-size: 1000
      negative-ttl: 5s      # 0 disables negative caching
    polling:
      interval: 30s
      initial-delay: 10s
    pubsub:
      channel: fluxgate:rule-reload
      retry-on-failure: true
      retry-interval: 5s
      backstop-polling-interval: 60s   # 0 disables the backstop
```

## Listener order is a contract

Every strategy extends `AbstractReloadStrategy`, which defines the order:

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/strategy/
└── AbstractReloadStrategy.java
```

| Constant | Value | Listener |
|----------|-------|----------|
| `ORDER_CACHE_INVALIDATION` | `-100` | `CachingRuleSetProvider` |
| `DEFAULT_ORDER` | `0` | anything registered with the one-argument `addListener` |
| `ORDER_BUCKET_RESET` | `100` | `BucketResetHandler` |

Groups run in ascending order, and **if a group fails, every higher order is skipped** with an ERROR
log. That ordering is load-bearing: resetting buckets before invalidating the cache means the next
request reads the stale rules and refills the buckets you just cleared to the old capacity.

## Strategies

### PollingReloadStrategy

Polls the provider and compares a content hash per rule set.

```java
private static int computeVersion(RateLimitRuleSet ruleSet) {
  return Objects.hash(ruleSet.getId(), ruleSet.getDescription(), ruleSet.getRules());
}
```

This is only stable because `RateLimitRule` and `RateLimitBand` now have value-based
`equals`/`hashCode`. Without them the hash changed on every poll, so polling **deleted every bucket
every cycle** — rate limiting was effectively disabled.

It must not use `RateLimitRuleSet.equals`, which also compares `keyResolver` and `metricsRecorder`,
usually freshly created lambdas.

### RedisPubSubReloadStrategy

Real-time propagation over Lettuce Pub/Sub (not Jedis).

Message handling:

| Payload | Effect |
|---------|--------|
| `{"version": 1, "ruleSetId": "api-limits"}` | reload that rule set |
| `"*"` | deliberate full reload |
| `{"fullReload": true}` | deliberate full reload |
| empty, not JSON, not an object, unknown `version` | WARN and **ignored** |

The last row used to be a full reload, which meant one malformed message wiped every bucket — an
unauthenticated global rate limit bypass window for anyone who could publish to Redis.

Lifecycle: the Lettuce client and the Pub/Sub connection are held in `AtomicReference`s; a resubscribe
closes the previous connection, and `doStop()` shuts a client down when the strategy owns it.

### CompositeReloadStrategy

`AUTO` and `PUBSUB` are wired as `CompositeReloadStrategy(pubsub, pollingBackstop)`, so a dropped
message self-heals within the backstop interval instead of leaving the data plane on stale rules
indefinitely. Set `backstop-polling-interval: 0` to turn it off.

### NONE

No reload at all. Note that `NONE` used to make the rule cache bean `null`, which failed the whole
application context with `UnsatisfiedDependencyException`; the bean is now conditional.

## BucketResetHandler

When rules change, the remaining token buckets have to be cleared, or the new rules do not take
effect until the old buckets expire.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/reload/
└── BucketResetHandler.java

📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/handler/
├── RedisBucketResetHandler.java
└── InMemoryBucketResetHandler.java
```

```java
public interface BucketResetHandler {
  void resetBuckets(String ruleSetId);
  void resetAllBuckets();
}
```

`RedisBucketResetHandler` takes a `Supplier<RedisTokenBucketStore>`, because the Redis beans are
`@Lazy`; it resolves the store per reset and skips with a WARN when Redis is unavailable.
`InMemoryBucketResetHandler` does the same job through `Bucket4jRateLimiter.reset` / `resetAll`.

## Publishing a change

`fluxgate-control-support` provides `@NotifyRuleChange` and `@NotifyFullReload` for the control plane:

- Inside a transaction, publishing is deferred to `afterCommit`, so the data plane never sees a change
  that was rolled back.
- Failures are retried three times with jittered backoff; a final failure is logged at ERROR and
  counted in `RuleChangeNotifierMetrics.getFailedNotifications()`.
- Messages carry `version: 1`.
- SpEL such as `#ruleSetId` needs the annotated class compiled with `-parameters` (Spring Boot's
  parent POM does this), or the positional `#a0` / `#p0` form.

## Flow

```
Admin updates MongoDB
        ↓
@NotifyRuleChange → (afterCommit inside a transaction) → Redis Pub/Sub
                     retried 3x on failure, then ERROR + metric
        ↓
┌──────────────────────────────────────────────────────────────┐
│ All application instances                                    │
│ (a dropped message is caught by the 60s polling backstop)     │
│                                                              │
│ order -100   RuleCache.invalidate(ruleSetId)                 │
│                   ↓  (stops here if this fails)               │
│ order  100   BucketResetHandler.resetBuckets(ruleSetId)      │
│                   ↓                                           │
│ The next request loads the new rules from MongoDB             │
└──────────────────────────────────────────────────────────────┘
```

> **Security.** The reload channel is a control-plane channel: `PUBLISH` access to it is equivalent to
> write access to your rules. FluxGate does not authenticate publishers — protect the channel with
> Redis AUTH, TLS and ACLs. See [SECURITY.md](../../../SECURITY.md).

---

## Related

- [Storage Layer](storage-layer.md)
- [Engine Layer](engine-layer.md)
- [Architecture Overview](README.md)
