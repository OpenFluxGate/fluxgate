# FluxGate Roadmap

This roadmap is derived from the project's current capabilities, known
limitations, and community priorities. Items are not dated; they reflect
intent, not commitments.

## Already shipped (0.4)

* Prometheus / Micrometer metrics integration
* Redis Cluster support (Lua-based multi-slot key hash tags)
* Structured JSON logging with correlation IDs
* Rate limit quota management UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
* Circuit breaker and retry integration (wired through `ResilientRateLimiter`)
* In-memory limiter and in-memory fallback during a Redis outage
* Standard IETF `RateLimit-*` response headers (`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`) and RFC 9457 problem responses
* Multi-module Maven reactor; separate boot2 / boot3 starters
* CycloneDX SBOM artifact attached to every release
* Scoped key prefixes (`ip:`, `user:`, `key:`, `custom:`, `global`)
* `missing-key-behavior: REJECT`
* `RateLimit-Policy` header, independently switchable standard / legacy header sets, and a custom `response.body-template`
* Trusted-proxy validation (`trusted-proxies` CIDR list) for `X-Forwarded-For`
* `tryConsume(context, ruleSetId, permits)` weighted consumption
* Sliding window and fixed window algorithms
* Calendar quotas (daily, weekly, monthly)
* Path, method and header matching on rules
* `RateLimitRule` / `RateLimitBand` value equality
* Cross-rule consumption: a single Lua call on standalone Redis or within one slot, compensated (refund not atomic) across slots on Redis Cluster

## Medium-Term

These items require more design work or depend on community demand.

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

* Strict cross-rule atomicity on Redis Cluster: when the keys of the matching rules
  hash to different slots, rules are charged one by one and refunded if a later
  rule rejects. The refund is not atomic with the charge. On a standalone Redis
  or within one slot all rules are evaluated in a single Lua call. Use one rule
  with multiple bands when strict atomicity matters on a cluster.
* Servlet-only: the servlet filter blocks a thread during `WAIT_FOR_REFILL`. There
  is no WebFlux filter today.

Contributions addressing any of the above are welcome.
