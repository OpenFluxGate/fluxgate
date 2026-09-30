# Changelog

All notable changes to FluxGate are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

This release closes the correctness, resilience and wiring gaps found in the
2026-09-30 review. The headline change is that **the features FluxGate already
advertised are now actually wired**: a working default handler, live retry and
circuit breaker, honoured filter properties, standard rate limit headers, and a
multi-band token bucket that is atomic per rule.

Read **[Breaking / Migration](#breaking--migration)** before upgrading: bucket
keys change shape, so every quota resets once.

### Added

**Runtime wiring**

- `org.fluxgate.spring.handler.EngineBackedRateLimitHandler` — the library's own
  `FluxgateRateLimitHandler`, registered automatically whenever a `RateLimiter`
  and a `RateLimitRuleSetProvider` bean exist. `@EnableFluxgateFilter` with Redis
  and MongoDB now needs **no user handler at all**. Define your own
  `FluxgateRateLimitHandler` bean to replace it.
- `fluxgate.ratelimit.mode` (`AUTO` | `REDIS` | `IN_MEMORY`, default `AUTO`).
  `AUTO` picks Redis when `fluxgate.redis.enabled=true` and otherwise an
  in-memory `Bucket4jRateLimiter`, so a development profile gets a working —
  though non-distributed — limiter with zero infrastructure. The choice is logged
  at INFO on startup.
- `fluxgate.ratelimit.fallback.mode` (`NONE` | `IN_MEMORY`, default `NONE`),
  `fallback.max-buckets` (`100000`) and `fallback.expire-after-access` (`1h`) —
  keeps per-instance limits in force while Redis is unavailable instead of
  falling back to a global allow or deny.
- `org.fluxgate.spring.handler.ResilientRateLimiter` — every limiter call now
  goes through `ResilientExecutor`, so `fluxgate.resilience.*` (retry + circuit
  breaker) is live rather than dead configuration.
- Redis is no longer required at startup: `LazyRedisRateLimiter` connects on
  first use and reconnects in the background with exponential backoff to a 60s
  cap. `fluxgate.redis.fail-fast=true` restores the old eager-connect behaviour.
  `RedisConnectionState` exposes `isReady()` / `getLastErrorMessage()` /
  `getFailedAttempts()` to the health indicator.
- `fluxgate-testkit` is a real testkit: `InMemoryRateLimitHandler`,
  `FluxgateTestRules`, the `FluxgateInMemoryExtension` JUnit 5 extension and
  `RateLimitAssertions`. See `fluxgate-testkit/README.md`.

**Client experience**

- IETF style headers `RateLimit-Limit`, `RateLimit-Remaining`,
  `RateLimit-Reset` (delta seconds) and `RateLimit-Policy` (`100;w=60`), behind
  `fluxgate.ratelimit.response.include-standard-headers` (default `true`).
- The legacy family is complete: `X-RateLimit-Limit` and `X-RateLimit-Reset`
  (epoch seconds) are written alongside `X-RateLimit-Remaining`, behind
  `fluxgate.ratelimit.response.include-legacy-headers` (default `true`).
  `fluxgate.ratelimit.include-headers` is the master switch over both families.
- `org.fluxgate.spring.filter.RateLimitResponseWriter` — an SPI
  (`void write(HttpServletRequest, HttpServletResponse, RateLimitResponse)`) that
  replaces the 429 body entirely. The default
  `ProblemDetailRateLimitResponseWriter` writes RFC 9457 problem JSON without
  needing Jackson; `fluxgate.ratelimit.response.content-type` and
  `response.body-template` tune it without writing code.
- Weighted requests: `fluxgate.ratelimit.cost-header` (unset) and `max-cost`
  (`1000`), `FluxgateRateLimitHandler.tryConsume(context, ruleSetId, permits)`,
  and `@RateLimit(permits = …)`.
- `@RateLimit(throwOnReject = true)` and `org.fluxgate.spring.aop.RateLimitExceededException`,
  so a `@RateLimit` method outside an HTTP request (scheduled task, message
  listener) can signal rejection to the caller. The aspect now works without an
  `HttpServletRequest` at all.

**Keys, scoping and safety**

- `fluxgate.ratelimit.trusted-proxies` (default empty) — IP literals or CIDR
  blocks, IPv4 and IPv6. With `trust-client-ip-header=true` the forwarding header
  is honoured only for a request whose `remoteAddr` is a trusted proxy; the
  X-Forwarded-For list is walked from the right, skipping trusted hops.
- `fluxgate.ratelimit.missing-key-behavior` (`FALLBACK_TO_IP` | `REJECT`,
  default `FALLBACK_TO_IP`), `org.fluxgate.core.key.MissingKeyBehavior` and
  `org.fluxgate.core.exception.MissingRateLimitKeyException`.
- `fluxgate.ratelimit.identity.source` (`HEADERS` | `PRINCIPAL` |
  `PRINCIPAL_THEN_HEADERS`) with `identity.user-id-header` (`X-User-Id`) and
  `identity.api-key-header` (`X-API-Key`). Unset resolves to
  `PRINCIPAL_THEN_HEADERS` when Spring Security is on the classpath and `HEADERS`
  otherwise, so a secured application gets **verified** identities without
  configuring anything — `PER_USER` and `PER_API_KEY` no longer take a
  client-controlled header at face value. The effective value is logged once at
  startup.
- `fluxgate.ratelimit.fail-on-missing-handler` (default `false`) — `true` fails
  the boot instead of starting with no rule set provider and therefore no limits.
- `fluxgate.redis.max-bucket-ttl` (default `7d`) — an upper bound on a bucket's
  TTL. Removing the old 24-hour cap fixed long windows but left the amount of
  Redis memory a forged-identity caller can pin down growing with the window;
  this bounds it. The limiter warns once per rule whose window the cap shortens.
- `fluxgate.mongo.event-retention` (default `30d`) — a MongoDB TTL index on the
  event collection's `createdAt`. Without one the collection grows without bound,
  and every document in it carries a client IP, a user id and an API key
  fingerprint. `0` creates no index and leaves retention to you.
- `fluxgate.reload.pubsub.secret` and `fluxgate.control.secret` — HMAC-SHA256
  signed rule change notifications, with `fluxgate.reload.pubsub.max-message-age`
  (default `5m`) as the replay window. Unset keeps the previous behaviour; set,
  every message without a valid signature — the legacy `"*"` full reload included
  — is logged at WARN and ignored.
- `fluxgate.actuator.health.include-endpoint-details` (default `false`) — the
  health payload no longer exposes `host:port`, cluster node counts and dependency
  failure messages unless you opt in. The status and exception type are still
  reported, and the full message is logged at WARN.
- `fluxgate.ratelimit.collect-headers` (default `false`) and `header-allowlist`
  — request headers reach `RequestContext` only when opted in and allow-listed.
- `fluxgate.ratelimit.log-query-string` (default `false`) and
  `case-sensitive-patterns` (default `true`).
- `org.fluxgate.core.key.KeyValueSanitizer` — restricts key values to
  `[A-Za-z0-9._:@-]` and hashes anything longer than 256 characters.
- `RateLimitEngine.OnMissingRuleSetStrategy.DENY`, which
  `fluxgate.ratelimit.missing-rule-behavior=DENY` now wires.

**Observability**

- `fluxgate.metrics.endpoint-normalization` (default `true`) replaces numeric,
  UUID and 24-hex path segments with `{id}`, and
  `fluxgate.metrics.max-endpoint-tags` (default `1000`) registers a
  `MeterFilter.maximumAllowableTags` deny filter. `fluxgate.metrics.include-endpoint`
  is now honoured.
- `org.fluxgate.spring.filter.RateLimitDurationRecorder` — lets the servlet layer
  feed `fluxgate.requests.duration` without a compile-time Micrometer dependency.
- `docker/grafana/fluxgate-dashboard.json` (10 panels) and
  `docker/prometheus/fluxgate-alerts.yml` (3 recording rules, 6 alerts).
- `org.fluxgate.control.notify.RuleChangeNotifierMetrics` —
  `getPublishedNotifications()` / `getRetriedNotifications()` / `getFailedNotifications()`.

**Core API**

- `RateLimitResult`: `limit`, `resetTimeMillis`, `policy`, `bandLabel`, `hasRule()`,
  6-argument `allowed(...)` / `rejected(...)` factories, value `equals`/`hashCode`.
- `RateLimitResponse`: `limit`, `resetTimeMillis`, `windowSeconds`, `bandLabel`
  and `RateLimitResponse.from(RateLimitResult)`, which converts nanos to millis
  rounding **up**.
- `RateLimitBand.getKeyLabel()` — the explicit label, or `<capacity>-per-<windowSeconds>s`.
- Value-based `equals`/`hashCode` on `RateLimitBand`, `RateLimitRule` and
  `RateLimitRuleSet`.
- `RuleCache.getOrLoad(String, Function)`; `CaffeineRuleCache` overrides it with
  an atomic single load plus negative caching (`fluxgate.reload.cache.negative-ttl`,
  default `5s`, `0` disables).
- Resilience knobs: `fluxgate.resilience.circuit-breaker.sliding-window-size` (`20`),
  `.failure-rate-threshold` (`50`), `.minimum-number-of-calls` (`10`),
  `fluxgate.resilience.retry.jitter-factor` (`0.2`), `.retry-on-timeout` (`false`).
  `DefaultCircuitBreaker` gained `getRecordedCalls()` and `getFailureRate()`.
- `Bucket4jRateLimiter.reset(String ruleSetId)`, `resetAll()`, `size()`,
  `getMaximumSize()`, and constructors taking `maximumSize` / `expireAfterAccess`.

**Hot reload**

- `CompositeReloadStrategy` — `AUTO` and `PUBSUB` now always run a polling
  backstop behind Pub/Sub (`fluxgate.reload.pubsub.backstop-polling-interval`,
  default `60s`, `0` disables), so a missed message self-heals.
- Ordered reload listeners: `AbstractReloadStrategy.addListener(listener, order)`
  with `ORDER_CACHE_INVALIDATION = -100` < `DEFAULT_ORDER = 0` < `ORDER_BUCKET_RESET = 100`.
- `InMemoryBucketResetHandler`, so the in-memory limiter participates in reloads.
- `RuleChangeMessage.version` (`SCHEMA_VERSION = 1`); `@NotifyRuleChange`
  publishes **after commit** inside a transaction and retries 3× with jittered
  backoff.

**Storage**

- Multi-band Lua contract in `token_bucket_consume.lua`: one call per rule, all
  of a rule's bands in one hash tag, two passes (check all, then consume all or
  none). See `fluxgate-redis-ratelimiter/README.md`.
