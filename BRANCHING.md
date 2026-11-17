# Branch, Pull Request, and Release Policy

> Proposed contributor policy for 0.5.0. This document does not change GitHub
> rulesets, CI triggers, secrets, tags, or deployment settings. The enforcement
> gaps in section 7 must not be mistaken for completed configuration.

## 1. Principles

- Use small, single-purpose pull requests; keep unrelated changes separate.
- Preserve existing release lines. Never merge the 0.5.0 integration branch
  wholesale into `maintenance/0.3.x`.
- Review and test the exact final commit before release. Skipped, blocked, and
  never-run checks are not passes.
- Merge approval and deployment approval are separate decisions.
- Never bypass a failing security check, retag a published version, or force-push
  a protected integration or release ref to make a release appear successful.
- Maintain compatibility explicitly: document migrations and mixed-version
  restrictions instead of claiming compatibility that has not been tested.

## 2. Branch names and targets

Use lowercase ASCII, hyphen-separated descriptions, and one clear purpose.
An issue number is optional: `fix/123-redis-timeout`.

| Branch | Purpose | PR target |
| --- | --- | --- |
| `main` | Reviewed integration baseline | No direct commits |
| `feat/<description>` | New functionality | `main`, or an explicitly selected active integration branch |
| `fix/<description>` | Bug fix | The affected integration branch |
| `perf/<description>` | Performance improvement | The affected integration branch |
| `docs/<description>` | Documentation | The branch whose behavior is documented |
| `test/<description>`, `refactor/<description>` | Tests or restructuring | The affected integration branch |
| `ci/<description>`, `chore/<description>` | Automation or maintenance | The affected integration branch |
| `hotfix/<version>-<description>` | Minimal urgent fix | Corresponding `maintenance/X.Y.x` |
| `maintenance/X.Y.x` | Supported historical release line | Backport/hotfix PRs only |
| `release/X.Y.Z[-qualifier]` | Deployment ref, not a working branch | Created only after release approval |
| `dependabot/**` | Dependency automation | Review the bot's chosen base and compatibility |

Existing `feature/**`, `development/**`, and other historical branch names are
grandfathered. Do not rename or delete open branches merely to match this policy.
Do not use `release/*` for experiments, hardening work, or ordinary preparation:
older workflows trigger deployment for every branch under that prefix.

### Current 0.5.0 integration

