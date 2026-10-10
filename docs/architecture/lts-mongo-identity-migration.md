# LTS Mongo rule identity migration

LTS `0.4.0-SNAPSHOT` identifies a rule by `(ruleSetId, id)`. A legacy unique `id_1` index enforces global IDs and prevents this contract. Both startup CREATE and VALIDATE modes reject that constraint with an explicit migration error. Startup does not remove indexes.

Perform this transition in an exclusive maintenance window:

1. Take and verify a backup, inventory index definitions, and record the active policy pointer and relevant Redis counters. Stop all old-version writers and concurrent schema/index management. Stop the authorization deployments before running DDL; requests can be unavailable during this window.
2. With the new Mongo adapter and an explicitly privileged maintenance Mongo client, call `new MongoRateLimitRuleRepository(ruleCollection).migrateLegacyGlobalIdConstraint()`. Use the exact existing rule collection and majority write concern. Never expose the maintenance credential to the service or logs.
3. The method checks for custom global-ID constraints and exact known index definitions before modifying anything. It creates and verifies the unique `(ruleSetId, id)` index first, removes only the known plain unique `id_1`, and recreates a nonunique `id_1` lookup. It does not modify rule documents, ACLs, active policy pointers, or Redis state.
4. Verify both final indexes and unchanged documents. Repeated invocation is idempotent. If a DDL call fails, keep writers stopped, inspect the error privately, and retry after resolving its cause. A failure after the legacy drop still leaves the verified compound constraint protecting identities. Unknown/custom indexes require separate operator review and are never removed automatically.
5. Start the new service artifacts, verify startup/readiness, exact backend responses, policy pointer equality, and quota preservation. Then reopen traffic and allow new-version writers.

MongoDB index deletion cannot atomically exclude concurrent DDL; the maintenance exclusivity above is an operational prerequisite. Switching back to an old JAR does not reverse this migration. Once different rule sets reuse an ID, a global unique index cannot be restored without data reconciliation.

Redis identity encoding and policy epoch/fence compatibility are separate migration concerns. Some special, reserved, or long identities change keys between the old encoder and LTS; no automatic migration for those counters is provided. Upgrade or stop callers using old Lua before publishing through the new lifecycle.