- `org.fluxgate.redis.connection.RedisUriUtils` (`detectMode`, `splitNodes`,
  `mask`, `DEFAULT_TIMEOUT`), `org.fluxgate.redis.script.LuaScriptRegistry`,
  `RedisConnectionProvider.unlink(String...)`,
  `RedisRateLimiter.BUCKET_KEY_PREFIX` / `bucketKeyPattern(String)`,
  `BucketState.limit()` / `bandIndex()`.
- MongoDB index creation on `fluxgate.mongo.ddl-auto=create`: `{ruleSetId: 1}`
  and unique `{ruleSetId: 1, id: 1}`.

**Build, CI and tests**

- Two test tiers: `./mvnw test` runs unit tests only, `./mvnw verify` adds the
  integration tests (failsafe, `*IntegrationTest` / `*IT`), `-DskipITs` skips
  them. Integration tests use Testcontainers and **skip** — never fail — when
  neither a supplied URI nor a Docker daemon is available.
- `FLUXGATE_REDIS_URI`, `FLUXGATE_MONGO_URI` and `FLUXGATE_MONGO_DB` point the
  integration tier at an existing server instead of Testcontainers.
- `.github/dependabot.yml`, `.github/CODEOWNERS`, and
  `fluxgate-spring-boot2-starter/src/main/resources/META-INF/spring.factories` —
  the latter for tooling and documentation generators that still read
  `spring.factories`, alongside the authoritative `AutoConfiguration.imports`. Both
  files must list the same eight auto-configurations, and a test fails the build if
  they diverge. **It does not extend version support:** the Boot 2 starter uses
  `@AutoConfiguration`, a Spring Boot 2.7 API, so **Spring Boot 2.7.x is required**,
  not merely supported. Boot 2.7 is OSS end-of-life, so plan the move to the Boot 3
  starter.
- `fluxgate-spring-boot2-starter/README.md` — the Boot 2 starter had no README at
  all. It points at the Boot 3 reference and lists only the differences (`javax`
  imports, Java 11, the 2.7 requirement).
