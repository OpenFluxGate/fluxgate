# FluxGate Release Checklist

This document is for maintainers who are cutting a release.

## Prerequisites

* Signing key configured: `~/.gnupg` with the key registered in Maven Central.
* `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` available (or stored in
  the GitHub Actions environment named `release`).
* You are on a dedicated `release/x.y.z` branch (created from `main`) with a
  clean working tree. The release workflow triggers on pushes to `release/*`
  branches, so a release branch is required.

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

Remove empty sections. Update the `[Unreleased]` diff link at the bottom.
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
./mvnw -B verify -Predis-cluster-it
```

All tests must pass. Resolve any failures before tagging.

### 5. Tag and Push the Release Branch

Create a signed tag on the release branch, then push **both the branch and
the tag** to the remote. The release workflow (`.github/workflows/release.yml`)
triggers on `push: branches: [release/*]`; pushing only the tag is not enough
to trigger it.

```bash
git tag -s v0.4.0 -m "Release 0.4.0"
git push origin release/0.4.0 v0.4.0
```

Pushing the branch triggers `.github/workflows/release.yml`, which:

1. Extracts the version from the branch name (`release/0.4.0` → `0.4.0`).
2. Builds and runs all tests (including integration tests).
3. Signs and deploys all non-sample modules to Maven Central via the
   Sonatype Central Publishing Plugin.
4. Generates the CycloneDX SBOM (`target/bom.json`) and attaches it to
   the GitHub Release.
5. Creates a GitHub Release with auto-generated release notes.

### 6. Verify the GitHub Release

After the workflow succeeds:

* Open the GitHub Release page for `v0.4.0`.
* Confirm all JARs (core, redis, mongo, boot2-starter, boot3-starter) are listed.
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

* Push the version bump commit back to `main` (if you worked on a release branch,
  open a PR and merge it).
* Reset the version back to the next SNAPSHOT:

```bash
./mvnw versions:set -DnewVersion=0.5.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: prepare for next development iteration 0.5.0-SNAPSHOT"
git push origin main
```

* Announce on GitHub Discussions and update the FluxGate Studio README if the
  release includes API changes.

---

## Emergency Patch Release

If you need to patch a released version while `main` already has unrelated in-progress work:

1. Create a branch from the release tag: `git checkout -b release/0.4.1 v0.4.0`
2. Cherry-pick the fix(es): `git cherry-pick <sha>`
3. Follow steps 2–8 above on that branch.
4. Backport the CHANGELOG entry and fix to `main` via a separate PR.

---

## Rollback

Maven Central deployments cannot be deleted. If a broken release reaches Central:

1. Immediately tag a new patch with the fix (`v0.4.1`).
2. Add a `### Security` or `### Fixed` note at the top of the new release notes
   describing the problem.
3. Open a GitHub Security Advisory if the issue is a security defect.
