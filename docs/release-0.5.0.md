# FluxGate Core 0.5.0

[한국어](release-0.5.0.ko.md)

## Overview

FluxGate 0.5.0 brings the rate-limit engine to Spring Boot 2.7, Spring Boot 3 and services behind Envoy. The starters can wire an engine-backed handler when the application supplies a limiter and a rule-set provider. Envoy external authorization lets backend services share the same policy without embedding the Java SDK.

## Maven modules

Use group `io.github.openfluxgate` and version `0.5.0`. Select the starter that matches the application's Spring Boot and Java versions.

| Use | Artifact | Packaging |
| --- | --- | --- |
| Parent | `fluxgate` | POM |
| Rate-limit engine | `fluxgate-core` | JAR |
| Rule storage | `fluxgate-mongo-adapter` | JAR |
| Distributed counters | `fluxgate-redis-ratelimiter` | JAR |
| Spring Boot 2.7, Java 11+ | `fluxgate-spring-boot2-starter` | JAR |
| Spring Boot 3, Java 17+ | `fluxgate-spring-boot3-starter` | JAR |
| Rule-change notifications | `fluxgate-control-support` | JAR |
| Envoy authorization service | `fluxgate-envoy-extauth` | JAR |

A Spring Boot 3 application can use this dependency.

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

## Upgrading from 0.3.x

The previous full module release was 0.3.7. Version 0.3.8 updated the Redis module alone. Align the FluxGate modules used by your application at 0.5.0, including any explicit Redis override.

- **Plan a maintenance window for shared quotas.** The Redis key format and token-bucket timestamp units change. Old and new writers for the same quota spend from separate buckets. Fence and drain every writer, verify a restore, and decide how to preserve or settle outstanding usage before admitting traffic. Rollback also needs a writer fence and a recovery plan. [Cutover and rollback](operations/shared-redis-legacy-migration.md)
- **Check custom Redis providers.** The built-in providers enumerate keys incrementally with `SCAN`. For counter resets, custom providers must implement `scanKeys`. The list-returning API still holds matching keys in memory. [Compatibility details](../CHANGELOG.md)
- **Choose a policy refresh mode.** `LEGACY_RESET` remains the default. With a compatible authoritative provider, opt-in `PUBLISHED_EPOCH` preserves counters on normal policy changes and uses a new epoch on an explicit reset. [Configuration and prerequisites](operations/shared-redis-published-epochs.md)
- **Review Spring dependencies.** Both starters use Spring MVC in test scope. Boot 2 also declares optional Spring Web 5.3.39 for servlet filter integration. Check the resolved runtime dependencies of the consuming application. The seven test-scope advisory exceptions expire on 2026-12-19 at 00:00 UTC. [Spring dependency notice and runtime checks](release-0.5.0-readiness.md#spring-dependency-notice)

## Validation

The merged Core changes passed Java 11, 17 and 21 CI. The separate release workflow verifies the signed tag's target, runs the Redis Cluster integration suite, rechecks advisory expiry, and signs the Maven artifacts. [Java CI for the merge commit](https://github.com/OpenFluxGate/fluxgate/actions/runs/44647301397) · [Release checklist](../RELEASING.md)
