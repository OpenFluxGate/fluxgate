# Published epoch timeout contract

A policy revision fence does not deduplicate consumption requests. In opt-in
`PUBLISHED_EPOCH` mode, `fluxgate.resilience.retry.retry-on-timeout=true` is rejected
at startup, including when retry is disabled or resilience auto-configuration is
absent. The selected `ResilientExecutor`'s nested retry config is also checked:
custom config/executor beans and `@Primary` selection cannot bypass the check by
leaving the property false. This guards the built-in wiring's declared policy,
not arbitrary user-written limiter implementations or hidden retry code.

`LEGACY_RESET` and default retry/timeout values are unchanged. The Redis timeout
is 5000 ms by default; the isolated failover fixture explicitly used 1000 ms.
Neither value guarantees immediate client recovery after server promotion.
No command replay, topology setting, timeout increase, dependency upgrade or
security exception is introduced by this change.

## Deterministic regression scope

`TimeoutContractTest` runs in both starters without Redis/Mongo containers. An
injected delegate throws a timeout on its first consumption and returns QUOTA on
its next independently initiated request. It verifies exactly one delegate call
for the failed operation, raw-engine exception propagation, full resilience
primary-wrapper selection and NONE/DENY unavailable exception. A real configured
filter processes **mock HTTP** and returns 503 without invoking the application
chain. These tests prove wiring and error-policy contracts, not Redis failover,
network recovery or physical quota storage behavior.

The original isolated Boot2 immediate-recovery FAIL remains unresolved. The
separate bounded observation recorded ten QUOTA responses but did not reproduce
a timeout-to-recovery transition. No success here overwrites either observation.
