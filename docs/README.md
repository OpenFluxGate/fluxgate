# FluxGate Documentation

Welcome to the FluxGate documentation.

English | [한국어](README.ko.md)

---

## Documentation Structure

### Architecture

- [Architecture Overview](en/architecture/README.md) - System architecture, diagrams, and core concepts
- **Layers**
  - [Filter Layer](en/architecture/filter-layer.md) - Request interception, `RequestContext`
  - [Handler Layer](en/architecture/handler-layer.md) - `FluxgateRateLimitHandler` and the library default
  - [Engine Layer](en/architecture/engine-layer.md) - Rule set resolution and caching
  - [RateLimiter Layer](en/architecture/ratelimiter-layer.md) - Token bucket execution
  - [Storage Layer](en/architecture/storage-layer.md) - Redis buckets, MongoDB rules, the Lua contract
  - [Hot Reload](en/architecture/hot-reload.md) - Reload strategies, listener order, bucket reset
- [Algorithm Analysis](en/architecture/algorithm-analysis.md) - Token bucket complexity and the Lua optimizations

### Customization

- [Request Context](en/customization/request-context.md) - Customizing request context, and setting identity from the authenticated principal
- [Key Resolver](en/customization/key-resolver.md) - Custom rate limit key resolution and sanitisation

### Operations

- [Migrating to 0.4](en/operations/migration-0.4.md) - Upgrade impact from 0.3.x, with a checklist

### Korean deep dives

The Korean documentation carries a full source-level walk-through that has no English equivalent yet:

- [아키텍처 Deep Dive](ARCHITECTURE_DEEP_DIVE.ko.md) - Filter → Handler → Engine → RateLimiter → Storage → Reload, with the real code

---

## Quick Links

- [Main README](../README.md) - Getting started guide
- [Changelog](../CHANGELOG.md) - What changed, with the breaking list
- [Security Policy](../SECURITY.md) - Reporting, secure defaults, tenant isolation
- [Contributing Guide](../CONTRIBUTING.md)
- [Sample Applications](../fluxgate-samples/README.md) - Example implementations
- [GitHub Repository](https://github.com/OpenFluxGate/fluxgate)

---

## Module Documentation

| Module | Description | README |
|--------|-------------|--------|
| `fluxgate-core` | Core rate limiting engine, SPIs, in-memory limiter | [README](../fluxgate-core/README.md) |
| `fluxgate-redis-ratelimiter` | Redis token bucket storage, Lua contract, key format | [README](../fluxgate-redis-ratelimiter/README.md) |
| `fluxgate-mongo-adapter` | MongoDB rule management | [README](../fluxgate-mongo-adapter/README.md) |
| `fluxgate-spring-boot3-starter` | Spring Boot 3.x auto-configuration, full property reference | [README](../fluxgate-spring-boot3-starter/README.md) |
| `fluxgate-spring-boot2-starter` | Spring Boot 2.7.x auto-configuration (javax.servlet) | [README](../fluxgate-spring-boot2-starter/README.md) |
| `fluxgate-control-support` | `@NotifyRuleChange` and the Redis rule-change notifier | — |
| `fluxgate-testkit` | In-memory handler, rule builders, JUnit 5 extension, benchmarks | [README](../fluxgate-testkit/README.md) |
