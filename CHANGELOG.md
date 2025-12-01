# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial release of FluxGate rate limiting framework
- **fluxgate-core**: Core rate limiting engine with Bucket4j integration
  - Token bucket algorithm implementation
  - Multi-band rate limiting support (e.g., 100/sec + 1000/min)
  - Flexible key resolution strategies (IP, User ID, API Key)
  - Configurable policies (REJECT_REQUEST, WAIT_FOR_REFILL)
  - Request context abstraction

- **fluxgate-redis-ratelimiter**: Distributed rate limiting with Redis
  - Atomic Lua script for thread-safe token consumption
  - Uses Redis server time to prevent clock drift issues
  - Integer arithmetic only (no floating-point precision loss)
  - Read-only operations on rejection for fair rate limiting
  - Automatic TTL management with safety margins
  - Connection pooling support via Lettuce

- **fluxgate-mongo-adapter**: MongoDB integration for rule management
  - CRUD operations for rate limit rules
  - Rule set persistence and retrieval
  - Event recording for rate limit decisions
  - Converter utilities for document mapping

- **fluxgate-spring-boot-starter**: Spring Boot auto-configuration
  - `@EnableFluxgateFilter` annotation for easy integration
  - Auto-configuration for Redis and MongoDB
  - `FluxgateRateLimitFilter` for automatic rate limiting
  - Handler-based architecture (Direct Redis, HTTP API)
  - Configurable URL patterns (include/exclude)
  - Property-based configuration via `fluxgate.*`

- **fluxgate-testkit**: Integration testing utilities
  - Test containers setup for Redis and MongoDB
  - Integration test base classes
  - Test utilities for rate limiter verification

- **fluxgate-samples**: Sample applications
  - `fluxgate-sample-redis`: Rate limit service with Redis backend
  - `fluxgate-sample-mongo`: Rule management with MongoDB
  - `fluxgate-sample-filter`: Client app with auto rate limiting
  - `fluxgate-sample-api`: REST API for rate limit checking

### Architecture
- Modular design with clear separation of concerns
- Support for two deployment patterns:
  - Direct Redis access (embedded mode)
  - HTTP API mode (centralized rate limiting service)
- Pluggable handler architecture for custom implementations
- Fail-open pattern for graceful degradation

### Documentation
- Comprehensive README for each module
- Integration testing guide
- Extension guide for custom implementations
- Architecture diagrams and deployment patterns

## [0.0.1-SNAPSHOT] - 2024-XX-XX

### Initial Development
- Project structure setup
- Maven multi-module configuration
- CI/CD pipeline with GitHub Actions
- Docker Compose for local development

---

## Release Notes Format

### Types of Changes

- **Added** for new features
- **Changed** for changes in existing functionality
- **Deprecated** for soon-to-be removed features
- **Removed** for now removed features
- **Fixed** for any bug fixes
- **Security** for vulnerability fixes

### Version Format

`MAJOR.MINOR.PATCH`

- **MAJOR**: Incompatible API changes
- **MINOR**: New functionality (backwards compatible)
- **PATCH**: Bug fixes (backwards compatible)
