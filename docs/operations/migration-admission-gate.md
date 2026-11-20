# Optional migration admission gate

This gate authorizes new-engine requests for exact operator-selected policy snapshots.
It does **not** detect or stop unmodified 0.3.x writers, preserve their debt, prove
writer fencing, authenticate provider metadata, or make a reset safe. A new epoch
or prefix is not evidence of a safe migration. No publication/reset/import is performed.

Default is off. To block all snapshots initially:

```yaml
fluxgate:
  migration-admission:
    enabled: true
    approvals: []
  ratelimit:
    failure-behavior: DENY
    fallback:
      mode: NONE
```

After independently authorizing the transition, explicitly list an exact tuple:

```yaml
fluxgate:
  migration-admission:
    enabled: true
    approvals:
      - rule-set-id: orders
        revision: 2
        epoch: "the-exact-explicit-reset-epoch"
```

Every rule in the returned snapshot must match the same positive exact revision
and nonlegacy epoch. Missing, malformed, mixed and unlisted snapshots are rejected.
Normal publication increments the revision, so a later publication is denied until
the approved configuration is updated and the process restarted. The engine takes
an immutable configuration copy; mutating bound properties does not revoke or grant
permission at runtime. Restart all relevant instances for grant/revoke changes.

The engine guards normal lookup, requested/returned ID agreement, missing snapshots,
provider read errors and direct `checkUsingSnapshot` before ACL bypass or consumption.
The supplied Spring handler maps the dedicated rejection to unavailable/503; the
Envoy decision service also returns503. Filter/AOP excluded paths and endpoints that
do not invoke these integrations are not protected. Direct low-level limiter calls
and manually constructed external handlers are outside this boundary.

When enabled, startup validates the actual selected engine carries an equal immutable
gate even with reload disabled. It requires DENY/no local fallback and rejects registered
custom handlers or handlers bound to another engine. Off preserves prior behavior.
The gate is independent of reload mode and does not imply the other PUBLISHED_EPOCH
provider/freshness validations have run. It validates tuple identity, not provenance.

Tests use deterministic providers/limiters and mock HTTP or direct Envoy service calls.
They are not Redis migration, real HTTP server, debt-transfer or old-writer exclusion tests.

One approval per rule-set ID is supported; duplicate IDs are rejected. This is not
a dual-revision rolling-update protocol. Coordinate publication and instance restarts;
unapproved Envoy snapshots are not ready. No target Redis identity, global writer
absence, credential revocation or traffic-drain action is verified or performed.

For publication order, interruption risks and the **not production-validated**
operational proposal, see the [migration runbook](shared-redis-legacy-migration.md#normal-policy-publication-with-optional-admission-enabled)
and its [Korean version](shared-redis-legacy-migration.ko.md).
