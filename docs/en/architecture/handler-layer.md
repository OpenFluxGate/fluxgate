# Handler Layer

The Handler Layer is the boundary between the servlet layer and the core engine.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/handler-layer.ko.md)

---

## Components

### FluxgateRateLimitHandler

The interface the filter and the aspect know about. There is no separate `RateLimitHandler` type and
no `handle(context)` method.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── FluxgateRateLimitHandler.java
```

```java
public interface FluxgateRateLimitHandler {

  RateLimitResponse tryConsume(RequestContext context, String ruleSetId);

  /** Weighted requests. The default delegates when permits == 1 and rejects anything heavier. */
  default RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    if (permits == 1) {
      return tryConsume(context, ruleSetId);
    }
    throw new UnsupportedOperationException(
        "Weighted permits are not supported by " + getClass().getName());
  }

  /** Last-resort fallback when no handler exists at all. */
  FluxgateRateLimitHandler ALLOW_ALL = (context, ruleSetId) -> RateLimitResponse.allowed(-1, 0);
}
```

To support `fluxgate.ratelimit.cost-header`, override the **three-argument** form.

### EngineBackedRateLimitHandler

The library's own implementation, registered automatically whenever a `RateLimiter` and a
`RateLimitRuleSetProvider` bean exist. `@EnableFluxgateFilter` with Redis and MongoDB therefore
needs no user code.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/handler/
└── EngineBackedRateLimitHandler.java
```

**Responsibilities:**
- Call `RateLimitEngine.check(ruleSetId, context, permits)`
- Convert the result with `RateLimitResponse.from(result)` (nanos → millis, rounding up)
- Map errors to whoever owns them, logged at WARN once per rule set id and at DEBUG afterwards:
  - `MissingRateLimitKeyException` (from `missing-key-behavior=REJECT`): a rejected response with no
    wait time (HTTP 429)
  - a request cost that no band can hold: `PermitsExceedCapacityException`, a client error (HTTP
    429, logged at DEBUG only)
  - any other `InvalidRuleConfigException`, or a missing rule set under `missing-rule-behavior=DENY`:
    `RateLimiterUnavailableException`, a configuration problem rather than an exceeded limit (HTTP
    503, also under `failure-behavior=ALLOW`)

It does **not** record metrics. Metrics belong to `RateLimitRuleSet.getMetricsRecorder()` in the core
and to `MicrometerMetricsRecorder` in the starter.

### ResilientRateLimiter

Not a handler, but the decorator that sits between the engine and the real limiter. It routes every
call through `ResilientExecutor` (retry + circuit breaker), and on failure either degrades to an
in-memory `Bucket4jRateLimiter` (`fallback.mode=IN_MEMORY`) or applies
`fluxgate.ratelimit.failure-behavior`.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/handler/
├── ResilientRateLimiter.java
├── LazyRedisRateLimiter.java
└── RedisConnectionState.java
```

### Your own handler: HTTP API mode

FluxGate ships no HTTP handler. Write one when you want a central rate limit service; the sample is
`fluxgate-samples/fluxgate-sample-filter`'s `HttpRateLimitHandler`.

```java
@SpringBootApplication
@EnableFluxgateFilter(handler = HttpRateLimitHandler.class)
public class ClientApplication { }
```

```
┌─────────────────┐         ┌──────────────────────────────┐
│  API Gateway    │  HTTP   │  Rate Limit Service          │
│  (Port 8083)    │ ──────→ │  (Port 8082)                 │
│                 │         │                              │
│  HttpRateLimit  │         │  EngineBackedRateLimitHandler│
│  Handler        │         │  + Redis                     │
└─────────────────┘         └──────────────────────────────┘
```

`fluxgate.api.url` is read by that **sample handler**, not by the library.

### RateLimitResponse

The value object a handler returns.

| Field | Meaning | `-1` means |
|-------|---------|------------|
| `allowed` | the decision | — |
| `remainingTokens` | tokens left in the band that decided | unknown → header omitted |
| `retryAfterMillis` | delay before retrying | unknown |
| `onLimitExceedPolicy` | `REJECT_REQUEST` or `WAIT_FOR_REFILL` | — |
| `limit` | capacity of the band that decided | unknown → header omitted |
| `resetTimeMillis` | epoch millis when the deciding band resets: TOKEN_BUCKET when it is full again, SLIDING_WINDOW when every request counted now has left the window, FIXED_WINDOW the window end (the in-memory limiter estimates time until full for every algorithm) | unknown → header omitted |
| `windowSeconds` | window behind `limit`, for `RateLimit-Policy` | unknown → header omitted |
| `bandLabel` | key label of the band that decided | — |

`RateLimitResponse.from(RateLimitResult)` converts nanoseconds to milliseconds **rounding up**, so a
sub-millisecond delay never becomes `Retry-After: 0`.

---

## Related

- [Filter Layer](filter-layer.md)
- [Engine Layer](engine-layer.md)
- [Architecture Overview](README.md)
