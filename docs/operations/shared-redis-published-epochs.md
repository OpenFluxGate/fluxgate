# Shared Redis: conditional scan-free policy refresh

This opt-in is for a shared Redis deployment. Dedicated Redis is NOT a prerequisite.
It removes automatic destructive reset from the authoritative published-policy path,
not from arbitrary application code, and is NOT an instantaneous global cutover guarantee.
Default `LEGACY_RESET` retains prior behavior for existing deployments.

## Preconditions

Set `fluxgate.reload.counter-mode=PUBLISHED_EPOCH` only after all relevant clients are on
this contract. The selected provider must advertise `usesPublishedCounterEpochs()` and
`requiresFreshRead()`. The built-in `PublishedMongoRuleSetProvider` reads the active
published snapshot on each request and supplies revision+epoch metadata from that same
snapshot. Custom providers must implement the entire capability contract, not merely
return true. Composites advertise the capability only if both sources do.

Redis must be enabled, the limiter cannot be IN_MEMORY, local fallback must be NONE,
and missing-rule and failure behavior must both be DENY. Unsupported configurations
fail startup. `reload.enabled=true` keeps validation active even with no cache/strategy.
Missing snapshots and backing-store errors must not fall back to drafts or legacy data.
There is no automatic selection of the published provider: configure it explicitly.

## Refresh versus reset

A normal policy publish preserves its `counterEpoch`; counters are not deleted. An
intentional policy publish with `reset=true` creates a new epoch in the authoritative
repository. Pub/Sub is only a notification, not the source of truth. Every Pod still
receives refresh notifications; dropped, duplicate or out-of-order messages do not
choose the policy revision. Fresh reads bypass the local TTL cache.

The mode does not register the Redis destructive-reset listener. The injected
`BucketResetHandler` rejects both scoped and full destructive reset; custom destructive
handler beans are rejected too. It does NOT intercept direct calls to the low-level
RedisTokenBucketStore or application-created handlers. Such calls are unsupported in
this mode and must be removed from custom administration paths. Use published policy
reset, not a blanket SCAN/delete.

Old epoch keys age out through their bucket TTL. This is not immediate memory reclamation.
Memory depends on active identities, bands, epoch-change rate and TTL. A stale writer can
refresh an old key's TTL. No new distributed cleanup leader or background SCAN is installed.
Only introduce optional old-epoch cleanup after proving it cannot select the active epoch.

## Consistency boundary and 0.3.x migration

The existing revision fence is per Redis key/hash slot, has a TTL and rejects an old
revision only after that key has observed a newer revision. A request that read an old
snapshot before publication can still consume before this observation. Fresh reads
reduce stale exposure but do NOT create a transaction spanning Mongo publication and
Redis consumption. For strict zero-old-request cutover, drain/fence in-flight requests
and old writers during the transition. No unbounded worker retry may replay stale policy.

Unmodified 0.3.7/0.3.8 clients neither understand this epoch protocol nor share native0.5
keys/state. Do not enable mixed legacy writers for one quota. First inventory clients,
back up policy/index/configuration, verify a restore, and transition with an approved
writer/traffic fence. New and old keyspaces are not a safe dual-write migration. Do not
silently reset outstanding quota debt. Post-activation rollback requires a reviewed
compatible snapshot/traffic plan, not simply an old binary. ACL enforcement by old
binaries is independently unsupported.

## Verification gates (not measured by this patch)

- Actual standalone and3-primary cluster: publish while Pub/Sub is disconnected, next
  request sees published revision; automatic SCAN/DEL/UNLINK command count remains zero.
- Preserve publish keeps consumed quota; reset publish changes epoch once. Duplicate,
  reversed, full-reload and queue-overflow notifications never delete active counters.
- Two/many Pods enforce one shared quota. Hold an old request between policy read and
  consume: test old allowance before new fence observation and stale rejection after it.
- Missing snapshot, Mongo error, Redis error: DENY, no local/draft fallback.
- Verify all band/metadata/fence keys share slot; failover/resharding/reconnect and TTL
  expiry do not create an undocumented guarantee.
- Shared keyspace load matrix: unrelated keys0/100k/1m+, Pods1/4/16; measure publish-to-read
  convergence, callback/queue age, request p50/p95/p99, Mongo read latency/QPS, Redis CPU,
  command counts and memory by epoch. Compare legacy SCAN mode under identical workload.
- Fresh Mongo reads shift cost from scanning to authoritative reads: measure that cost,
  do not claim latency improvement without results. Test missing/out-of-order Pub/Sub,
  slow listeners and bounded queues separately.

The focused unit tests validate wiring and guardrails only. They do not establish the
million-key performance result, live quota equivalence, or a global stale-writer barrier.
