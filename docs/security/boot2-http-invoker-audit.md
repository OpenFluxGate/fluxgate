# Boot 2 HTTP Invoker exposure audit

This audit is evidence, not a vulnerability fix, security gate exemption, or assurance about every consumer deployment. GHSA-4wrc-f8pq-fpqp remains applicable to the included Spring Web artifact.

The test starts a Boot 2.7 / Spring 5.3 servlet application context with the starter's nine registered auto-configurations, MVC enabled, and Mongo/Redis/reload disabled. It checks that no HttpInvokerServiceExporter bean is present. A positive control registers an application-supplied exporter and verifies that Spring's HandlerMapping resolves its test endpoint. No serialized payload is sent and no network listener is opened. A classpath test proves that the flagged class is still present, rather than excluded to make the test pass.

Run from the repository root with a build JDK >=17 and Java 11 test runtime:

```sh
BOOT2_JAVA_HOME=/path/to/jdk11 bash scripts/security/audit-boot2-http-invoker.sh
```

The script records the Maven dependency tree under target/security-evidence. It does not change the dependency-review workflow, versions, scopes or allowlists. It verifies the expected dependency family, NOT that this version is free of vulnerabilities.

Limitations: this is a mock-servlet context, not an embedded network-server deployment. It does not enumerate arbitrary URL paths, test enabled external infrastructure, application-owned parent/child or lazy/dynamic contexts, custom deserializers, proxies, or production routing. Consumers must repeat exposure checks against their actual configuration and service inventory. No absence of exploitability is asserted for untested applications. This Boot2-specific test has no equivalent removed HttpInvoker class to import on Boot3; it is not a replacement for the independent Boot3 test/security matrix.

Sources: https://github.com/advisories/GHSA-4wrc-f8pq-fpqp and https://github.com/spring-projects/spring-framework/issues/24434 .
