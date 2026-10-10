# FluxGate Governance

## Overview

FluxGate is an open-source project hosted under the
[OpenFluxGate](https://github.com/OpenFluxGate) GitHub organisation. This
document describes how decisions are made, how to contribute, the release
cadence, and how users can rely on the project over time.

---

## Current Status: Single-Maintainer Project

FluxGate is currently maintained by one person ([@rojae](https://github.com/rojae)).
Users should be aware of what this means in practice:

* There is no committee. Architectural decisions and releases are made by the
  lead maintainer.
* Pull request reviews are best-effort (typically one to two weeks).
* The project follows [semantic versioning](https://semver.org/), so breaking
  changes are always in major version bumps. The 0.x series is pre-1.0; minor
  bumps in 0.x may include breaking API changes, which are documented in
  [CHANGELOG.md](CHANGELOG.md).
* If you need guaranteed response times or commercial support, FluxGate is
  not the right choice.

---

## Decision Process

| Type | Process |
|---|---|
| Bug fixes and minor improvements | Pull request; maintainer approval required |
| New features | Issue discussion first (feature request template), then PR |
| Breaking API changes | Issue discussion, 30-day comment period, documented in CHANGELOG |
| Security fixes | Private disclosure → patch → coordinated release (see [SECURITY.md](SECURITY.md)) |
| Governance changes | PR to this file; 14-day comment period |

---

## Path to Maintainership

The project welcomes additional maintainers. To be considered:

1. Contribute at least three non-trivial merged pull requests.
2. Demonstrate familiarity with the codebase and coding standards.
3. Engage constructively in issue discussions over at least 60 days.
4. Be nominated (by yourself or an existing maintainer) via a GitHub Discussion.

The lead maintainer makes the final decision. Maintainer status can be
revoked if a maintainer is inactive for 12 months without notice, or for
violation of the [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Release Cadence

* **Patch releases** (0.x.y → 0.x.y+1): as needed for bug and security fixes,
  typically within two weeks of a confirmed issue.
* **Minor releases** (0.x → 0.x+1): roughly every two to three months, when a
  meaningful set of features or improvements is ready. No fixed schedule.
* **Major releases**: no firm timeline. 1.0 is planned when the API is stable
  enough to commit to long-term compatibility.

Release notes are in [CHANGELOG.md](CHANGELOG.md). Each release tag is signed
with a GPG key and published to Maven Central.

---

## Deprecation and Support Policy

### Semantic Versioning Promise

FluxGate uses [Semantic Versioning 2.0.0](https://semver.org/).

* **Patch** (Z): backwards-compatible bug fixes only.
* **Minor** (Y, in 1.x+): backwards-compatible new functionality. In 0.x, a
  minor bump may include small breaking API changes; these are always documented
  under `### Breaking Changes` in CHANGELOG.md.
* **Major** (X): breaking changes.

### Deprecation Lifecycle

1. An API element is annotated `@Deprecated` with a Javadoc `@deprecated` note
   explaining the replacement.
2. The deprecation is mentioned under `### Deprecated` in the CHANGELOG entry
   for the release where it appears.
3. The deprecated element is **not removed earlier than the next minor release
   after the release that documented the deprecation**. In concrete terms: if
   an element is deprecated in 0.5.0, it will not be removed before 0.6.0, and
   the removal will itself be a CHANGELOG entry under `### Removed`.
4. In the pre-1.0 (0.x) series, this gives at least one minor-release cycle of
   warning. After 1.0, the guarantee is at least one minor release in the same
   major line.

### Spring Boot 2.7 Starter: Maintenance-Only Mode

**`fluxgate-spring-boot2-starter` is in maintenance-only mode.**

Spring Boot 2.7 reached OSS end-of-life in November 2023. The boot2 starter is
kept in this repository as a convenience for teams that have not yet migrated,
but it will receive:

* Security fixes for issues in FluxGate's own code.
* Bug fixes where the fix requires no new Spring or Jakarta API.

It will **not** receive:

* New features.
* Updates to match new Spring Boot 2.x releases (there will be none).
* Dependency upgrades beyond security patches.

Teams are strongly encouraged to migrate to `fluxgate-spring-boot3-starter` on
Spring Boot 3.2+. The boot2 starter may be removed in a future major release
without further notice beyond the deprecation policy above.

### Supported Spring Boot and Java Matrix

| FluxGate | Spring Boot | Java | Status |
|---|---|---|---|
| 0.3.x | 3.2–3.3 | 17, 21 | Active (current) |
| 0.3.x | 2.7 | 11, 17 | Maintenance-only |

Java 11 is required to compile the core library and the boot2 starter. Java 17
is required to run the boot3 starter. Java 21 is required for the sample
standalone-java21 module.

### End-of-Life

A release line reaches end-of-life when the corresponding Spring Boot line does
(see the [Spring Boot support policy](https://spring.io/projects/spring-boot#support)).
EOL will be announced **at least 90 days before the final patch release**,
giving teams time to plan their migration. The announcement is made in a
CHANGELOG entry, an update to this table, and a pinned GitHub Discussion post.

---

## Code of Conduct

All participants are expected to follow the
[Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
