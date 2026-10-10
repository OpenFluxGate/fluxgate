# Filter Layer

The Filter Layer is the entry point for HTTP request interception and rate limiting.

[< Back to Architecture Overview](README.md) | [한국어 (deep dive)](../../ko/architecture/deep-dive/filter-layer.ko.md)

---

## Components

### FluxgateRateLimitFilter

The main filter that intercepts HTTP requests and applies rate limiting.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── FluxgateRateLimitFilter.java
```

**Responsibilities:**
- Decide whether the request is in scope, matching `include-patterns` / `exclude-patterns` against
  the **normalized** path from `RequestPathResolver` (URL-decoded, `;`-parameters removed, `//`
  collapsed, trailing slash stripped, context path independent)
- Build a `RequestContext` through `RequestContextFactory`
- Call `FluxgateRateLimitHandler.tryConsume(context, ruleSetId[, permits])`
- Write rate limit headers through `RateLimitHeaderWriter`, on allowed responses too
- On rejection, delegate the body to a `RateLimitResponseWriter` (RFC 9457 problem JSON by default)
- Populate and restore the logging MDC

**Two invariants worth knowing:**

1. `filterChain.doFilter(...)` runs **exactly once, outside** the limiter's try/catch. It used to sit
   inside it, so a downstream exception ran the whole chain a second time.
2. The MDC entries the filter found on entry are restored in a `finally` block. It used to call
   `MDC.clear()`, which wiped entries owned by other components.

### RequestContextFactory

Builds the context, shared by the filter and the `@RateLimit` aspect so a rule set behaves
identically in both modes.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── RequestContextFactory.java
```

### RequestContext

An immutable data object containing the request metadata the core needs.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/context/
└── RequestContext.java
```

**Fields:** `clientIp`, `userId`, `apiKey`, `endpoint`, `method`, `headers`, `attributes`.

There is no `path` field — the normalized path is `endpoint` — and no `ruleSetId` field, because the
rule set id is a separate argument to the handler.

`headers` is empty unless `fluxgate.ratelimit.collect-headers=true`, and then only allow-listed
names are copied. `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and `X-API-Key` are
never copied.

### RequestContextCustomizer

A functional interface for customizing the context. It runs **last**, so it overrides anything the
factory extracted.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── RequestContextCustomizer.java
```

```java
@Bean
public RequestContextCustomizer customizer() {
    return (builder, request) -> {
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

See [Request Context](../customization/request-context.md) for the authenticated-identity pattern,
which is the customizer's most important use.

### Supporting types

| Type | Purpose |
|------|---------|
| `util.ClientIpExtractor` | Client IP, honouring `trust-client-ip-header` and `trusted-proxies` |
| `util.TrustedProxies` | Immutable IPv4/IPv6 + CIDR set; never resolves hostnames |
| `util.RequestPathResolver` | Path normalization |
| `util.LogSanitizer` | Strips control characters and caps length before a value reaches the MDC |
| `filter.RateLimitHeaderWriter` | Legacy `X-RateLimit-*` and IETF `RateLimit-*` families |
| `filter.RateLimitResponseWriter` | SPI for the 429 body |
| `filter.ProblemDetailRateLimitResponseWriter` | Default RFC 9457 body, no Jackson needed |

### RateLimitAspect

`@RateLimit` on a method is the alternative entry point, for cases the filter cannot express.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/aop/
├── RateLimitAspect.java
└── RateLimitExceededException.java
```

It shares `RequestContextFactory` with the filter, works without an `HttpServletRequest` at all
(scheduled tasks, message listeners), and throws `RateLimitExceededException` when there is no
response to write to or when `@RateLimit(throwOnReject = true)`.

---

## Flow

```
HTTP Request
    ↓
FluxgateRateLimitFilter.doFilterInternal()
    ↓
RequestPathResolver → include/exclude match
    ↓
RequestContextFactory.create(request, endpoint)
    ├─→ ClientIpExtractor + trustedProxies
    └─→ RequestContextCustomizer.customize()     (last)
    ↓
handler.tryConsume(context, ruleSetId[, permits])
    ↓
RateLimitHeaderWriter.write(response, result)
    ↓
allowed  → filterChain.doFilter()  (exactly once, outside the try/catch)
rejected → RateLimitResponseWriter.write() → 429 + problem+json
```

---

## Related

- [Handler Layer](handler-layer.md)
- [Request Context](../customization/request-context.md)
- [Architecture Overview](README.md)
