# 0.5.0 release readiness (not published)

The latest release requirement includes both Spring Boot 2 and Spring Boot 3, Redis KEYS
removal and the separately released Studio UI fixes. The earlier proposal to defer Boot 2
to 0.5.1 is cancelled. Both starter sources, the Boot 2 Java 11 sample, reactor entries and
real Java 11 / Boot 2 CI are restored from `2423129`; Redis SCAN changes are preserved.

## Unresolved security gates

- Boot 2: `org.springframework:spring-web:5.3.39`, GHSA-4wrc-f8pq-fpqp.
- Boot 3: `org.springframework:spring-webmvc:6.2.19`, GHSA-pc63-qcmh-9cmg
  and GHSA-j9f9-w8pj-32f8. Official Spring notices identify 6.2.20 as enterprise-only;
  public Spring 7 fixes must not be forced into Boot 3 as a compatibility patch.
- CodeQL reported five high-severity alerts on the preceding PR head. Restoring sources
  does not fix or dismiss them; investigate and validate the exact final commit.

The successful Java 11 SDK-only checks on the temporary reduced-scope branch did NOT test
Boot 2. Earlier green build results do not establish release readiness for this restored tree.
Run the full Java 11 / Boot 2, Java 17 / Boot 3 and Java 21 matrix, Dependency Review and
CodeQL on the final head. Do not merge or publish while these gates are unresolved.
No advisory allowlist, scan-scope reduction, or protection bypass is part of this restoration.
If branch protection currently names the temporary SDK-only context, its restoration to the
real Boot 2 context requires a separately approved settings change; this commit changes no settings.

The intended Maven artifact set is the parent, core, Redis, Mongo, both starters,
control-support and Envoy (eight artifacts). Samples/testkit/benchmarks are not published.
Studio UI readiness must be checked in its own repository before the coordinated release.
