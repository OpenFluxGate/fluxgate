# FluxGate Spring Boot 2 Starter

Spring Boot 2.7 auto-configuration for FluxGate, on `javax.servlet`.

> **The reference documentation for this starter is the Boot 3 one:**
> **[fluxgate-spring-boot3-starter/README.md](../fluxgate-spring-boot3-starter/README.md)**
>
> Every property, bean, SPI and behaviour documented there applies here unchanged. This page lists
> only the differences.

---

## Requirements

| | |
|---|---|
| Spring Boot | **2.7.x** |
| Java | 11+ |
| Servlet API | `javax.servlet` |

**Spring Boot 2.7 is required, not merely supported.** The starter is built with
`@AutoConfiguration`, which arrived in Boot 2.7, so it does not work on Boot 2.0 – 2.6. The
`META-INF/spring.factories` file that ships alongside
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` exists for older
tooling and documentation generators, not to extend version support; both files list the same nine
auto-configurations and a test fails the build if they diverge.

> **Spring Boot 2.7 reached OSS end of life in November 2023** and receives no further community
> patches. Plan the move to [fluxgate-spring-boot3-starter](../fluxgate-spring-boot3-starter/README.md)
> (Java 17+, `jakarta.servlet`). This starter is maintained as a mirror of the Boot 3 one so that the
> migration is a dependency swap plus the `javax` → `jakarta` import change in your own code, not a
> FluxGate rewrite.

## Spring 5 HTTP Invoker

The public Spring Web 5.3.39 dependency contains the HTTP Invoker exporter flagged by GHSA-4wrc-f8pq-fpqp. Untrusted callers can reach Java deserialization when an application publishes an HTTP Invoker service. Inventory the deployed application's servlet mappings, Spring contexts, XML and Java configuration, and third-party registrations. Remove unused endpoints, migrate callers to a non-serialization protocol, and restrict access at the service boundary while migrating. The standalone sample checks exporter bean metadata in its running context. Consumers with lazy, dynamic, or application-specific registrations need to inspect their own deployment. The dependency finding remains open. [Spring maintainer guidance](https://github.com/spring-projects/spring-framework/issues/24434) · [Advisory](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp)

Spring Framework 6 removes HTTP Invoker. Prefer the Boot 3 starter when the application supports Java 17 and Jakarta Servlet. Verify the final runtime dependency tree and the application's servlet filters after migration. [Spring Framework 6 release notes](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-6.0-Release-Notes)

WSO2 documents a fork with remoting classes removed for its named products. HeroDevs documents a separate commercial NES `no-remoting` artifact and identifies its trial as demonstration-only. FluxGate has not qualified either substitute. Before adoption, confirm version alignment and binary compatibility, the vendor's repository access and production redistribution rights, and a written price and support commitment. [WSO2 security assessment](https://security.docs.wso2.com/en/latest/security-announcements/cve-justifications/2025/CVE-2016-1000027/) · [HeroDevs release notes](https://docs.herodevs.com/spring/release-notes/spring-framework-5-3)

[한국어 운영 안내](HTTP-INVOKER.ko.md)

## Dependency

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot2-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

Add `fluxgate-redis-ratelimiter` for distributed rate limiting and `fluxgate-mongo-adapter` for rule
management, exactly as with the Boot 3 starter.

## Differences from the Boot 3 starter

| | Boot 3 starter | Boot 2 starter |
|---|---|---|
| Servlet API | `jakarta.servlet.*` | `javax.servlet.*` |
| Java | 17+ | 11+ |
| Auto-config registration | `AutoConfiguration.imports` | `AutoConfiguration.imports` + a legacy `spring.factories` listing the same nine classes |
| Health status mapping (`FluxgateHealthStatusEnvironmentPostProcessor`) | `spring.factories` | `spring.factories` (identical) |
| Problem responses | `org.springframework.http.HttpStatus` based; same RFC 9457 body | identical |
| Everything else | — | identical class names, packages, properties and behaviour |

The Java package names are the **same** (`org.fluxgate.spring.*`), so the two starters must never be
on the same classpath. Pick one.

If you implement a FluxGate SPI, the only change between the two is the servlet import:

```java
// Boot 2
import javax.servlet.http.HttpServletRequest;

// Boot 3
import jakarta.servlet.http.HttpServletRequest;
```

This affects `RequestContextCustomizer`, `RateLimitResponseWriter` and anything else that takes an
`HttpServletRequest` or `HttpServletResponse`. The core SPIs — `FluxgateRateLimitHandler`,
`RateLimitRuleSetProvider`, `KeyResolver`, `RateLimiter`, `BucketResetHandler` — live in
`fluxgate-core` and are identical.

## Quick Start

```java
@SpringBootApplication
@EnableFluxgateFilter
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

```yaml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
  ratelimit:
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
```

No handler class is needed: with a `RateLimiter` and a `RateLimitRuleSetProvider` on the context, the
starter registers `EngineBackedRateLimitHandler`.

## Sample

`fluxgate-samples/fluxgate-sample-standalone-java11` runs this starter on Java 11 / Spring Boot 2.7
with direct MongoDB + Redis integration.

---

## Related

- [Spring Boot 3 Starter](../fluxgate-spring-boot3-starter/README.md) - the full reference
- [Main README](../README.md)
- [Migrating to 0.4](../docs/en/operations/migration-0.4.md)
- [Changelog](../CHANGELOG.md)