- New utility types `org.fluxgate.spring.util.{LogSanitizer, TrustedProxies, RequestPathResolver}`
  and `org.fluxgate.spring.filter.{RateLimitHeaderWriter, RequestContextFactory}`.
- `FluxgateActuatorAutoConfiguration.DegradedHttpStatusMapperConfiguration` — registers a
  `HttpCodeStatusMapper` bean mapping the DEGRADED health status to HTTP 503 by default
  (`fluxgate.actuator.health.degraded-http-status=503`); set to `0` to disable. Backs off when
  the application defines its own `HttpCodeStatusMapper` bean. Property
  `fluxgate.actuator.health.degraded-http-status` added to `FluxgateProperties`. (N-16)
- All six items above mirrored byte-identically to `fluxgate-spring-boot2-starter`
  (`jakarta.` → `javax.`). (Mirror)

### Changed

- The rate limit **decision path is now the core `RateLimitEngine`** in every
  mode. `missing-rule-behavior` is decided there and nowhere else.
- `fluxgate.ratelimit.include-patterns`, `exclude-patterns`, `filter-order` and
  `default-rule-set-id` are read from **properties first**, with the
  `@EnableFluxgateFilter` attribute as the fallback — which is what the
  annotation's Javadoc always promised.
- `fluxgate.ratelimit.include-patterns` default changed from `/*` to all paths
  (`/**`). `/*` matches a single segment only, so the old default never matched
  `/api/v1/users`.
- `fluxgate.ratelimit.filter-order` is now an `Integer`, unset by default; the
  effective default is `1` (from `@EnableFluxgateFilter#filterOrder()`).
- `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` default `100` → `50`.
- `fluxgate.resilience.circuit-breaker.failure-threshold` is now an `Integer`,
  unset by default, and is applied only when set. Unset, the breaker uses the
  sliding-window failure rate.
- Include/exclude patterns match the **normalized, context-path independent**
  request path (URL-decoded, `;`-parameters removed, `//` collapsed, trailing
  slash stripped), not the raw URI.
- The 429 body is an RFC 9457 problem document with
  `Content-Type: application/problem+json;charset=UTF-8`.
