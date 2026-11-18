# Spring integration support and consumer security checklist

[한국어](spring-support-policy.ko.md)

## 0.5.0 policy and release boundary

For new integrations, prefer the Boot 3 path over Boot 2. Keep Boot 2 as a legacy
compatibility path for existing Java 11 / javax applications. This recommendation
does not claim that the currently pinned Boot 3 line is upstream-maintained or
free of vulnerabilities. See the exact versions and status in the
[compatibility matrix](../../GOVERNANCE.md#supported-spring-boot-and-java-matrix).

Compatibility is not a security-support guarantee. FluxGate does not extend
upstream OSS maintenance, bundle commercial patches, or promise a completed LTS
contract. Both integration paths remain in the 0.5.0 scope; this policy does not
defer Boot 2 to 0.5.1. Existing security findings and required checks remain in
force. This document is not a CVE fix, exception approval, or release approval.


## Why this recommendation and why retain Boot 2?

Boot 3 is the preferred integration direction because it uses the Java 17 /
Jakarta platform and Spring 6 removed the legacy HTTP Invoker functionality
involved in CVE-2016-1000027. This is a reason to prefer that architecture, not a
claim that every Boot 3 release is currently supported or all findings are fixed.
The current pinned versions and other unresolved advisories still need review.

Boot 2 remains for consumers that cannot immediately move their Java 11 / javax
applications. Keeping compatibility avoids forcing an unrelated platform migration
during a FluxGate upgrade. The trade-off is an explicit legacy dependency risk,
application-specific exposure assessment and a migration plan, not an equivalent
upstream security-support promise.

## Public product responses: examples, not transferable guarantees

| Product | Published response | Important boundary |
| --- | --- | --- |
| Atlassian Bitbucket Data Center 8.19.7 / 9.0 | Verified no use of implicated classes, forked Spring, removed remoting packages and shipped the modified dependency. | Plugins using removed packages require changes; compatibility was not unconditional. |
| WSO2 API Manager / Identity Server and listed WSO2 products | Forked Spring 5.3.31 through 5.3.39 and removed vulnerable classes; its official justification identifies 5.3.31-wso2v1 onward as the modified line. | The not-affected conclusion concerns WSO2's bundled fork and product use cases, not original Spring 5.3.39. |
| IBM OpenPages for Cloud Pak for Data | Shipped spring-web-5.3.39.2504.jar / 5.3.39.2410.jar with unused RCE code removed; recommends Cloud Pak for Data 4.7+ and OpenPages service 8.302.2+. | These are IBM-remediated product artifacts, not a statement that the original public 5.3.39 jar fixes the issue. |

Official sources:
[Bitbucket 8.19 release notes](https://confluence.atlassian.com/bitbucketserver/bitbucket-data-center-8-19-release-notes-1354498386.html),
[Bitbucket 9.0 release notes](https://confluence.atlassian.com/bitbucketserver/bitbucket-data-center-9-0-release-notes-1369277827.html),
[WSO2 CVE justification](https://security.docs.wso2.com/en/latest/security-announcements/cve-justifications/2025/CVE-2016-1000027/),
[WSO2 removal patch](https://github.com/wso2/wso2-spring-framework/pull/1/files),
[IBM OpenPages security bulletin](https://www.ibm.com/support/pages/security-bulletin-ibm-openpages-ibm-cloud-pak-data-vulnerable-spring-web-unsafe-deserialization-cve-2016-1000027-0).

These are vendor-published product assessments, not independent penetration tests.
Their common pattern is product-specific review plus removal of implicated code,
not simply renaming a dependency version or ignoring a scanner.

A complete product vendor controls its shipped application and dependency bundle.
FluxGate is also embedded as a library in applications it does not control.
Silently replacing a consumer's Spring installation could break unrelated
features, introduce conflicting Spring modules or invalidate their support
contract. FluxGate therefore does not automatically substitute a vendor fork.
A future hardened distribution or vendor-patch option requires explicit selection,
provenance, licensing/redistribution and maintenance review, and compatibility
testing of the full application. Commercial support by itself is not evidence
that this particular CVE is patched.

## Known boundaries

- Boot 2 includes original Spring Web 5.3.39. GHSA-4wrc-f8pq-fpqp /
  CVE-2016-1000027 remains flagged. Untrusted Java deserialization through HTTP
  Invoker can lead to remote code execution. Merely loading Spring Web does not
  prove exploitability; merely not finding an exporter in one context does not
  prove every consumer safe.
- Spring 6 removed the implicated HTTP Invoker functionality. Do not force it
  into Boot 2 as an isolated dependency override: Java / javax / Jakarta platform
  compatibility must be preserved.
- Boot 3 is not a blanket clearance. The current readiness record also identifies
  Spring Web MVC 6.2.19 findings GHSA-pc63-qcmh-9cmg and GHSA-j9f9-w8pj-32f8.
  Preserve and reassess these findings against the exact release graph; this
  policy does not declare them remediated.
- Legacy samples can resolve additional Spring Data, SnakeYAML and logging
  advisories. A clean SDK-only scan is not a clean application or sample scan.
- The bounded Boot 2 exposure work in
  [draft PR #83](https://github.com/OpenFluxGate/fluxgate/pull/83) checks a mock
  servlet context and a positive detection control, not a deployed consumer.
  It is proposed evidence, not a merged runtime protection or security clearance.

See [SECURITY.md](../../SECURITY.md) and
[release readiness](../release-0.5.0-readiness.md) for remaining release conditions.

## Consumer checklist

Before adopting or upgrading either integration path:

- [ ] Record the actual runtime JDK, Boot/Spring versions, active profiles and
  complete resolved application dependencies / SBOM, including transitive
  dependencies. Review SDK, server and samples separately.
- [ ] Inventory HTTP Invoker exporters, including `HttpInvokerServiceExporter`,
  `SimpleHttpInvokerServiceExporter`, application-defined exporters and custom
  Java deserializers. Do not rely only on string searches in FluxGate sources.
- [ ] Inspect Java configuration, XML, web.xml, direct servlet registrations,
  library auto-configuration, parent/child contexts and lazy/dynamic registration.
- [ ] Check active beans, handler mappings, servlet mappings and actual ingress
  routes in the deployed configuration. A default mock context cannot establish
  the absence of consumer-defined endpoints.
- [ ] Ensure untrusted input cannot reach Java deserialization endpoints.
  Authentication or an internal network alone is not proof of safety.
  Remove unused HTTP Invoker endpoints; plan a non-Java-serialization protocol
  such as JSON for services that still need remote invocation.
- [ ] Run the applicable Boot 2 / Java 11 and Boot 3 / Java 17-or-later functional,
  startup and integration tests. Avoid mixing incompatible Spring major versions.
- [ ] Review other advisories across the entire deployed graph and enabled
  features. A conclusion about this CVE is not a whole-application clearance.
- [ ] Keep deployment-specific evidence, review owner, residual risks and a
  review date. Mark unverified conditions as unverified, not not-affected.
- [ ] Repeat the assessment after dependency, profile, endpoint, routing or
  application-configuration changes. Plan migration to an upstream-maintained
  platform or evaluate a supported vendor patch with explicit provenance,
  compatibility and redistribution rights.

## Evidence and security-gate decisions

A compatibility test pass, a bounded exposure test pass, a dependency scan pass,
and release approval are separate facts. Publish each result with its commit,
scope and limitations. Do not claim a package CVE is fixed while the implicated
original functionality remains in the artifact.

Documentation alone cannot turn the existing Dependency Review failure into a
pass. Do not lower severity thresholds, narrow scan scopes, remove required
checks, hide dependencies, or allowlist this advisory as part of this policy.
A future exception requires an explicit, separate decision recording the precise
advisory and artifact scope, justification, owner, review/expiry condition and
residual risk. No such exception is approved by this document.

The latest source-preparation record must be checked against the final commit
before merge or publication. A stacked PR with no checks is not evidence that
required release checks passed.

## References

- [GitHub advisory: affected original Spring Web below 6.0.0](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp)
- [Spring maintainers: HTTP Invoker exposure and unsafe deserialization](https://github.com/spring-projects/spring-framework/issues/24434)
- [Spring Boot 2.7.18 and end of OSS support](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/)
