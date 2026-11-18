# Shared Redis: 0.3.x migration evidence and release gates

[한국어](shared-redis-legacy-migration.ko.md) · [Published-epoch mode](shared-redis-published-epochs.md)

## Status and scope

This is evidence and a migration checklist, **not a supported rolling-upgrade procedure**.
The 2026-11-18 (KST) check compared published 0.3.7/0.3.8 artifacts with candidate commit
`837b40c2faae80ccc6a3ad56209cdae96cca2ee9` for PR #79. It used the actual Lua resources and
the corresponding key/wire formats in an isolated Redis instance. It did **not** run
complete old/new Java applications, real Pods, production traffic, or a Redis Cluster.

Shared Redis remains the intended deployment environment; dedicated Redis is not a
prerequisite. `PUBLISHED_EPOCH` remains opt-in and `LEGACY_RESET` remains the default.
Neither mode name establishes compatibility with published 0.3.x counter state.

## Artifact identity

Published coordinates:

- `io.github.openfluxgate:fluxgate-redis-ratelimiter:0.3.7`
- `io.github.openfluxgate:fluxgate-redis-ratelimiter:0.3.8`

Both binary JARs were downloaded separately from the official Maven Central directory
and matched the corresponding published `.jar.sha1` files. The SHA-256 values below
were calculated locally for reproducibility; they are not a signature-verification claim.