- Redis bucket keys, and therefore every quota, change shape — see
  [Breaking / Migration](#breaking--migration).
- Bucket TTL is `max(1, ceil(window_seconds × 1.1))` with **no upper cap**, so a
  7-day window keeps a 7-day bucket.
- Rejections no longer write bucket state; only TTLs are refreshed, via `EXPIRE`,
  which is a no-op for a bucket that does not exist yet.
- `Bucket4jRateLimiter` holds buckets in a Caffeine cache
  (`maximumSize` 100 000, `expireAfterAccess` 1 hour) instead of an unbounded map,
  and keys them per `(ruleSetId, ruleId, key, band)` to match the Redis layout.
- Rejected results now carry the **real** remaining tokens of the binding band
  instead of a hardcoded `0`; `RateLimitResult.allowedWithoutRule()` reports
  `remainingTokens = -1` (unknown) instead of `Long.MAX_VALUE`. A value of `-1`
  means "unknown" and the corresponding header is omitted, not written as `-1`.
- `Retry-After` is rounded **up** and never `0` (a floor of 1 second), so a
  sub-second delay can no longer send clients into a busy loop.
- Bucket deletion uses `SCAN` + `UNLINK` instead of `KEYS` + `DEL`.
- Pub/Sub reload messages that are empty, unparseable, not an object or carry an
  unknown `version` are logged at WARN and **ignored**. Only `"*"` is a
  deliberate full reload.
- MongoDB failures propagate as `MongoConnectionException` (socket, timeout, not
  primary) or a retryable `FluxgateOperationException` instead of looking like
  "no rules exist". Only a successful empty query returns `Optional.empty()`.
- A half-written Redis rule-set hash throws `IllegalStateException` naming the
  key and field instead of silently becoming "capacity 10, window 60s".
- Redis URIs are logged as `host:port[/db]` only. Resolved key values are logged
  masked (first four characters + `***`) and only at DEBUG.
- `FluxgateHealthIndicator` probes for real filter and aspect beans rather than
  reporting the `filter-enabled` property.
- `RedisRateLimiterConfig` closes only the connection provider it created
  (`ownsConnectionProvider()`), and its constructors no longer declare
  `throws IOException`. `RedisRateLimiter` implements `AutoCloseable` with a
  documented no-op `close()` — it owns neither the store nor the connection.
- `RateLimitRuleSet.builder().build()` and `RateLimitRule.build()` throw
  `InvalidRuleConfigException` consistently (previously `IllegalArgumentException`
  or `IllegalStateException`).
- `docker/*.yml` bind every published port to `127.0.0.1` and are marked local
  development only.
- `fluxgate.resilience.circuit-breaker.enabled` now defaults to **`true`**.
  With retry on and the breaker off, a failing dependency received three times the
  load and nothing stopped it — which is inconsistent with the fail-closed
  defaults everywhere else. An open circuit degrades immediately to
  `fluxgate.ratelimit.fallback.mode`, or to `failure-behavior`.
- `LazyRedisRateLimiter` never blocks a request thread. While it is
  disconnected a request fails fast with `RedisUnavailableException`, which
  `failure-behavior` / `fallback.mode` then resolves, and reconnection happens on a
  background thread. It previously reconnected synchronously on the calling
  thread, so one network black hole could exhaust the container's worker pool.
- A band with a sub-second window derives its key label as
  `<capacity>-per-<millis>ms` (and `-per-<nanos>ns` below a millisecond), so two
  sub-second bands of one rule no longer collide on `…-per-0s` and get rejected at
  build time.
- A Redis-only deployment with no rule set provider logs a startup **ERROR**
  naming the missing bean instead of silently running with `ALLOW_ALL` or
  rejecting everything with a misleading message.
- The starter resolves the rule set provider by looking for a bean named
  `delegateRuleSetProvider` first, then falling back to a **unique**
  `RateLimitRuleSetProvider` bean. A single provider bean works under any name,
  and `fluxgate.mongo.enabled=false` no longer fails the context.
- `ControlSupportAutoConfiguration.ruleChangeAspect(...)` additionally takes a
  `RuleChangeNotifierMetrics`, and the bean declares `destroyMethod = "shutdown"`.
  Code that constructed that bean directly must be updated.
- `FluxgateAopAutoConfiguration` no longer carries
  `@ConditionalOnWebApplication(SERVLET)`, so `@RateLimit` works in a batch or
  messaging application too. If you did not expect the aspect to be active there,
  switch it off with `fluxgate.ratelimit.enabled=false` or remove
  `aspectjweaver`.
- `fluxgate.ratelimit.mode=IN_MEMORY` wins even when `fluxgate.redis.enabled=true`:
  Redis is then used only for rule storage and hot reload, and limits apply per
  instance. A warning is logged at startup.
- Every documented `mvn` invocation is `./mvnw`.
- `fluxgate.ratelimit.fail-on-missing-handler` promoted from `@Value` injection to
  `FluxgateProperties.RateLimitProperties.failOnMissingHandler` (`boolean`, default `false`)
  with Javadoc. (N-1)
- Filter and AOP `resolveHandler` now log precisely which dependency is missing: *neither
  RateLimiter nor RuleSetProvider* / *RateLimiter but no RuleSetProvider* / *RuleSetProvider
  but no RateLimiter* — each with an actionable remedy message. (N-4)
- `ClientIpExtractor` with `trust-client-ip-header=true` and an empty `trusted-proxies` now
  returns the **right-most** valid XFF hop instead of the left-most. The startup WARN is
  updated accordingly. (E/C-5)
- `fluxgate.metrics.max-endpoint-tags <= 0` means unbounded — the
  `fluxgateEndpointTagLimitMeterFilter` bean is not registered when the value is zero or
  negative. (N-14)
- Rate-limited request and invocation log messages demoted from WARN to DEBUG in the filter
  and the AOP aspect. (N-15)

#### Breaking / Migration

1. **Redis bucket keys change layout — every quota resets once.**
   `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel|default}` becomes
   `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>`. The
   `{...}` hash tag pins all bands of one rule and key to one cluster slot,
   which is what makes the multi-band script atomic. Old buckets are not read
   and expire on their own TTL.
2. **Resolved key values are scope-prefixed and sanitised.** `192.168.1.100`
   becomes `ip:192.168.1.100`, a user id becomes `user:<id>`, an API key
   `key:<value>`, a custom attribute `custom:<value>`, and `GLOBAL` stays
   `global`. Characters outside `[A-Za-z0-9._:@-]` become `_`, and a value longer
   than 256 characters is replaced by the SHA-256 hex of the value. Combined with
   (1) this is **one** quota reset on upgrade, not two. External tooling that
   builds FluxGate keys must be updated.
3. **Bucket hash field renamed** `last_refill_nanos` → `last_refill_micros`. A
   0.3.x bucket is treated as missing and re-initialised full, which is a one-off
   burst allowance. Values are now plain digits, not `1.76e+18`. Any tooling
   reading these hashes must be updated.
4. **The 429 body changed** from `{"error":"...","retryAfter":N}` to an RFC 9457
   problem document with `Content-Type: application/problem+json;charset=UTF-8`.
   Restore the old shape with `fluxgate.ratelimit.response.body-template`, or
   take the body over entirely with a `RateLimitResponseWriter` bean. Clients
   that parsed the old JSON keys will break.
5. **The `fluxgate.requests.total` meter is gone.** Under Micrometer's Prometheus
   naming it exported as `fluxgate_requests_total` and collided with
   `fluxgate.requests`, which carries the `result` tag. Sum `fluxgate.requests`
   over `result` instead: `sum(rate(fluxgate_requests_total{result=~"allowed|rejected"}[5m]))`.
   The **constant** `FluxgateConstants.Metrics.REQUESTS_TOTAL` is only
   `@Deprecated`, not removed, so code referencing it still compiles.
   Separately, `endpoint` tag values are now normalized (`/api/users/12345` →
   `/api/users/{id}`) and capped by `fluxgate.metrics.max-endpoint-tags`
   (default 1000), so **a dashboard or alert matching raw URI labels breaks.**
6. **`fluxgate.ratelimit.include-patterns` default changed** from `/*` to all
   paths. An application that relied on the old default was in practice rate
   limiting nothing below the first path segment and will now see limits applied.
   Set the property explicitly to keep a narrow surface.
7. **Fail-closed defaults stay fail-closed.** `failure-behavior=DENY`,
   `missing-rule-behavior=DENY` and `trust-client-ip-header=false` are the
   defaults (introduced by the hardening line). A limiter failure or a missing
   rule set answers 429. Opt out per property with `ALLOW`.
8. **`trust-client-ip-header=true` without `trusted-proxies`** keeps legacy
   behaviour but logs one WARN at startup. Configure `trusted-proxies` in
   production: without it a client can rotate the forwarding header per request.
9. **`RateLimitRuleSet.builder().build()` and `RateLimitRule.build()` throw
   `InvalidRuleConfigException`**, not `IllegalArgumentException` /
   `IllegalStateException`. `RateLimitRule.build()` additionally rejects two
   bands with the same derived key label. Update `catch` blocks and tests.
10. **`permits > band capacity` throws `InvalidRuleConfigException`** instead of
    rejecting forever with an unreachable wait time.
11. **`fluxgate.ratelimit.collect-headers` defaults to `false`**, so
    `RequestContext.getHeaders()` is empty unless you opt in and allow-list the
    header names. `Authorization`, `Cookie`, `Set-Cookie`,
    `Proxy-Authorization` and `X-API-Key` are never copied.
12. **Windows longer than 24 hours are now enforced for their full length.** A
    7-day quota used to reset every 24 hours because of the TTL cap, so it
    effectively allowed 7× the configured capacity. It no longer does.
13. **Removed methods**: `BucketState.getRetryAfterSeconds()`,
    `RedisTokenBucketStore.close()`, `DefaultCircuitBreaker.handleOpenState()`.
    `CircuitBreaker.execute(...)` now always throws `CircuitBreakerOpenException`
    while the circuit is open, and nothing returns `null` any more — use
    `executeWithFallback(operation, action, fallback)`.
14. **Redis connection failures throw `org.fluxgate.core.exception.RedisConnectionException`.**
    The module-local `org.fluxgate.redis.connection.RedisConnectionException` now
    extends it, so existing `catch` blocks still compile and still catch.
15. **`fluxgate.ratelimit.filter-order` is an `Integer`** (unset by default,
    effective `1`). Code reading the property as a primitive `int` must handle
    `null`.
16. The commented-out `RedisRateLimitHandler` in `fluxgate-sample-filter` was
    deleted. The library's `EngineBackedRateLimitHandler` replaces it.
17. **`FluxgateResilienceProperties.CircuitBreaker.getFailureThreshold()` changed
    from `int` to `Integer`** (and the setter likewise) — a binary-incompatible
    signature change. Unset, the breaker uses the new sliding-window failure rate
    (`sliding-window-size` 20, `failure-rate-threshold` 50%,
    `minimum-number-of-calls` 10) instead of "N consecutive failures". Together
    with `circuit-breaker.enabled` now defaulting to `true`, **when the circuit
    opens changes for every deployment.**
18. **`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` default changed
    from `100` to `50`**, and `@RateLimit(maxConcurrentWaits = …)` is no longer
    read at all — the property is the only source, because the semaphore is
    aspect-wide.
19. **`RateLimitResult.allowedWithoutRule()` reports `remainingTokens = -1`**
    instead of `Long.MAX_VALUE`, and a `-1` means the header is **omitted**. A
    response that matched no rule used to carry
    `X-RateLimit-Remaining: 9223372036854775807`; it now carries no remaining
    header at all. Clients that require the header to be present will break.
20. **Rate limit headers are written on allowed responses too**, and the IETF
    `RateLimit-*` family is new. Turn them off with
    `fluxgate.ratelimit.include-headers=false` or
    `response.include-standard-headers=false` if a proxy or client is strict about
    unknown headers. `Retry-After` now has a floor of 1 second.
21. **`fluxgate.redis.enabled=true` with Redis down starts successfully** (lazy
    connect). Combined with `failure-behavior=DENY` — the default — **every request
    is answered 429 until the connection succeeds.** Re-check your rollout order,
    or set `fluxgate.ratelimit.fallback.mode=IN_MEMORY`. `fluxgate.redis.fail-fast=true`
    restores the old fail-at-startup behaviour.
22. **`AUTO` and `PUBSUB` reload now also poll** (`fluxgate.reload.pubsub.backstop-polling-interval`,
    default `60s`), so the rule store sees periodic queries it did not before. Set
    it to `0` to disable the backstop.
23. **Rule change notifications are published after the transaction commits.**
    `@NotifyRuleChange` / `@NotifyFullReload` inside a `@Transactional` boundary
    publish in `afterCommit` and **do not publish at all on rollback**. A test or
    operational script that assumed "published as soon as the method returns"
    needs updating. Failures are retried three times on a separate daemon thread.
24. **`ClientIpExtractor.extract(HttpServletRequest)` and
    `extract(request, header, boolean)` are `@Deprecated`** — move to the
    four-argument overload that takes `TrustedProxies`.

### Fixed

**Correctness**

- A downstream exception ran the filter chain — and the `@RateLimit` business
  method — **twice**: `doFilter` / `proceed()` sat inside the limiter's
  `try`/`catch`, so the catch block invoked it again. Both now run exactly once,
  outside the guarded block. (C2, C-1)
- `@RateLimit`'s `maxConcurrentWaits` was completely ineffective, because the
  semaphore was created per advice invocation; the wait semaphore is now an
  aspect-wide instance field. Unlimited thread blocking was a DoS vector. (C1)
- The polling reload strategy deleted **every bucket on every cycle**:
  `RateLimitRule` and `RateLimitBand` had no value `equals`/`hashCode`, so the
  version hash changed each poll and every poll looked like a rule change.
  (C3, C-2)
- A full bucket reset deleted Redis-stored rule set definitions, because
  `deleteAllBuckets()` globbed `fluxgate:*`. It now uses `fluxgate:bucket:*`, and
  per-rule-set deletion goes through `RedisRateLimiter.bucketKeyPattern(id)`
  with glob metacharacters escaped. (C4, C-3, PART 3 H-3)
- A request rejected by one band of a rule no longer consumes tokens from that
  rule's other bands: the Lua script evaluates all of a rule's bands in one call,
  checking every band before writing any, and `Bucket4jRateLimiter` pre-checks
  with `estimateAbilityToConsume` and refunds a concurrent over-consume.
  (H8, H-17)
- Refill discarded the sub-token remainder, which systematically under-allowed
  high-frequency bands. The timestamp now advances only by the time the credited
  whole tokens cost, so the remainder carries into the next call.
- Windows longer than 24 hours were silently reset by the TTL cap; the cap is
  gone. (H9)
- Two bands of one rule with no explicit label collided on one bucket key, so
  the second band was never enforced. `RateLimitBand.getKeyLabel()` derives
  `<capacity>-per-<windowSeconds>s`. (H10)
- The Lua script computed `reset_time_millis` **before** consumption on the
  allow path, advertising a reset that was already stale. (H-5)
- `last_refill_nanos` was stored through Lua's default `%.14g` as `1.76e+18`,
  losing precision; the script's "integer arithmetic only" claim was false. The
  time base is microseconds (inside the exact 2^53 double range) and every
  stored number goes through `string.format('%.0f', v)`. (H-6)
- `permits > band capacity` rejected forever and returned an unreachable wait
  time; it now throws `InvalidRuleConfigException`, pre-validated in Java. (H-7)
- The Lua-computed reset time and band capacity never reached the HTTP response,
  so `X-RateLimit-Limit` and `X-RateLimit-Reset` were absent despite being
  documented. (H-23)
- `RateLimitEngine.check()` could return `null` from a misbehaving `RateLimiter`;
  it now raises `IllegalStateException` instead of leaking the null.

**Security-relevant behaviour**

- `X-Forwarded-For` was trusted unconditionally and `trust-client-ip-header` was
  never read, so any client could bypass every `PER_IP` limit and fill the store
  with one bucket per forged value. The header is now honoured only when trusted,
  only from a `trusted-proxies` hop, and only for candidates that parse as an IP
  literal of at most 45 characters. (C5, H-1, PART 3 C-3)
- The filter copied **every** request header, `Authorization` and `Cookie`
  included, into `RequestContext`, which is handed to metrics recorders that may
  persist it. Header collection is opt-in, allow-listed, and credential-carrying
  headers are never copied. (H-24, PART 3 C-4)
- `LimitScopeKeyResolver` logged API keys and user ids in clear text, logged a
  WARN per request on the hot path, and let a missing identity inherit the
  authenticated tier's quota. Keys are now scope-prefixed, fallback logs are at
  DEBUG, values are masked in logs, and `missing-key-behavior=REJECT` refuses
  rather than widening the key. (H-21, PART 3 H-5)
- Unsanitised key values allowed namespace collisions and quota theft between
  scopes; `KeyValueSanitizer` restricts the character set and caps the length.
  (PART 3 H-5)
- Path include/exclude matching used the raw, unnormalized URI, so
  `/api/./users`, `/api//users` and `;`-parameters bypassed the limit.
  `RequestPathResolver` normalizes first, **including percent-encoded dot
  segments** (`%2e%2e`) — those survived the first fix and still bypassed both
  include and exclude patterns. A path that cannot be decoded is now treated as
  in-scope rather than falling back to the raw URI, which silently disabled the
  limit. (PART 3 H-6, N-1, N-2)
- `Session-Id` was collected unconditionally when `collect-headers` was on, and
  the never-copied denylist was missing authentication headers beyond
  `Authorization`. `Content-Length` must now be allow-listed explicitly if you want
  it. (N-9)
- The starter's own `maskUri` did not use `RedisUriUtils.mask`, so a Redis URI was
  still logged in clear text from two call sites even after the module-level fix.
  (N-10)
- Micrometer counters and timers tagged the endpoint without the cardinality cap
  that the gauges had, so `max-endpoint-tags` did not actually bound the registry.
  It now caps every FluxGate meter, with overflow tagged `endpoint="other"` rather
  than dropped, and the `MeterFilter` stays behind it as defence in depth for
  custom recorders. (N-13)
- A rejected request logged a WARN per request on the hot path. (N-15)
- `Bucket4jRateLimiter` cache eviction resets a caller's tokens, which combined
  with a forgeable identity scope was a bypass path; `max-buckets` and
  `expire-after-access` are documented as a security parameter, not only a memory
  one. (N-8)
- `RedisPubSubReloadStrategy.retryScheduler` was non-volatile and could be read as
  null during a concurrent restart. (N-10)
- `Pub/Sub` reload logging did not sanitise the raw message or the rule set id.
  (N-11)
- `metrics.max-endpoint-tags=0` was interpreted one way by the gauges and the
  opposite way by the `MeterFilter`. (N-14)
- `bucketKeyPattern` over-matched when a rule set id contained `:`. (N-17)
- A `MissingRateLimitKeyException` rejection was recorded against
  `rule_set="unknown"` in metrics. (N-13)
- The Lua reject path's `reset_time_millis` carried a different meaning from the
  allow path's. (N-11)
- `fluxgate.ratelimit.max-cost` of `0` or less meant "unlimited", so a typo let a
  client spend an unbounded number of permits per request. It is now a startup
  error when `cost-header` is set. (N-7)
- A `fallback.mode=IN_MEMORY` fallback bucket was never reset on a rule change.
  (N-6)
- Pub/Sub plus the polling backstop reset buckets **twice** for each rule change.
  (N-5)
- `CaffeineRuleCache`'s negative cache had an invalidation race, so a newly created
  rule set could take up to `negative-ttl` to become visible. (N-8)
- GitHub Actions workflows pinned third-party actions by tag rather than SHA while
  passing them the GPG signing key and passphrase, and `maven-ci.yml` had no
  `permissions:` block. (N-3, N-4)
- Lettuce versions differed between modules (6.4.0 vs 6.3.2). (N-5)
- CRLF in header-derived values was written straight into the MDC (log
  injection); `LogSanitizer` replaces control characters and caps the length.
  (M-3)
- A single unparseable Pub/Sub message triggered a full keyspace reset — an
  unauthenticated global rate limit bypass window for anyone who could publish
  to Redis. Unparseable messages are now ignored. (H18, PART 3 H-3)

**Resilience**

- Retry, circuit breaker and `ResilientExecutor` were complete but wired to
  nothing, so failure behaviour was not configurable at all. `ResilientRateLimiter`
  now decorates the limiter. (C7)
- The circuit breaker never opened on the `executeWithFallback` path, because the
  inner lambda swallowed the failure before the breaker could record it. (C-4)
- `FAIL_OPEN` returned `null` and NPE'd the caller; `executeWithFallback` now
  always returns the caller's fallback and `execute` always throws
  `CircuitBreakerOpenException` while open. (C-5)
- `HALF_OPEN` admitted unlimited concurrent trial calls, defeating the point of
  the state; at most `permittedCallsInHalfOpenState` now pass, gated by a
  semaphore published before the state flip. `getState()` no longer performs the
  transition as a side effect of being polled. (H14)
- The breaker opened only on consecutive failures, so an intermittent failure
  rate never tripped it. A count-based sliding window with a failure-rate
  threshold was added alongside the legacy consecutive rule.
- `WAIT_FOR_REFILL` blocked servlet worker threads without an effective bound and
  was dead in Redis mode; the policy now comes from the rule with the annotation
  as an explicit override, under an aspect-wide semaphore. (H11, H-3)
- Bucket reset used `KEYS`, blocking single-threaded Redis for O(N); it uses
  `SCAN` + `UNLINK`. (H12, M-7)
- Concurrent cache misses for the same rule set all hit MongoDB (stampede) and a
  missing rule set was re-queried on every request. `CaffeineRuleCache` loads
  atomically once and caches the negative result. (H13)
- `fluxgate.reload.strategy=NONE` made `ruleCache()` return `null`, which failed
  the whole application context with `UnsatisfiedDependencyException`.
- A failing reload listener let later listeners run anyway, so a bucket reset
  could execute against a stale cache. Listeners run in ordered groups and a
  failed group skips every higher order. (H20)
- Rule change notifications could be published **before** the database commit and
  were dropped silently on failure, leaving the data plane permanently on stale
  rules. Publishing is deferred to `afterCommit`, retried three times with
  jittered backoff, and final failures are counted and logged at ERROR.
  (H19, H22)

**Leaks and lifecycle**

- `Bucket4jRateLimiter`'s bucket map grew without bound (memory leak, OOM DoS
  vector). It is now a bounded, idle-expiring Caffeine cache. (H-16, PART 3 H-4)
