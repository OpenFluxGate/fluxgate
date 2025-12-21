# Handler Layer

The Handler Layer orchestrates rate limiting by coordinating between filters and the engine.

[< Back to Architecture Overview](README.md)

---

## Components

### RateLimitHandler Interface

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/handler/
└── RateLimitHandler.java
```

```java
public interface RateLimitHandler {
    RateLimitResponse handle(RequestContext context);
}
```

### FluxgateRateLimitHandler

The default implementation that delegates to `RateLimitEngine`.

**Responsibilities:**
- Call the rate limit engine
- Record metrics
- Convert results to response format

### HttpRateLimitHandler

A handler that calls a remote rate limit service via HTTP.

**Use Case:**
```
┌─────────────────┐         ┌─────────────────────────┐
│  API Gateway    │  HTTP   │  Rate Limit Service     │
│  (Port 8080)    │ ──────→ │  (Port 8082)            │
│                 │         │                         │
│  HttpRateLimit  │         │  FluxgateRateLimitHandler│
│  Handler        │         │  + Redis                │
└─────────────────┘         └─────────────────────────┘
```

---

## Related

- [Filter Layer](filter-layer.md)
- [Engine Layer](engine-layer.md)
- [Architecture Overview](README.md)