| Version | Official artifact directory | Verified published SHA-1 | Calculated JAR SHA-256 |
| --- | --- | --- | --- |
| 0.3.7 | [Maven Central](https://repo.maven.apache.org/maven2/io/github/openfluxgate/fluxgate-redis-ratelimiter/0.3.7/) | `0958ee55bfa7b4371911cc049c7b29bef9e79395` | `92cc7321df451f9b6c5ac850173210fff078ce0461f2e03f2d49a940702d3490` |
| 0.3.8 | [Maven Central](https://repo.maven.apache.org/maven2/io/github/openfluxgate/fluxgate-redis-ratelimiter/0.3.8/) | `074b793bd71014777ff5f5ae0497fc08fc1b7742` | `3411aee46646e7981a0accd9b0da7b705ebbbcbd0914746bf5a2d96d92d8299e` |

The two published `lua/token_bucket_consume.lua` resources are byte-identical with
SHA-1 `c50421ad02e1b7e96852b75332174af1d7491c20`. The tested candidate script has SHA-1
`b3bf3d0b4f3ee8db6f4b03e2da46804f02291b8c`.

Do not identify an artifact solely by its local Maven cache directory or version label.
Local installs can overwrite a release coordinate. One cached 0.3.7 JAR did not match
its stored published checksum and was excluded from this check. The cache itself was
not changed. No conclusion about the cause of that mismatch is required for this test.

## Counter contracts differ

| Contract | Published 0.3.7 / 0.3.8 | Tested 0.5.0 candidate |
| --- | --- | --- |
| Bucket key | `fluxgate:<set>:<rule>:<identity>:<label>` | `fluxgate:bucket:{<set>:<rule>:<identity>}:<band-label>`, with an epoch suffix for a non-legacy epoch |
| Token-bucket timestamp | `last_refill_nanos` | `last_refill_micros` |
| Consumption wire | One bucket; capacity, window in nanoseconds, permits | FENCED bucket/metadata/fence keys; per-band configuration, revision and flags |
| Result | Four values | Eight values |

The new metadata and revision fence do not make the old key namespace participate in
the same quota. A literal `legacy` epoch omits the epoch suffix; it does not restore the
published 0.3.x key or timestamp contract. Label derivation/escaping must also be checked
rather than assuming identical labels for every existing rule.

## Isolated counterexamples

The proof used a new, short-lived `redis:7-alpine` container with networking disabled,
no published ports, a 64 MiB memory limit and a 0.25 CPU limit. Persistence was disabled.
Only a few script calls were made. The owned container was removed and its absence was
checked afterward. No existing Redis instance or production URI was used.

The policy was a token bucket with capacity **5** and a **3,600-second** refill window.
The calls were immediate, so no whole token refilled between them. Old calls used the
published 0.3.8 script; its identity with 0.3.7 makes the same script-level result
applicable to both versions. New calls used the candidate's actual FENCED Lua protocol,
revision 1, and source-derived bucket/metadata/fence names.

### A. Mixed writers split the quota

| Step | Observed result |
| --- | --- |
| Old-format key consumes 5 permits | Allowed; 0 tokens remain |
| Old-format key requests 1 more | Rejected; 0 tokens remain |
| New-format key for the same logical quota consumes 5 permits | Allowed; 0 tokens remain in a separate bucket |

The two namespaces therefore accepted **5 + 5 permits** for one intended quota of 5.
The exhausted old hash retained `last_refill_nanos`; the new hash contained
`last_refill_micros`. A successful build or startup does not prevent this split.

### B. Renaming a key does not preserve usage debt

A second old-format bucket consumed all 5 permits. In the isolated instance only, its
hash was renamed to the new bucket name. The candidate FENCED script then **allowed
another 5 permits**. The old hash lacked `last_refill_micros`, so the candidate initialized
a full token bucket. A key-name-only migration is not a debt-preserving conversion.

These are counterexamples, not a throughput benchmark or proof that every possible
migration arrangement fails. They demonstrate why this PR must not promise mixed-version
quota sharing or treat a rename as a compatible upgrade.

## What `validateLegacyBootstrap()` does not establish

`MongoPolicyRepository.validateLegacyBootstrap()` compares legacy Mongo rule documents
with the initial publication, including structure and capacity. It does not inspect the
actual Redis writer binary, key format, timestamp units or outstanding token usage.
Passing that check does **not** establish preservation of published 0.3.x counter debt.
The word "legacy" in that method or epoch is not a version-negotiation mechanism.

## Required cutover gates

1. **Inventory:** identify the exact deployed artifacts/checksums, all applications and
   Pods sharing each quota, policy configuration, labels, reset jobs, and existing Redis
   state format. Include autoscaling, scheduled jobs and disaster-recovery replicas.
2. **Freeze and recoverability:** prepare a consistent policy/configuration/state backup
   and demonstrate restoration in isolation. Freeze policy changes and destructive resets
   for the cutover window. Do not infer a complete key inventory from a sample.
3. **Fence writers:** stop admitting affected traffic or enforce an approved traffic fence;
   drain in-flight requests, retries and queues; verify no old writer can resume. Prevent
   old binaries from returning through autoscaling or automatic rollback.
4. **Resolve usage debt before enabling new traffic:** use a separately implemented and
   validated state conversion, or obtain explicit approval for a counter reset and its
   possible extra burst. **This PR supplies no 0.3.x state converter.** A hold until quota
   naturally replenishes requires a validated policy-specific bound and an approved
   availability plan. Key expiry alone is not proof that debt was safely preserved.
5. **Validate the target:** configure the published provider and fail-closed prerequisites
   explicitly; verify one shared quota across new instances, expected preservation/reset
   behavior, stale-writer rejection and acceptable Redis/Mongo load before lifting the fence.
6. **Observe:** check actual decision counts, policy versions, errors, Redis memory/latency
   and Mongo query load. Retire old state only under an ownership-aware cleanup procedure
   that cannot select current counters. Do not use a shared-instance blanket deletion.

This checklist authorizes no production operation. Record the chosen debt/reset policy,
traffic impact, abort conditions and responsible operator in the deployment plan.

## Rollback gates

A binary-only downgrade is not a safe rollback. After new traffic has consumed quota,
old state or a pre-cutover backup may be stale and grant extra permits. Fence traffic and
both writer generations first; reconcile consumed usage or obtain explicit reset approval;
then restore a compatible policy/state/configuration combination and validate it before
reopening traffic. Keep the old-writer prohibition in place until this procedure is ready.
An inability to demonstrate safe rollback is a release blocker, not a reason to silently
restart the old fleet.

## Still unverified by this evidence

- Complete Java application and multi-Pod upgrade/rollback with in-flight requests.
- A debt-preserving converter for every supported algorithm, label and state variant.
- New-mode Pub/Sub loss/reordering/reconnect end-to-end behavior.
- Redis Cluster failover/resharding and revision-fence expiry during transition.
- Million-key shared Redis load, Mongo QPS/latency/outage impact and request tail latency.
- Production backup/restore and the organization's actual traffic-fencing mechanism.

Track separate validation results against their exact commits. This document does not
convert other test successes into a guarantee for any of these outstanding release gates.

## Normal policy publication with optional admission enabled

This applies only to explicitly enabled `migration-admission`; the default remains
OFF. The immutable gate approves one exact rule-set ID/revision/epoch tuple per ID.
Even an epoch-preserving publication from revision N to N+1 no longer matches the
old approval. The supplied Spring filter returns 503 without downstream chain
execution or additional quota consumption for that rejection.

| Order | Intermediate mismatch | Availability impact |
| --- | --- | --- |
| Publish N+1 first | N-approved process reads N+1 | Protected request returns 503 |
| Restart with N+1 approval first | N+1-approved process still reads N | Protected request returns 503 |
| Restart only some Pods | Different approved revisions across Pods | Some requests may return 503 |

Duplicate rule-set IDs fail startup, so listing both revisions is not supported.
This is not a zero-downtime rolling-publication protocol. This 503 is not necessarily
transient quota exhaustion (429) and retrying does not fix configuration mismatch.

### Proposed runbook, not production-validated

The steps below require a separate operational change approval and rehearsal.
Spring-context tests are not real HTTP-server, Pod rollout, service-health or safe
state-transition validation. Health UP does not establish any of those properties.

1. Approve the affected services/Pods, current and proposed policy content and
   tuples, traffic interruption, owners and abort/recovery conditions. The gate
   neither verifies old-writer exclusion nor authorizes debt abandonment/reset.
2. Externally drain/block affected traffic and settle in-flight requests. This gate
   does not perform those actions. Do not publish an unapproved reset.
3. Verify the actual tuple returned by the authorized publication, update every
   affected instance's approval, and restart/redeploy to construct a new gate.
   Do not guess a future revision or directly edit production policy storage.
   Editing bound properties, Pub/Sub and ordinary refresh do not mutate the gate.
4. Verify every Pod's configured tuple and actual provider snapshot before resuming
   traffic. Envoy readiness rejects unapproved snapshots; the current Boot health
   indicator does not inspect the gate and may remain UP during a mismatch. Use
   an approved canary/test identity and requests without business side effects on
   the protected path, accounting for possible quota consumption. One successful
   request does not verify all Pods.
5. On failure, retain traffic blocking. Do not bypass the problem by disabling the
   gate, enabling fail-open or resetting counters. A rollback publication can create
   another revision: verify its actual tuple, coordinate new approvals and all Pod
   restarts, and do not assume restoring an old revision/debt is safe.

Both starters' `revisionPublicationRequiresNewContextAndImmutableApproval` tests
exercise N admission, N+1 filter503/no-chain/no-additional-consumption, continued
rejection after property mutation, and admission in a newly created context with
N+1 approval. They use mock HTTP and deterministic provider/limiter fixtures, not
Redis state migration, real traffic drain, multi-Pod rollout or zero downtime.
