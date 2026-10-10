# Safe policy publication and Envoy integration implementation plan

> **For agentic workers:** Use native bounded executors with test-driven development. User authorized execution; do not pause for another approval.

**Goal:** Preserve existing policies and usage while publishing, recovering and rolling back rules through a verified Envoy path. Rust comparison is excluded.

**Architecture:** Studio edits drafts. An explicit admin publish supplies one complete validated ruleset; immutable Mongo snapshots precede a CAS switch of the active pointer. Revision and counter epoch are separate. Data-plane reads the authoritative pointer and fences stale Redis operations; reload only refreshes config.

**Tech Stack:** Existing Java, Mongo driver, Redis Lua, Spring Boot 2/3, Envoy Gateway 1.9.2 and kind on Docker Desktop. No new library dependencies.

**Spec:** `docs/architecture/fluxgate-envoy-production-design.ko.md` plus the review and user-approved non-Rust corrections.

## Global constraints

- Only the assigned Envoy worktree and the isolated Studio `feature/envoy-policy-lifecycle` worktree are writable.
- Preserve unrelated Studio CI/IDE changes. Do not push, merge or deploy outside the local kind cluster.
- Starter changes must mirror Boot 2 and Boot 3.
- Notifications are hints; correctness cannot require their delivery. Mongo errors do not fall back to mutable drafts when a published snapshot is required.
- No automatic quota reset during reload. Reset requires an explicit audited publish choice and reason.
- One coherent snapshot per request; no claim of simultaneous cutover of already-running requests.

## Review focus

1. Lost/duplicate/reordered notifications, first poll and ACL-only changes preserve counters and recover policy.
2. Concurrent publishers and lost responses preserve CAS/idempotency and immutable active history.
3. Capacity decrease then increase/rollback preserves usage debt, including stale requests.
4. Studio editing older DTOs preserves all unsupported BSON fields and fails conflicting writes.
5. Gateway identity cannot be forged by raw headers; backend outages fail closed and ready probes reflect policy availability.

## Tasks and acceptance

- [x] 1. Studio read/merge/CAS field preservation: real BSON round-trip and edit conflict regressions.
- [x] 2. Mongo immutable policy repository: validate/publish/read/history/rollback, checksum, majority-write ordering, CAS and idempotency tests.
- [x] 3. Policy provider and Studio admin API: draft edits separate from publish; admin authorization; exact payload and preconditions; published-mode fail closed.
- [x] 4. Redis stable state: counter epoch separate from revision, stored monotonic revision fencing, capacity debt preservation; real Redis + cluster tests.
- [x] 5. Reload recovery: no implicit reset, first-poll/deletion/ACL recovery and reconnect refresh; Boot 2/3 tests.
- [x] 6. Envoy adapter: authoritative route binding/permits, verified identity, trusted IP, internal auth, readiness, unsupported WAIT rejection; contract tests.
- [x] 7. Local kind deployment: two authz Pods, explicit service trust, published seed, actual Envoy allow/429/403/5xx, update/import/rollback, missed notification convergence and counter preservation.
- [x] 8. Whole-reactor verification, Studio tests, formatting, independent review and evidence-based internal score. Release-blocking defects must be fixed before >=95/100.

## Progress and decisions

- Baseline Envoy worktree clean at ec4e9a8; Studio original dirty only in unrelated CI/IDE files. Studio isolated at 467bbf6.
- Ruling: complete publish request is the immutable draft generation; do not auto-assemble a multi-document Mongo query while edits race. Existing UI remains a draft editor; publication is explicit.
- Ruling: active pointer is checked per request in published mode, so dropped Pub/Sub cannot indefinitely retain a stale policy. In-flight old requests require Redis revision fencing; this is not a global instantaneous cutover guarantee.
- Ruling: internal review score is an engineering acceptance rubric, not certification or proof of production operation.

- Review correction: reject unknown/embedded ACL fields and use the same strict CIDR parser for publication and runtime. Reject empty generations, unsafe counter identities and bands Redis cannot execute.
- Review correction: indexed published-history candidates with checksum-checked binary ancestry avoid the previous 10,000-generation publication limit and exclude failed-CAS orphans.
- Review correction: preserve untagged public Redis API hash slots; metadata/fences live in `fluxgate:policy:`; reset APIs still report actual bucket deletion counts.
- Review correction: always compose optional metrics recorders so their exceptions cannot override decisions. Lua stale failures are nonretryable; gateway consumption retries are disabled.
- Test correction: mirrored composite reload fixtures now invalidate cache as production listeners do; markSeen cannot hide stale cache.
- Runtime acceptance requires successful complete scripts, never a partial JSON file: trusted network positives before/after timed-out negatives, explicit TLS certificate rejection, and healthy backend controls around outages.
- Completed: core 16-module clean install 2,213 tests; Studio clean verify 197 tests; failures/errors/skips zero and coverage/Spotless gates passed. Actual Calico network proof 31 checks, lifecycle 59 recorded assertions, real JWT Studio proof 17 observations with notifications disabled, and final storage/authz outage proof exit zero.
- Runtime test correction: internal SIGSTOP of namespace PID 1 did not pause Mongo. Use the exact isolated containerd task freezer and assert PAUSED before requests, RUNNING after cleanup. Missing authz endpoints return EG 1.9.2's documented 500; storage failures remain 503. Earlier failed checks do not count as successful evidence.
- Independent final review: scoped internal 96/100; no remaining evidence-backed release blocker in the reviewed implementation. Production HA/load/rotation/durable configuration are outside the completed local validation. See `docs/reviews/2026-10-10-enterprise-envoy-review.ko.md`.
