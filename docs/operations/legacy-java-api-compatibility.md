# Legacy Java API compatibility scope

The published Redis 0.3.8 artifact depends on core 0.3.7; there is no core 0.3.8 artifact in the verified Central metadata. This change restores these public contracts:

- `RateLimitRule.getAttribute(String, Class<T>)` returns the legacy nullable `T` (erased `Object`). It retains the old unchecked return and does not inspect `Class<T>`; a typed caller may throw `ClassCastException`. The checked Optional lookup is named `getOptionalAttribute`. Code compiled against the unpublished Optional-returning candidate must migrate/recompile.
- `RedisTokenBucketStore.close()` remains a logging-only lifecycle hook. As in 0.3.8, the externally managed connection is not closed, and the store does not implement AutoCloseable/Closeable. This does not change connection ownership.
- `BucketState.getRetryAfterSeconds()` restores rounded-up seconds for nonnegative waits. Unlike the old addition-based implementation, positive long overflow is avoided. Negative waits return zero. These extreme-value behavior corrections are not bitwise behavioral equivalence for every long.
- Four legacy `RedisRateLimiterConfig` constructors retain their declared `IOException` for old catch-only source compatibility. Runtime script failures remain the current `ScriptExecutionException`; no exception is swallowed or converted. New overloads are unchanged. Existing test helpers propagate checked exceptions rather than suppressing them.

## Evidence and limits

A separate offline Java 11 fixture compiled a client once against checksum-verified official core 0.3.7 and Redis 0.3.8 JARs. The same class reproduced three `NoSuchMethodError` failures against the preceding candidate and resolved all three after restoration. Null receivers in two fixture calls verify linkage only; unit tests exercise nullable/caller-cast semantics and ensure store.close does not invoke the provider. Four old catch-only constructor fixtures compile again. The external fixture and downloaded artifacts are not included in default CI; descriptor, checked-exception, behavior and ownership unit assertions are included.

A javap audit of 80 public old core/Redis classes found no missing declared member name/JVM-descriptor pairs after restoration. This is not a complete binary compatibility checker: inherited access, static/instance changes, generics, serialization, other modules and all application behaviors are not exhaustively verified. Redis persisted-state compatibility, mixed-version writers, security findings and Studio pairing remain separate release gates. This document does not claim complete backward compatibility or release readiness.