- Micrometer gauges became `NaN`, because `registry.gauge(name, tags, long)`
  holds an autoboxed `Long` weakly; per-tag-set `AtomicLong` holders are
  registered once with `strongReference(true)`. Raw URIs as `endpoint` tags grew
  the meter registry and heap without bound; endpoints are normalized and the
  tag count is capped. (C6, H-11)
- `RedisPubSubReloadStrategy` never shut the Lettuce client down and leaked a
  Pub/Sub connection on every resubscribe. (H-9)
- `RedisRuleChangeNotifier` leaked a client on reconnect, and a race between
  `close()` and `publish()` could create a client after shutdown. (H-10)
- `LuaScripts` kept the script SHA in process-global mutable static state, so two
  FluxGate instances interfered with each other. Each store now owns a
  `LuaScriptRegistry`. (H-8)
- An exception thrown from `close()` skipped the client `shutdown()`. (H-13)
- `isConnected()` always returned `false` when commands were supplied
  externally, pinning the health check to DOWN forever. (H-12)
- `MDC.clear()` wiped MDC entries owned by other components; the filter now saves
  and restores the context it found. (H-4)

**Configuration and wiring**

- The default handler was `ALLOW_ALL` and the only Redis-backed handler in the
  repository was fully commented out, so an application that followed the README
  passed every request through unlimited. (H16)
