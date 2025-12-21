# FluxGate Documentation

Welcome to the FluxGate documentation.

English | [한국어](README.ko.md)

---

## Documentation Structure

### Architecture

- [Architecture Overview](en/architecture/README.md) - System architecture, diagrams, and core concepts

### Customization

- [Request Context](en/customization/request-context.md) - Customizing request context
- [Key Resolver](en/customization/key-resolver.md) - Custom rate limit key resolution

---

## Quick Links

- [Main README](../README.md) - Getting started guide
- [Sample Applications](../fluxgate-samples/) - Example implementations
- [GitHub Repository](https://github.com/OpenFluxGate/fluxgate)

---

## Module Documentation

| Module | Description |
|--------|-------------|
| `fluxgate-core` | Core rate limiting engine |
| `fluxgate-redis-ratelimiter` | Redis-based token bucket storage |
| `fluxgate-mongo-adapter` | MongoDB rule management |
| `fluxgate-spring-boot3-starter` | Spring Boot 3.x auto-configuration |
| `fluxgate-spring-boot2-starter` | Spring Boot 2.7.x auto-configuration |