`feature/lts-0.5.0` (PR #70) remains the active 0.5.0 integration branch.
Create scoped branches from its current head and target it when a change depends
on 0.5.0 code. Label the PR as stacked and link #70. After the parent merges,
verify the resulting diff and retarget any remaining PRs deliberately.

`feature/lts-1.0.0` / PR #67 is historical overlapping work, not an independent
release to merge automatically. Reconcile unique changes before any closure or
merge. The branch name `lts` does not itself promise a support period.

The 0.3.x maintenance line remains separate. After a hotfix, port the relevant
logic to the active 0.5.0 line through another PR; do not assume a clean
cherry-pick proves behavioral compatibility.

## 3. Pull request contract

1. Fetch the intended base and create a scoped branch. Do not overwrite another
   contributor's work or reuse a release ref.
2. Open a draft PR early. Include:
   - problem, scope, target version, and linked issue/parent PR;
   - implementation and alternatives where correctness is nontrivial;
   - compatibility, data migration, security, and rollback impact;
   - commands, exact tested SHA, counts including skipped tests, and evidence;
   - explicit remaining blockers and release prerequisites.
3. Use Conventional Commits for the final squash title, for example
   `perf(redis): avoid full keyspace scans on policy reload`.
   Document breaking changes explicitly; version 0.x is not permission to hide
   incompatibility.
4. Resolve review threads and rerun affected checks after changes or conflicts.
5. Squash-merge ordinary feature/fix/docs PRs into their target. Use a meaningful
   title and retain issue/evidence links in the final commit.
6. Do not rewrite a branch used by others. For private PR branches, coordinate
   any history rewrite and use lease-based protection, never blind force pushes.
7. Delete a short-lived branch only after merge and after confirming it is not
   the base of another PR. Keep maintenance and immutable release refs.

A separate reviewer is preferred. The repository currently has a zero-approval
ruleset; do not pretend self-review is an independent GitHub approval or
configure a mandatory reviewer count that a sole maintainer cannot satisfy.
Any exception and its rationale must be visible in the PR; CI and security
failures are not waived by this paragraph.

## 4. Test and security gates

Follow [CONTRIBUTING.md](CONTRIBUTING.md) and the workflows on the target branch.
The main ruleset currently requires these exact contexts:

- `Build & Test (Java 11 - Spring Boot 2.x)`
- `Build & Test (Java 17 - Spring Boot 3.x)`
- `Build & Test (Java 21 - All Modules)`
- `Review dependency changes`

Require the applicable checks on the final PR head with the current base.
Do not remove or rename required contexts without coordinating their workflow
and ruleset changes. A workflow excluded by branch/path filters can leave a
required check permanently pending.

For 0.5.0 release candidates, also retain evidence for:

- Java formatting, unit/integration suites, Boot 2/3 compatibility, and coverage;
- real standalone and Cluster Redis tests, including mandatory-cluster checks;
- shared Redis policy reload correctness, stale writers, notification loss,
  duplicate/reordered messages, TTL, and multi-Pod behavior;
- no Redis KEYS calls in the supported implementation paths;
- security findings, including transitive dependencies and unresolved risks;
- Central publisher response regression checks and release artifact scope.

Focused unit tests are not a substitute for Cluster, performance, or production
recovery tests. Do not use a production Redis or database for destructive tests.
Report unverified conditions clearly. Known blocking security findings remain
blocking; a branch naming rule does not resolve them.

## 5. Release and tag protocol

Read [RELEASING.md](RELEASING.md) for the actual workflow. The 0.5.0 workflow is
branch-push driven, not tag-only driven.

1. Prepare version, changelog, release notes, and artifact scope through a normal
   PR on a non-release branch. Record the final reviewed commit and test evidence.
2. Resolve release blockers and obtain the applicable release approval.
3. Create `release/0.5.0` and signed tag `v0.5.0` at the same final approved
   commit. If source changes are needed, return to review and testing before
   initiating publication. Do not push a release ref merely to test CI.
4. Push branch and tag together; prefer an atomic push so neither ref is created
   alone. The 0.5.0 workflow validates tag-to-HEAD equality, but does not itself
   verify the tag's GPG signature. Verify that signature separately.
5. Respect the `release` environment approval process. Neither creating a tag
   nor passing ordinary PR checks constitutes publication.
6. Verify Maven Central publication and downloadable artifacts, signatures,
   checksums, metadata, GitHub Release, and documented version coordinates.
   Upload acceptance is not proof of public availability.
7. If Actions fails after upload, inspect the Central deployment before retrying.
   A response-parser error or delayed publication can coexist with successful
   publication. Never overwrite or move a published version/tag.
8. Record the release commit and backport/forward-port status. If release-only
   metadata must return to the integration branch, use a PR; do not force its
   history to match the tag.

A prerelease uses matching values such as `release/0.5.0-rc.1`,
`v0.5.0-rc.1`, and Maven version `0.5.0-rc.1`. A new commit after a published
candidate requires a new candidate version, not a moved tag.

## 6. Emergency maintenance releases

For a 0.3.x fix, branch `hotfix/<next-patch>-<description>` from the reviewed
maintenance baseline. Keep the change narrow, test it, and merge its PR into
`maintenance/0.3.x` before release preparation.

Before publishing, verify the actual maintenance workflow's artifact selection,
POM versions, tag checks, and CI coverage. Do not assume it behaves like 0.5.0.
In particular, 0.3.8 was a Redis-module-only release; it does not imply every
artifact exists at 0.3.8. Consumer instructions must name actual coordinates.

Use a new unpublished patch version for subsequent fixes. Forward-port through
a separate PR and record whether code or only intent can be reused.

## 7. Enforcement audit and follow-up proposals

Read-only snapshot checked on 2026-11-18 KST; recheck before implementation:

| Area | Observed | Proposed follow-up (not applied here) |
| --- | --- | --- |
| Main | Active ruleset requires PR, resolved threads, up-to-date checks, and the four contexts above; prohibits deletion/non-fast-forward updates | Preserve these requirements |
| Merge methods | Ruleset allows merge, squash, and rebase | Consider enforcing squash for ordinary PRs after checking release/integration needs |
| Reviews | Required approval count is zero | Use a second maintainer when available; increase only when a workable reviewer exists |
| Maintenance | `maintenance/0.3.x` reports unprotected | Add scoped protections after its CI supports PRs to maintenance branches |
| Maintenance CI | Its Maven workflow's PR filter includes only `main` | Extend and test maintenance PR triggers before requiring their check contexts |
| Release workflows | Main/maintenance use broad `release/*`; #70 narrows names and checks tag equality | Review/backport appropriate release safeguards, without accidentally triggering publication |
| Tags/release refs | Not established by the branch audit | Audit tag protection, release-ref mutation rules, and environment reviewer settings separately |

No bypass identities, reviewer configuration, security settings, or protections
are changed by this documentation PR. Confirm concrete administrative changes
separately. Avoid activating required checks before confirming they run for every
target branch and event.

## 8. Related repositories

Studio and the documentation portal should follow the same naming and PR intent,
but must use their own verified checks and deployment triggers. Do not copy the
Java check names into those repositories. Portal changes may be prepared and
reviewed without deployment; the current portal deployment hold remains in place.