- `fluxgate.ratelimit.include-patterns`, `exclude-patterns`, `filter-order`,
  `include-headers`, `client-ip-header`, `trust-client-ip-header`,
  `missing-rule-behavior` and `fluxgate.metrics.include-endpoint` were never read
  by library code. They are all honoured now. (H15, H-2)
- The aspect silently demoted `PER_USER`, `PER_API_KEY` and `CUSTOM` to per-IP
  limiting, because it built a context containing only the client IP. Filter and
  aspect share `RequestContextFactory`. (H17)
- `@ConditionalOnBean` on an `@Import`ed configuration evaluated before the bean
  existed, so the AOP aspect was silently not registered. (H-18)
- `getBeansWithAnnotation()` inside a `@Bean` method forced every singleton to
  initialise early; `ImportAware` replaces it. (H-19)
- The Boot 2 starter shipped no `META-INF/spring.factories`, so auto-configuration
  was silently disabled on Spring Boot 2.0–2.6. (H-20)
- `FluxgateHealthIndicator` reported `filterEnabled: false` while the filter was
  running, because it read a property instead of looking for the bean. (E-6)
- `RedisRateLimiterConfig` closed a connection provider it did not own.
- `maskPassword` did not mask the password of a userless URI such as
  `redis://:secret@host`, the exact form the documentation recommended. (H-14)
