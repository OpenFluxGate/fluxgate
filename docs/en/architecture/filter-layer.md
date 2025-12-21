# Filter Layer

The Filter Layer is the entry point for HTTP request interception and rate limiting.

[< Back to Architecture Overview](README.md)

---

## Components

### FluxgateRateLimitFilter

The main filter that intercepts HTTP requests and applies rate limiting.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── FluxgateRateLimitFilter.java
```

**Responsibilities:**
- Intercept incoming HTTP requests
- Build `RequestContext` from request data
- Delegate to `RateLimitHandler` for rate limiting decisions
- Add rate limit headers to responses
- Handle 429 Too Many Requests responses

### RequestContext

An immutable data object containing all request metadata.

```
📁 fluxgate-core/src/main/java/org/fluxgate/core/context/
└── RequestContext.java
```

**Fields:**
- `path` - Request URI path
- `method` - HTTP method (GET, POST, etc.)
- `clientIp` - Client IP address
- `userId` - User identifier (optional)
- `apiKey` - API key (optional)
- `ruleSetId` - Rule set to apply
- `attributes` - Custom attributes map

### RequestContextCustomizer

A functional interface for customizing the request context.

```
📁 fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/filter/
└── RequestContextCustomizer.java
```

**Usage:**
```java
@Bean
public RequestContextCustomizer customizer() {
    return (builder, request) -> {
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

---

## Flow

```
HTTP Request
    ↓
FluxgateRateLimitFilter.doFilterInternal()
    ↓
buildRequestContext()
    ├─→ RequestContext.builder()
    └─→ customizer.customize()
    ↓
handler.handle(context)
    ↓
Response (200 OK or 429 Too Many Requests)
```

---

## Related

- [Handler Layer](handler-layer.md)
- [Architecture Overview](README.md)
