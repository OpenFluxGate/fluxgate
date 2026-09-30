# Request Context

Every rate limit decision starts from a `RequestContext`. It is the only thing the core sees of your
request, so what it contains decides which bucket gets spent.

[< Back to Documentation Index](../../README.md) | [한국어](../../ko/customization/request-context.ko.md)

---

## What it holds

`org.fluxgate.core.context.RequestContext` is immutable and built through a builder.

| Field | Populated by the servlet filter from | Used by |
|-------|--------------------------------------|---------|
| `clientIp` | `remoteAddr`, or the forwarding header when it is trusted | `PER_IP` scope |
| `userId` | the `X-User-Id` request header | `PER_USER` scope |
| `apiKey` | the `X-API-Key` request header | `PER_API_KEY` scope |
| `endpoint` | the normalized request path | metrics `endpoint` tag, logging |
| `method` | the HTTP method | metrics `method` tag, logging |
| `headers` | allow-listed request headers, only when `collect-headers=true` | your own code, metrics recorders |
| `attributes` | nothing — this is yours | `CUSTOM` scope, your own code |

There is no `path` field and no `ruleSetId` field. The path lives in `endpoint`, and the rule set id
is a separate argument to `FluxgateRateLimitHandler.tryConsume(context, ruleSetId)`.

## How the filter builds it

`org.fluxgate.spring.filter.RequestContextFactory` builds the context, and the filter and the
`@RateLimit` aspect share it — so a rule set behaves identically in both modes.

```
HTTP request
   │
   ├─ clientIp  ← ClientIpExtractor.extract(request, clientIpHeader, trust, trustedProxies)
   ├─ userId    ← request.getHeader("X-User-Id")
   ├─ apiKey    ← request.getHeader("X-API-Key")
   ├─ endpoint  ← RequestPathResolver (normalized, context-path independent)
   ├─ method    ← request.getMethod()
   ├─ headers   ← allow-listed headers, if collect-headers=true
   │
   └─ RequestContextCustomizer.customize(builder, request)   ← runs LAST, wins over everything
          │
          └─ build()
```

The customizer runs last on purpose: whatever it sets overrides what the factory extracted.

## `RequestContextCustomizer`

```java
package org.fluxgate.spring.filter;

@FunctionalInterface
public interface RequestContextCustomizer {
  RequestContext.Builder customize(RequestContext.Builder builder, HttpServletRequest request);
}
```

Register one bean; the starter picks it up automatically.

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

### The most important use: authenticated identity

By default `userId` and `apiKey` come from request headers, which the **client controls**. A caller
can therefore send a different `X-User-Id` per request and bypass a per-user limit, or send someone
else's identifier and exhaust their quota.

That is correct behaviour for a gateway behind an authenticating edge that rewrites those headers.
Everywhere else, set them from the authenticated principal:

```java
@Bean
public RequestContextCustomizer authenticatedIdentityCustomizer() {
    return (builder, request) -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {
            builder.userId(auth.getName());   // never the X-User-Id header
            builder.apiKey(null);             // drop any client supplied API key
        }
        return builder;
    };
}
```

Two conditions for this to work:

- The FluxGate filter must run **after** Spring Security, otherwise there is no principal yet. The
  default `fluxgate.ratelimit.filter-order=1` already satisfies this — Spring Security's chain
  defaults to order `-100`. Only a deliberately negative value breaks it.
- Combine it with `fluxgate.ratelimit.missing-key-behavior=REJECT` if an unauthenticated request
  must not fall back to the IP bucket and inherit the authenticated tier's quota.

### Overriding the client IP

When your CDN uses its own header, or the forwarding chain is shaped in a way
`trusted-proxies` cannot express:

```java
@Bean
public RequestContextCustomizer cloudflareIpCustomizer() {
    return (builder, request) -> {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isEmpty()) {
            builder.clientIp(cfIp);
        }
        return builder;
    };
}
```

This trusts `CF-Connecting-IP` unconditionally, so it is only safe when nothing but your CDN can
reach the application. Prefer `fluxgate.ratelimit.trusted-proxies` where it fits.

### Composite keys for `CUSTOM` scope

A `CUSTOM` rule reads one attribute, named by the rule's `keyStrategyId`:

```java
@Bean
public RequestContextCustomizer compositeKeyCustomizer() {
    return (builder, request) -> {
        String userId = request.getHeader("X-User-Id");
        String clientIp = request.getRemoteAddr();

        // Prefix each component so the parts stay unambiguous after sanitisation.
        String composite = userId != null
            ? "ip:" + clientIp + ":user:" + userId
            : "ip:" + clientIp;

        builder.attribute("ipUser", composite);
        return builder;
    };
}
```

```java
RateLimitRule rule = RateLimitRule.builder("composite-rule")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();
```

For a multi-tenant system, validate `X-Tenant-Id` at your application boundary before copying it,
and include both tenant and subject in the key (`tenantId + ":" + userId`), so one tenant cannot
spend or reset another tenant's bucket.

## Header collection

`RequestContext.getHeaders()` is **empty** unless you opt in:

```yaml
fluxgate:
  ratelimit:
    collect-headers: true
    header-allowlist:
      - X-Tenant-Id
      - X-Request-Id
```

Matching is case-insensitive. These headers are **never** copied, whatever the allow list says:

`Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-API-Key`

`X-API-Key` still populates `RequestContext.getApiKey()`; it just does not end up in the header map.

Collection is off by default because the context is handed to metrics recorders that may **persist**
it — `MongoRateLimitMetricsRecorder` writes it to MongoDB. Treat anything you allow-list as data you
are choosing to store.

## Invocations without an HTTP request

A `@RateLimit` method called from a scheduled task or a message listener has no request. The aspect
then builds a minimal context through `RequestContextFactory.createForInvocation(endpoint, method)`,
which sets only `endpoint` (typically `Type.method`) and `method` (such as `INTERNAL`).

Identity scopes have nothing to resolve there, so `fluxgate.ratelimit.missing-key-behavior` decides
what happens: `FALLBACK_TO_IP` produces the key `ip:unknown` — one shared bucket for every such
invocation — and `REJECT` rejects. For internal invocations, a `GLOBAL` rule is usually what you
actually want.

## Building one by hand

In a test or when calling a handler directly:

```java
RequestContext context = RequestContext.builder()
    .clientIp("10.0.0.1")
    .userId("user-123")
    .apiKey("key-abc")
    .endpoint("/api/orders")
    .method("POST")
    .attribute("tenantId", "acme")
    .build();
```

`fluxgate-testkit` packages this together with an in-memory handler — see
[fluxgate-testkit/README.md](../../../fluxgate-testkit/README.md).

---

## Related

- [Key Resolver](key-resolver.md) - how a context becomes a bucket key
- [Filter Layer](../architecture/filter-layer.md)
- [Security Policy](../../../SECURITY.md) - the identity-header caveat in full
- [Documentation Index](../../README.md)