- Deprecated exception types were still being thrown, bypassing the exception
  hierarchy. (H-15)
- `CompositeMetricsRecorder` wrote to `System.err` and abandoned the remaining
  recorders when one threw.
- Integration tests ran `flushdb()` and `collection.drop()` against
  `localhost` with no guard, destroying developer and CI data. They use
  Testcontainers, run-scoped keys and per-test collections, and delete only what
  they created. (H-25)
- Boot 2 / Boot 3 starter drift: the Boot 2 `collectionExists` cursor leak, the
  missing registration file, and the missing `spring/handler` package.

**Documentation**

- `fluxgate-testkit/README.md` documented a `RateLimiterTestHelper` and a
  `MockRateLimiter` that do not exist, and the module contained only JMH
  benchmarks. Both the utilities and the README are now real. The benchmark package
  carries a `package-info.java` saying plainly that it is not an API.
- The root README, the `fluxgate-spring-boot3-starter` README and both
  `docs/**/architecture/README` files documented `X-RateLimit-Limit` and
  `X-RateLimit-Reset` response headers that the code never wrote, and an
  `include-headers` toggle that was never read. Both are now true.
- `docs/en/architecture/*.md` were stubs that contradicted the code in a dozen
  places (a `RateLimitHandler.handle(context)` method, `RuleSetProvider` rather than
  `RateLimitRuleSetProvider`, a `LimitScope` enum of `IP`/`USER_ID`/`API_KEY`/`COMPOSITE`,
  rule `path`/`method`/`priority` fields, a Jedis-based store, a single-key Lua
  script, a 24-hour TTL cap, `fluxgate.ratelimit.reload.*` property paths). They now
  describe the real classes and signatures.
- `docs/ko/architecture/deep-dive/*.ko.md` carry a note pointing at the corrected
  deep dive, and the bucket hash field name is fixed throughout.
- Both starter READMEs claimed FluxGate ships
  `org/fluxgate/spring/logback-spring.xml`. It does not; the docs now list the real
  MDC keys and show a logstash-encoder configuration instead.
- Removed dead links to `fluxgate-spring-boot-starter/README.md` and
  `HOW_TO_EXTEND_RATELIMITER.md`; added the missing `fluxgate-control-support`
  module row; corrected the sample module names
  (`fluxgate-sample-standalone-java11` / `-java21`) and the starter's own
  `artifactId` in its dependency snippet. (E-8, E-10, E-11)
- Corrected the root README configuration table, which listed
  `include-patterns: [/api/*]` and `default-rule-set-id: default` against code
  defaults of `{"/*"}` and `null`. (E-7)
- `docs/ARCHITECTURE_DEEP_DIVE.ko.md` §3–§4 presented APIs that do not exist
  (`rule.getPath()`, `findMatchingRule()`, `org.fluxgate.core.provider`,
  `RateLimitHandler.handle`, `keyResolver.resolve(rule, context)`) as "real
  source code". Both sections now describe the real
  `FluxgateRateLimitHandler` → `EngineBackedRateLimitHandler` →
  `RateLimitEngine` → `RateLimiter` flow. (E-1)
- Added the missing `docs/en/customization/request-context.md` and
  `key-resolver.md` that `docs/README.md` linked to, plus Korean twins. (E-9)
- `FluxgateProperties.AuditLogProperties` Javadoc no longer claims the
  properties are never consumed. (E-15)

### Security

- Fail-closed defaults (`failure-behavior=DENY`, `missing-rule-behavior=DENY`,
  `trust-client-ip-header=false`) are the shipped defaults, so a version upgrade
  alone adopts the safe behaviour.
- `trusted-proxies` closes the X-Forwarded-For spoofing path that bypassed every
  `PER_IP` limit and let an attacker inflate Redis key cardinality at will.
- Credential-carrying headers are never copied into `RequestContext`, and header
  collection is off by default.
- Key values are sanitised and length-capped; user ids and API keys are masked in
  logs; Redis URIs are logged as `host:port[/db]` only.
- Log injection via CRLF in MDC values is fixed (`LogSanitizer`).
- Pub/Sub reload messages are validated and versioned, and a garbage message can
  no longer trigger a global bucket reset. They can now also be **signed**: set
  `fluxgate.reload.pubsub.secret` on the data plane and `fluxgate.control.secret`
  on the control plane to the same value, and every unsigned or stale message is
  ignored. Unset, the channel remains unauthenticated — restrict Redis network
  access and use a dedicated database or ACL for it.
