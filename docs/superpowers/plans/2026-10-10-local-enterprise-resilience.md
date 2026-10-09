# Local enterprise resilience validation plan

> **For agentic workers:** Use bounded native executors in dedicated worktrees and a separate read-only reviewer. User explicitly authorized local execution; continue through fixes and fresh verification.

**Goal:** Measure and verify HA, load and credential rotation through the real Envoy → Java FluxGate → Mongo/Redis path, and reassess the engineering score from evidence.

**Architecture:** A separate three-node kind/Calico cluster hosts a three-member Mongo replica set, nine Redis Cluster nodes (three primaries and two replicas per primary), and multiple authz/Envoy/backend replicas. Each Redis shard spans all three nodes; `min-replicas-to-write=1` still permits writes after one member/node loss. Dedicated secrets, persistent local volumes and replica placement support reversible fault experiments. Existing local environments are preserved.

**Tech Stack:** Existing Java 21/FluxGate/Lettuce/Mongo, Kubernetes 1.35.0, Calico 3.33.0, Envoy Gateway 1.9.2, Docker Desktop, Python standard library, OpenSSL. No new application libraries.

**Spec:** Prior target architecture plus the user's explicit request to validate local HA/load/credentials before a cold >=95 assessment.

## Global constraints

- Only assigned feature and temporary resilience worktrees are writable. No cross-edit of active Claude branches, shared Maven cache, production or unrelated Docker resources.
- Cluster name/context: `fluxgate-resilience` / `kind-fluxgate-resilience`. Namespace: `fluxgate-resilience`, labelled local-ephemeral. Dedicated kubeconfig; no default context change.
- Store credentials and private keys are generated outside Git in private directories, mounted as Secrets, never printed or placed in command arguments/evidence.
- Fault and rotation sequences run serially under root coordination. Every injected failure has exact target/state checks and independent cleanup.
- Redis asynchronous replication is not a zero-loss consensus protocol; record observed counter preservation and remaining RPO bounds honestly. Do not use a pre-crash artificial WAIT to imply stronger normal-path guarantees.
- Mongo publication without write quorum must fail within the client/write timeout; existing majority-committed reads may remain available before primary stepdown. Redis minority/CLUSTERDOWN is tested explicitly, not inferred from a missing replica.
- Acceptance recovery deadlines: Mongo/Redis primary failover <=30s; isolated Pod replacement <=90s; abrupt worker loss <=90s for surviving service traffic, then full resource recovery <=180s after restart. Record exact loss and recovery duration without treating a single successful probe as sustained recovery.
- Node/Pod HA in one Docker Desktop host cannot prove physical-host or availability-zone survival. Local persistent volume recovery is separate from off-host backups.

## Review focus

1. A promoted Redis replica lacks scripts or stale routing leaves healthy replacements unusable: verify real traffic, script cache fallback and topology recovery.
2. Mongo primary election loses or exposes a partial publication: majority pointer/snapshot coherence and actual identity change must survive election.
3. A rotation is considered passed because old sockets stay open: test new connections and retired credentials after old Pods/contexts disappear.
4. Load hides failed schedules or coordinated omission: latency starts at the scheduled arrival, including queue delays/timeouts; account for planned/submitted/completed/missed work, all status distributions and latency samples.
5. Quota/deny requests accidentally reach the backend under chaos: explicit body/status controls, exhausted counter checks, current-generation readiness and exact resource mapping.

## Tasks

- [ ] **HA topology and proof** — `deploy/resilience-local/{kind.yaml,stack.yaml,setup.py,verify-ha.py,README.md}` in HA worktree. Create authenticated Mongo3/Redis9 with all three members of each shard on distinct nodes and local PVCs; two authz/Envoy/echo replicas. Validate new primary identities, unchanged policy checksum/revision/epoch, preserved exhausted quota, Mongo/Redis Pod PVC restart, authz/Envoy/backend replacement and a worker container stop/restart. No-quorum storage must fail closed. Recover isolated resources after each injection.
- [ ] **Load proof** — `deploy/resilience-local/verify-load.py` in load worktree. In-cluster generator uses real Gateway traffic and standard-library connections. Warm up; fixed arrival 100 RPS for 60s with p95 <=100ms, p99 <=250ms, >=99.9% expected 200 and no omitted schedules; characterize 300/600 RPS saturation separately, preserving failures and misses. Test concurrent quota exactly, and mixed allow/deny/OPTIONS distributions. Bounds are stated before runs and not silently relaxed to pass.
- [ ] **Credential proof** — `deploy/resilience-local/verify-credentials.py` in credential worktree. Explicit valid/missing/invalid/expired/wrong-subject TLS controls; overlapping API-key add/remove maps to same logical identity and preserves counter epoch. Client/server certificate and CA rolling rotation with post-retirement probes over new sockets; authenticated Mongo/Redis positive and wrong/missing-password rejection; rolling password change where supported. Real JWT/JWKS key rotation validates new signature and retirement. No raw credential/JWT output.
- [ ] **Root integration/fixes** — integrate reviewed executor commits into the feature branch, sequence all live proofs, fix evidence-backed application defects with meaningful regressions and mirrored starters when touched. Rebuild/retest all changed Java through isolated Maven and actual Cluster IT; rebuild deployed images after behavior changes.
- [ ] **Independent final review** — read complete proofs/log exits, score correctness/HA/load/credentials/reproducibility against stated criteria, document residual limits and sanitized provenance. Only complete when requested local scenarios have fresh passing evidence or a concrete non-recoverable limitation is identified.

## Interfaces

Setup writes a private fixture directory and a **sanitized** metadata JSON naming context, namespace, node placement, Gateway address, CA/certificate paths (no contents), credential file paths, load/quota rule IDs and active revision. `api_key_file` is reserved for load/rotation, `ha_api_key_file` for HA near-boundary/counter continuity, and `quota_api_key_file` for exact concurrent consumption; they resolve to distinct logical API-key IDs. Proof scripts consume that directory with `--fixture` and write distinct JSON only after full successful execution. Each script validates the isolated target before mutation. Root owns runtime scheduling. Public bootstrap keys from the previous pilot are not reused in this authenticated fixture.

Cold-review corrections: compare raw quota state near the boundary through Redis promotion; exercise publication during Mongo election; preserve actual primary changes and PVC identity/data; credential rejection needs protocol-specific evidence plus trusted positive controls; CA retirement is tested with old-only/new-only trust after old Pods terminate; JWT retirement explicitly distinguishes warm cached acceptance from post-refresh/cold-decoder rejection with unexpired tokens.

## Acceptance boundaries

Local HA requires actual failover and recovery, not just replica counts. Load results are measurements of this 6-CPU/16-GB Docker Desktop environment, not global capacity predictions. Cold score is an internal rubric with explicit deductions, not a certification, and is not assigned before the extended live results exist.
