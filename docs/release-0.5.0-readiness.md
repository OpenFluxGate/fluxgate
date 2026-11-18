# 0.5.0 release readiness

[한국어](release-0.5.0-readiness.ko.md)

0.5.0 is in source preparation. Its scope includes Spring Boot 2 and 3, replacing Redis
`KEYS` with `SCAN`, and separately released Studio UI fixes. Both starter sources, the
Boot 2 Java 11 sample, reactor entries, and Java 11 / Boot 2 CI were restored from
`2423129`.

## Spring dependency notice

Dependency Review reported seven MVC records across the two starter POMs. Both starters
declare `spring-webmvc` with Maven `test` scope for MockMvc coverage of the rate-limit
exception mapping. Adding either starter alone does not put that MVC dependency on the
application runtime classpath.

| Starter | Test dependency | GitHub advisory | Spring notice |
| --- | --- | --- | --- |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-3chg-m5w7-qfv5](https://github.com/advisories/GHSA-3chg-m5w7-qfv5) | [CVE-2026-41845](https://spring.io/security/cve-2026-41845/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-cx7f-g6mp-7hqm](https://github.com/advisories/GHSA-cx7f-g6mp-7hqm) | [CVE-2024-38816](https://spring.io/security/cve-2024-38816/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-g5vr-rgqm-vf78](https://github.com/advisories/GHSA-g5vr-rgqm-vf78) | [CVE-2024-38819](https://spring.io/security/cve-2024-38819/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) | [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-x23c-287f-qqv5](https://github.com/advisories/GHSA-x23c-287f-qqv5) | [CVE-2026-41842](https://spring.io/security/cve-2026-41842/) |
| Boot 3 | `org.springframework:spring-webmvc:6.2.19` | [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) | [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) |
| Boot 3 | `org.springframework:spring-webmvc:6.2.19` | [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) | [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) |

GHSA-pc63-qcmh-9cmg appears on both Spring Framework lines, giving seven records for six
distinct advisories. The [release maintainer](../MAINTAINERS.md) owns the temporary
CI exception for these seven test records. It expires at **2026-12-19 00:00 UTC**.
Each PR run checks the dependency comparison. Before publishing, the release workflow
checks both starters' effective POMs again, even if the PR passed before the deadline.
Reevaluate the exception sooner if dependency versions or scopes, starter use, advisory
applicability, or patch availability change. Consumer applications must assess their own
runtime dependencies.

Boot 2 separately declares optional `org.springframework:spring-web:5.3.39` for servlet
filter integration. This declaration does not transitively install Spring Web in the
consumer application. Dependency Review classifies it as runtime and records
[GHSA-4wrc-f8pq-fpqp (CVE-2016-1000027)](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp).
Its separate exception rests on the starter's use of Spring Web for the filter and its
absence of HTTP Invoker use. Applications that supply Spring Web themselves must assess
their use of HTTP Invoker and Java deserialization independently. Spring Framework 6
removed the affected HTTP Invoker components.

### Check your application runtime

Run the command for your build from the application module, with the profiles and
dependency settings used to build the deployment.

```bash
./mvnw dependency:tree -Dscope=runtime '-Dincludes=org.springframework:spring-web,org.springframework:spring-webmvc'
```

```bash
./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.springframework:spring-web
./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.springframework:spring-webmvc
```

Check which versions actually resolve, which dependency brings them in, and which versions
are packaged or provided by your deployment. A web application can supply MVC through
`spring-boot-starter-web` or another dependency. Compare the resolved versions and enabled
features with the Spring notices above, including any separately resolved Spring WebFlux
dependencies. For Boot 2, also verify that the application and its dependencies do not
enable HTTP Invoker with untrusted input.

For the MVC records above, Spring publishes enterprise-only fixes on the same Framework
lines through 5.3.50 for Boot 2 and 6.2.20 for Boot 3. If you use enterprise patches, align
the Spring Framework modules with your supported Boot BOM and retest your application.
For the newer notices above, Spring Framework 7.0.9 is a public fix option through a
compatible Spring Boot upgrade. Treat that as a platform migration and validate the
application on the target Boot and Java versions. See the Spring notices in the table for
the fixed version and applicability of each advisory.

## Remaining release checks

The main branch requires Java 11 / Boot 2, Java 17 / Boot 3, Java 21 / all modules,
and Dependency Review. Check these results and the CodeQL findings on the release
candidate commit. The release workflow also checks the starter exception deadline
before publication.

The intended Maven artifact set is the parent, core, Redis, Mongo, both starters,
control-support, and Envoy (eight artifacts). Samples, testkit, and benchmarks are excluded
from publication. Verify Studio UI readiness in its own repository before a coordinated
release.