- `PER_USER` and `PER_API_KEY` no longer take a client-controlled header at face
  value when Spring Security is present: `fluxgate.ratelimit.identity.source`
  defaults to `PRINCIPAL_THEN_HEADERS` on a secured application.
- The health payload no longer exposes internal `host:port`, cluster topology or
  dependency failure messages unless
  `fluxgate.actuator.health.include-endpoint-details=true`.
- Event documents in MongoDB get a TTL index (`fluxgate.mongo.event-retention`,
  default 30 days). Each one carries a client IP, a user id and an API key
  fingerprint, so an unbounded collection is an unbounded breach radius.
- `fluxgate.redis.max-bucket-ttl` (default 7 days) bounds how much Redis memory a
  caller with a forgeable identity can pin down through a long-window rule.
- `fluxgate.ratelimit.max-cost` must be positive when `cost-header` is set; `0`
  used to mean "unlimited permits per request".
- Percent-encoded dot segments (`%2e%2e`) are removed during path normalization,
  closing a bidirectional include/exclude bypass, and a path that cannot be
  decoded is treated as in-scope rather than falling back to the raw URI.
- GitHub Actions are pinned by commit SHA, and `maven-ci.yml` declares a minimal
  `permissions:` block — the signing key's blast radius was every tag those
  workflows resolved.
- The GPG passphrase is passed to the release workflow through
  `MAVEN_GPG_PASSPHRASE` instead of a command-line argument, where it was visible
  in the process list and CI logs. (M-1)
- Test credentials are gone from test sources; CI uses a test-only MongoDB
  password. `docker/*.yml` bind published ports to `127.0.0.1`. `.gitignore`
  covers `*.key`, `*.pem`, `*.asc`, `*.p12`, `*.jks`, `.env`, `.env.*`. (M-5)
- `SECURITY.md` documents the reporting process, the final secure-default set,
  the identity-header caveat and key sanitisation.
- The credential exposure, missing authorization and open-proxy findings
  (PART 3 C-1, C-2, C-5, H-1, H-2, H-7, H-8) are in the FluxGate Studio and demo
  repositories, not in this one, and are tracked there.

### Deprecated

- `fluxgate.ratelimit.filter-enabled` — inert. The filter is registered by
  `@EnableFluxgateFilter` and switched off with `fluxgate.ratelimit.enabled=false`.
- `fluxgate.resilience.circuit-breaker.fallback` — inert. Express fail-open or
  fail-closed by which `CircuitBreaker` method you call and which fallback you
  pass.
- `@RateLimit(maxConcurrentWaits = …)` — ignored; the wait semaphore is
  aspect-wide. Use `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits`.
- `FluxgateConstants.Metrics.REQUESTS_TOTAL` — use `Metrics.REQUESTS` with the
  `result` tag.
- `org.fluxgate.redis.script.LuaScripts` and `LuaScriptLoader` (marked
  `forRemoval`) — use `LuaScriptRegistry`.
- `org.fluxgate.redis.store.RedisRuleSetStore`, `RuleSetData` and
  `RedisRateLimiterConfig.getRuleSetStore()` — store rules in MongoDB behind a
  `RateLimitRuleSetProvider`.
- `org.fluxgate.redis.connection.RedisConnectionException` — use
  `org.fluxgate.core.exception.RedisConnectionException`, which it now extends.
- The pre-`TrustedProxies` `ClientIpExtractor` overloads.

### Removed

- The `fluxgate.requests.total` meter — see Breaking item 5.
- `BucketState.getRetryAfterSeconds()`, `RedisTokenBucketStore.close()`,
  `DefaultCircuitBreaker.handleOpenState()`.
- `fluxgate-samples/fluxgate-sample-filter`'s commented-out
  `RedisRateLimitHandler`.

### Upgrading from 0.3.x

1. **Expect one quota reset.** Bucket keys and the bucket hash layout both
   change, so on first start every caller gets a full bucket. Roll out during a
   low-traffic window if a burst matters to you. Old keys are never read and age
   out on their own TTL; `redis-cli --scan --pattern 'fluxgate:*'` followed by
   `UNLINK` cleans them up early if you want the memory back.
2. **Re-check `include-patterns`.** If you never set it, FluxGate used to match
   `/*` — a single path segment — and now matches everything. Set the property
   explicitly if you want the narrow surface back.
3. **Set `trusted-proxies` if you set `trust-client-ip-header=true`.** Startup
   logs one WARN until you do, and until you do the forwarding header is taken at
   face value.
4. **Update anything that parses the 429 body.** It is now RFC 9457
   `application/problem+json`. `fluxgate.ratelimit.response.body-template`
   reproduces the old shape:
   `{"error":"Too Many Requests","retryAfter":{retryAfterSeconds}}`.
5. **Update dashboards and alerts that use `fluxgate_requests_total` without a
   `result` tag.** Sum `fluxgate.requests` over `result` instead. The dashboard
   and alert rules under `docker/` already do.
6. **Drop your custom `FluxgateRateLimitHandler` if it only adapted Redis.**
   With `fluxgate.redis.enabled=true` (or `fluxgate.ratelimit.mode=IN_MEMORY`)
   and a `RateLimitRuleSetProvider`, the starter registers
   `EngineBackedRateLimitHandler` for you. Your own bean still wins.
7. **Review fail-closed behaviour.** `failure-behavior=DENY` and
   `missing-rule-behavior=DENY` answer 429 when Redis is unreachable or a rule
   set is missing. If you would rather degrade than reject, either set `ALLOW` or
   — better — set `fluxgate.ratelimit.fallback.mode=IN_MEMORY` so limits keep
   applying per instance during an outage.
8. **Map `DEGRADED` to a non-200 status** if you probe health from a load
   balancer: `management.endpoint.health.status.http-mapping.DEGRADED=503`.
9. **Switch integration test commands.** `./mvnw test` no longer runs the
   integration tests; use `./mvnw verify` (or `-DskipITs` to skip them).
10. **Recompile against the exception changes.** `InvalidRuleConfigException`
    from rule building, `RedisConnectionException` from the core package, no
    `null` from the circuit breaker, and `throws IOException` gone from
    `RedisRateLimiterConfig`.

See [docs/en/operations/migration-0.4.md](docs/en/operations/migration-0.4.md)
for the full upgrade walkthrough
([한국어](docs/ko/operations/migration-0.4.ko.md)).

[Unreleased]: https://github.com/OpenFluxGate/fluxgate/compare/v0.3.7...HEAD
