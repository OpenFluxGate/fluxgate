# FluxGate Release Checklist

This document is for maintainers who are cutting a release.

## Prerequisites

* Signing key configured: `~/.gnupg` with the key registered in Maven Central.
* `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` available (or stored in
  the GitHub Actions environment named `release`).
* You are on a dedicated `release/x.y.z` branch (created from `main`) with a
  clean working tree. The release workflow triggers only on pushes to
  version-shaped branches (`release/X.Y.Z` or `release/X.Y.Z-qualifier`), so a
  release branch is required, and a branch such as `release/0.4-hardening`
  never triggers it.

---

## Release Steps

### 1. Decide the Version

Follow [Semantic Versioning 2.0.0](https://semver.org/).

```
0.X.Y  →  patch    bug fixes only
0.X+1  →  minor    new features; may include small breaking changes (0.x series)
1.0.0  →  major    full stability guarantee begins
```

### 2. Update CHANGELOG.md

Move all items under `## [Unreleased]` to a new section:

```markdown
## [0.4.0] – 2026-10-01
### Added
...
### Changed
...
### Fixed
...
### Deprecated
...
### Removed
...
### Security
...
```

Remove empty sections. At the bottom, point the `[Unreleased]` link at `v0.4.0...HEAD` and add
`[0.4.0]: https://github.com/OpenFluxGate/fluxgate/compare/v0.3.7...v0.4.0`.
Commit the CHANGELOG update to the release branch:

```bash
git commit -am "chore: release 0.4.0 – update CHANGELOG"
```

### 3. Bump the Version

Use Maven Versions plugin — do **not** edit pom files manually:

```bash
./mvnw versions:set -DnewVersion=0.4.0 -DgenerateBackupPoms=false
git commit -am "chore(release): bump version to 0.4.0"
```

Verify the parent and all child modules show the new version:

```bash
./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout
```

### 4. Run the Full Test Suite Locally

```bash
./mvnw -B verify -Predis-cluster-it -Dfluxgate.redis.cluster.require=true
```

`-Dfluxgate.redis.cluster.require=true` turns the cluster ITs from "skip when unreachable" into
failures; the release workflow passes it, so a release cannot go out with the cluster tests skipped.
(The workflow also pins `maven-wrapper.jar` by SHA-256 before running `mvnw`; the
`wrapperSha256Sum` property is not used because macOS's BSD `sha256sum` rejects it.)

All tests must pass. Resolve any failures before tagging. The Redis Cluster tests need a cluster on
`localhost:7100-7105` (`docker compose -f docker/redis-cluster.yml up -d`); the release workflow
starts one itself.

### 5. Tag and Push the Release Branch

Create a signed tag on the release branch, then push **both the branch and
the tag** to the remote. The release workflow (`.github/workflows/release.yml`)
triggers on pushes to branches matching `release/[0-9]+.[0-9]+.[0-9]+` or
`release/[0-9]+.[0-9]+.[0-9]+-*`; pushing only the tag is not enough to trigger
it.

```bash
git tag -s v0.4.0 -m "Release 0.4.0"
git push origin release/0.4.0 v0.4.0
```

Pushing the branch triggers `.github/workflows/release.yml`, which:

1. Derives the version from the branch name (`release/0.4.0` → `0.4.0`) and refuses anything
   that is not `release/X.Y.Z[-qualifier]` (no `SNAPSHOT`).
2. Verifies that the tag `v<version>` exists and points at the branch HEAD. If the tag is missing
   or points elsewhere, the job fails before anything is built or deployed. This is why the branch
   and the tag are pushed together. (The workflow does not check the tag's GPG signature.)
3. Sets the version from the branch name with `versions:set` (a no-op when [§3 Bump the Version](#3-bump-the-version) already bumped
   the poms to the same version) and runs `./mvnw -B verify
   -Predis-cluster-it -Dfluxgate.redis.cluster.require=true` against MongoDB, Redis and a Redis
   Cluster service container: unit tier, integration tier including the Redis Cluster tests (which
   fail rather than skip when the cluster is unreachable), and the coverage gates.
4. Signs and deploys (`-Prelease`) to Maven Central via the Sonatype Central Publishing Plugin
   exactly these artifacts: the parent POM `fluxgate`, `fluxgate-core`, `fluxgate-redis-ratelimiter`,
   `fluxgate-mongo-adapter`, `fluxgate-spring-boot2-starter`, `fluxgate-spring-boot3-starter` and
   `fluxgate-control-support`. `fluxgate-testkit`, `fluxgate-benchmarks`, `fluxgate-samples` and
   every sample module set `maven.deploy.skip` (directly or through `fluxgate-samples`) and are
   listed in the plugin's `excludeArtifacts` in the root `pom.xml`.
5. Creates a GitHub Release with auto-generated release notes and attaches the module jars
   (core, redis-ratelimiter, mongo-adapter, boot2-starter, boot3-starter, control-support).
6. Generates the CycloneDX SBOM (`target/bom.json`) and attaches it to the GitHub Release.

### 6. Verify the GitHub Release

After the workflow succeeds:

* Open the GitHub Release page for `v0.4.0`.
* Confirm all JARs (core, redis, mongo, boot2-starter, boot3-starter, control-support) are listed.
* Confirm `bom.json` is attached as a release asset.
* Confirm the Maven Central listing shows the new version (allow up to 30 minutes
  for propagation).

### 7. SBOM Verification

```bash
# Download the attached bom.json from the GitHub Release and validate locally
python3 -c "import json; d=json.load(open('bom.json')); print(d['bomFormat'], d['specVersion'], len(d['components']), 'components')"
# Expected output: CycloneDX 1.5 <N> components
```

### 8. Post-Release

* Merge the release branch back into `main` through a PR (`release/0.4.0` → `main`). Do not
  push to `main` directly.
* Reset the version to the next SNAPSHOT on a branch from the updated `main`, and merge it through
  a PR as well. In the same commit, re-add an empty `## [Unreleased]` heading at the top of
  `CHANGELOG.md` (the compare links were already updated in step 2):

```bash
git checkout main && git pull
git checkout -b chore/0.5.0-snapshot
./mvnw versions:set -DnewVersion=0.5.0-SNAPSHOT -DgenerateBackupPoms=false
# edit CHANGELOG.md: add an empty "## [Unreleased]" heading at the top
git commit -am "chore: prepare for next development iteration 0.5.0-SNAPSHOT"
git push origin chore/0.5.0-snapshot
# open a PR from chore/0.5.0-snapshot into main and merge it
```

* Merge the two PRs back-to-back: between them `main` is at the release version, so nothing else
  should land on `main` until the SNAPSHOT PR is in.

* Announce on GitHub Discussions and update the FluxGate Studio README if the
  release includes API changes.

---

## Emergency Patch Release

If you need to patch a released version while `main` already has unrelated in-progress work:

1. Create a branch from the release tag: `git checkout -b release/0.4.1 v0.4.0`
2. Cherry-pick the fix(es): `git cherry-pick <sha>`
3. Follow steps 2–7 above on that branch. Skip step 8: it resets `main` to the next SNAPSHOT,
   which a patch branch must not do.
4. Backport the CHANGELOG entry and fix to `main` via a separate PR.

---

## Rollback

Maven Central deployments cannot be deleted. If a broken release reaches Central:

1. Immediately tag a new patch with the fix (`v0.4.1`).
2. Add a `### Security` or `### Fixed` note at the top of the new release notes
   describing the problem.
3. Open a GitHub Security Advisory if the issue is a security defect.
