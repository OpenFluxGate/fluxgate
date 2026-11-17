# 0.5.0 release scope

## Included

Maven Central: `fluxgate` (parent), `fluxgate-core`, `fluxgate-redis-ratelimiter`,
`fluxgate-mongo-adapter`, `fluxgate-spring-boot3-starter`, `fluxgate-control-support`,
and `fluxgate-envoy-extauth`, all at 0.5.0. Studio UI is released by the separate Studio
repository, not as an extra Maven module. Redis KEYS removal is required for this release.

## Deferred to 0.5.1

The Boot 2 starter and its Java 11 / Boot 2 sample are absent from the release tree,
reactor, and published artifacts. Their sources remain recoverable from commit `2423129`.
Restore them on a 0.5.1 development branch only after resolving the Spring 5 dependency
security blocker and passing the full Boot 2 compatibility and dependency checks.
This is not a promise that a fixed upstream Spring 5 dependency is currently available.

Java 11 support in 0.5.0 means the framework-neutral core, Redis and Mongo SDK modules;
it does not mean Spring Boot 2 support. Spring Boot 3 requires Java 17 or newer.

## Release gates

Dependency Review retains all runtime, development and unknown scopes and its existing
high/critical threshold. No advisory is allowlisted. All current reactor modules, including
samples and Envoy, remain in the Java 21 build; Java 17 tests Boot 3 and Java 11 tests SDKs.
The required-check label must be updated explicitly by the maintainer from
`Build & Test (Java 11 - Spring Boot 2.x)` to `Build & Test (Java 11 - SDK Modules)`.
Do not bypass protections or claim the old Boot 2 check ran.

Published Maven coordinates are immutable. Once a module's 0.5.0 is published, any content
change must use a new version, such as 0.5.1; do not overwrite or blindly redeploy 0.5.0.
