# FluxGate Roadmap

This roadmap is derived from the project's current capabilities, known
limitations, and community priorities. Items are not dated; they reflect
intent, not commitments.

## Already Shipped

* Prometheus / Micrometer metrics integration
* Redis Cluster support (Lua-based multi-slot key hash tags)
* Structured JSON logging with correlation IDs
* Rate limit quota management UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
* Circuit breaker and retry integration (wired through `ResilientRateLimiter`)
* In-memory limiter and in-memory fallback during a Redis outage
* Standard IETF `RateLimit-*` response headers (`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`) and RFC 9457 problem responses (basic support; see Near-Term for configurable enhancements)
* Multi-module Maven reactor; separate boot2 / boot3 starters
* CycloneDX SBOM artifact attached to every release

## Near-Term (next minor release)

These items address known correctness gaps and the highest-scored missing
features from the independent audit.

### Correctness and Observability

* **Enhancements to the existing `RateLimit-*` headers**: add `RateLimit-Policy`
  (`100;w=60` form); make the standard set (`RateLimit-*`) and the legacy set
  (`X-RateLimit-*`) independently configurable via `response.include-standard-headers`
  and `response.include-legacy-headers`.
* **Enhancements to RFC 9457 problem responses**: ensure `Content-Type:
  application/problem+json` is always set, add `retryAfterMillis` to the body,
  and support a custom `response.body-template` override. (Basic 429 JSON responses
  shipped in 0.3.x; this work makes them fully spec-compliant and configurable.)
* **`RateLimitResult` carries `limit` and `resetTimeMillis`**: the bucket capacity
  and next-full epoch are propagated all the way from the Lua response to HTTP
  response headers.
* **Trusted-proxy validation for `X-Forwarded-For`**: `trusted-proxies` CIDR list;
  `trust-client-ip-header` only honoured when the request comes from a listed proxy.
* **Scoped key prefixes** (`ip:`, `user:`, `key:`, `custom:`, `global`): avoids
  namespace collisions when multiple rule sets share a Redis keyspace.
* **`missing-key-behavior: REJECT`**: rejects rather than falling back to IP when the
  configured identity field is absent.

### API

* **`tryConsume(context, ruleSetId, permits)`**: weighted permit consumption for
  cost-based rate limiting.
* **`RateLimitRule` / `RateLimitBand` value equality**: `equals`/`hashCode` so rule
  objects can be used in sets and as map keys reliably.

## Medium-Term

These items require more design work or depend on community demand.

### Algorithms

* **Sliding window** (Redis Sorted Set or approximate sliding window): current
  token-bucket semantics cause burst allowances at window edges; a sliding window
  eliminates this.
* **Fixed window**: simpler and cheaper than token bucket for calendar-aligned quotas.
* **Calendar quotas** (daily, weekly, monthly): use-case driven by API monetisation
  and quota management.

### Rule Matching

* **Path and method matching on rules**: annotate a rule with `include-patterns` and
  `methods` so one filter deployment can enforce different limits per endpoint without
  registering multiple filter beans.

### Reactive Support

* **WebFlux / Project Reactor integration**: a `WebFilter` for reactive stacks.
  `WAIT_FOR_REFILL` would use `Mono.delay` instead of blocking a thread.
* **Spring Cloud Gateway filter**: drop-in integration for Gateway-level limiting.

### Operational

* **Bucket introspection API**: a management endpoint or actuator extension to read
  and optionally reset an individual caller's current token count.
* **Admin API for bucket reset**: exposes bucket-level reset to FluxGate Studio and
  to external automation without requiring a rule-set reload.

## Long-Term / Exploratory

* **gRPC integration**: a `ServerInterceptor` for gRPC services.
* **Reactive Redis support** (Lettuce reactive client) for non-blocking bucket
  operations on the reactive stack.
* **Multi-tenant rule isolation guarantees**: formal documentation and verification
  that two tenants' buckets can never cross-pollute, even under identical user IDs.

## Known Limitations Not Yet on the Roadmap

The following limitations are real and acknowledged. They are not on the active
roadmap because they require significant redesign and no contributor has
committed to them yet:

* Cross-rule atomicity: multiple rules in one rule set are not evaluated in a
  single atomic operation. Earlier rules consume tokens before a later rule rejects.
  Use one rule with multiple bands when strict atomicity matters.
* Servlet-only: the servlet filter blocks a thread during `WAIT_FOR_REFILL`. There
  is no WebFlux filter today.

Contributions addressing any of the above are welcome.
