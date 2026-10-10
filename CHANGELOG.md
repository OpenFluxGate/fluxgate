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
  `identity.api-key-header` (`X-API-Key`). Unset resolves to `PRINCIPAL`, with or
  without Spring Security, so `PER_USER` and `PER_API_KEY` never take a
  client-controlled header at face value unless the operator opts in with
  `HEADERS` or `PRINCIPAL_THEN_HEADERS` — which logs a WARN naming the trusted
  headers. The effective value is logged once at startup.
- `fluxgate.ratelimit.fail-on-missing-handler` (default `false`) — `true` fails
  the boot with a `MissingConfigurationException` instead of starting with no
  limits: when a limiter exists without a rule set provider and Redis is enabled
  or `fluxgate.ratelimit.mode` is set, and whenever the filter
  (`@EnableFluxgateFilter`) or the aspect (`@EnableFluxgateAspect`) finds no
  `FluxgateRateLimitHandler` at all — which includes the default configuration
  with no rule source. `false` keeps the startup ERROR / WARN and applies
  `failure-behavior`.
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
  (default `60s`) as the replay window. Every message without a valid signature —
  the legacy `"*"` full reload included — is logged at WARN and ignored. Pub/Sub
  only runs signed: without a secret `AUTO` polls and `PUBSUB` fails startup;
  `fluxgate.reload.pubsub.allow-unsigned`
  and `fluxgate.control.allow-unsigned` (default `false`) are the development-only
  way to run without one.
- `fluxgate.reload.pubsub.accept-legacy-signed` (default `true`) — whether a data plane
  with a secret still accepts signed schema version 1 messages, which bind neither the
  channel nor a nonce. The first one accepted is logged at WARN; set it to `false` once
  every control plane publishes version 2.
- `fluxgate.actuator.health.include-endpoint-details` (default `false`) — the
  health payload no longer exposes `host:port`, cluster node counts and dependency
  failure messages unless you opt in. The status and exception type are still
  reported, and the full message is logged at WARN.
- `fluxgate.ratelimit.collect-headers` (default `false`) and `header-allowlist`
  — request headers reach `RequestContext` only when opted in and allow-listed.
- `fluxgate.ratelimit.log-query-string` (default `false`) and
  `case-sensitive-patterns` (default `true`).
- `org.fluxgate.core.key.KeyValueSanitizer` — encodes key values injectively into
  `[A-Za-z0-9._:@-]`, at most 256 characters (see Breaking item 30).
- `RateLimitKey.of(prefix, rawValue)` — keeps a scope prefix and sanitises only the value,
  the same shape `LimitScopeKeyResolver` produces; `RateLimitKey.of(key)` sanitises the
  whole string. The prefix must be a name followed by `:` (`"user:"`, `"tenant:"`); an
  empty prefix, `":"` or one without a trailing `:` (`"h"`) is rejected.
- `RateLimitKey.withPrefix(prefix, key)` — puts a prefix in front of an existing,
  already sanitised key without encoding it again. The result is always
  `prefix + key.value()`: never hashed, shortened or rejected for length, and each call
  adds at most 64 characters (the prefix limit) to the key.
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
- `org.fluxgate.core.resilience.IgnoredCallException` — thrown by a guarded action to report
  an outcome that is neither a success nor a failure of the protected resource (a client or
  configuration error); the original exception is its cause. `DefaultCircuitBreaker` and
  `NoOpCircuitBreaker` record nothing for it, give back any half-open trial permit it held,
  and rethrow it unchanged from both `execute` and `executeWithFallback` without using the
  fallback; `RetryConfig` never retries it. **Custom `CircuitBreaker` implementations must do
  the same:** rethrow it without recording a success or a failure and without invoking the
  fallback. An implementation that does not know the type treats it like any other
  `RuntimeException`, so a misbehaving client counts against the breaker; `ResilientRateLimiter`
  still rethrows the original client error instead of degrading in that case.
- `Bucket4jRateLimiter.reset(String ruleSetId)`, `resetAll()`, `size()`,
  `getMaximumSize()`, and constructors taking `maximumSize` / `expireAfterAccess`.

**Hot reload**

- `CompositeReloadStrategy` — `AUTO` and `PUBSUB` now always run a polling
  backstop behind Pub/Sub (`fluxgate.reload.pubsub.backstop-polling-interval`,
  default `60s`, `0` disables), so a missed message self-heals.
- Ordered reload listeners: `AbstractReloadStrategy.addListener(listener, order)`
  with `ORDER_CACHE_INVALIDATION = -100` < `DEFAULT_ORDER = 0` < `ORDER_BUCKET_RESET = 100`.
- `InMemoryBucketResetHandler`, so the in-memory limiter participates in reloads.
- `RuleChangeMessage.version` (`SCHEMA_VERSION = 2`: every message carries version 2 and a
  `nonce`; signed `LEGACY_SCHEMA_VERSION = 1` messages are accepted while
  `fluxgate.reload.pubsub.accept-legacy-signed` is `true` (the default), see Breaking item 34; with
  no secret and `allow-unsigned=true` no signature check applies); `@NotifyRuleChange`
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
- MongoDB index creation on `fluxgate.mongo.ddl-auto=create`: unique
  `{ruleSetId: 1, id: 1}` (`ruleSetId_1_id_1_unique`) and `{id: 1}` (`id_1`), through
  `MongoRateLimitRuleRepository#ensureIndexes()`.

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
  files must list the same nine auto-configurations, and a test fails the build if
  they diverge. **It does not extend version support:** the Boot 2 starter uses
  `@AutoConfiguration`, a Spring Boot 2.7 API, so **Spring Boot 2.7.x is required**,
  not merely supported. Boot 2.7 is OSS end-of-life, so plan the move to the Boot 3
  starter.
- `fluxgate-spring-boot2-starter/README.md` — the Boot 2 starter had no README at
  all. It points at the Boot 3 reference and lists only the differences (`javax`
  imports, Java 11, the 2.7 requirement).
- New utility types `org.fluxgate.spring.util.{LogSanitizer, TrustedProxies, RequestPathResolver}`
  and `org.fluxgate.spring.filter.{RateLimitHeaderWriter, RequestContextFactory}`.
- `org.fluxgate.spring.actuator.FluxgateHealthStatusEnvironmentPostProcessor` — maps the
  DEGRADED health status to HTTP 503 by default
  (`fluxgate.actuator.health.degraded-http-status=503`; `0` or negative disables it) by
  contributing `management.endpoint.health.status.http-mapping.*` defaults as the
  lowest-precedence property source, together with Spring Boot's own `down=503` and
  `out-of-service=503`. Only statuses the application has not mapped itself are added, so
  every user mapping and `HttpCodeStatusMapper` bean still wins. It also contributes
  `management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown` unless
  the application sets an order, so `DEGRADED` reaches the aggregated `/actuator/health` and
  the readiness group; an order of the application's own without `degraded` is reported at
  WARN. Nothing is added when `fluxgate.actuator.health.enabled=false`. (N-16)
- All six items above mirrored byte-identically to `fluxgate-spring-boot2-starter`
  (`jakarta.` → `javax.`). (Mirror)

**0.4 hardening**

- `org.fluxgate.core.exception.RedisConnectionException.Phase` (`CONNECT`, `COMMAND`,
  `UNKNOWN`) and constructors taking a phase. Only `CONNECT` failures are retried; see
  Breaking item 33.
- `org.fluxgate.spring.handler.RateLimiterUnavailableException` — thrown by
  `ResilientRateLimiter`, `MissingRuleSetProviderRateLimitHandler` and
  `EngineBackedRateLimitHandler` once `failure-behavior=DENY` /
  `missing-rule-behavior=DENY` decided to reject. The filter and the aspect answer it
  with HTTP 503 and `Retry-After` when the wait is known.
  `RateLimitResponseWriter` gains a default `writeUnavailable(...)` (a minimal 503
  problem document) that `ProblemDetailRateLimitResponseWriter` renders with the
  configured content type and body template; `RateLimitExceededException#isServiceUnavailable()`
  tells a non-web caller which of the two happened.
- `MongoRateLimitRuleRepository#moveRule(id, fromRuleSetId, toRuleSetId)`, the
  `(ruleSetId, id)` overloads of `findById`, `existsById` and `deleteById`, and
  `ensureIndexes()`, which creates the unique `ruleSetId_1_id_1_unique` index and the
  `id_1` index and fails with the duplicated `(ruleSetId, id)` pairs listed.
- `RuleSetAccessControlSource` SPI — `MongoRuleSetProvider` looks up a rule set's access
  control through it, so a decorated repository no longer loses it.
- `org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder` — FluxGate's own MongoDB
  client, which is no longer a `MongoClient` bean (Breaking item 40).
- `fluxgate.limiter.failures` and `fluxgate.limiter.bucket_evictions` are now actually
  registered by the metrics auto-configuration. The first counts limiter dependency
  failures by `action` and `exception`; the second is a `FunctionCounter` bound to
  `Bucket4jRateLimiter#getEvictionCount()` — every eviction resets that key's quota.
- Rate limit events are written to MongoDB off the request thread, from a bounded queue
  (10000 events) with batched `insertMany`; a full queue drops the event and counts it.
- Rule change messages are schema version 2 and carry a random `nonce`; the signature
  covers a length-prefixed canonical form that binds the Pub/Sub channel. The subscriber
  remembers accepted nonces for the replay window and ignores replays, messages signed
  for another channel and version 2 messages without a nonce. Version 1 and unsigned
  messages keep their previous rules. A secret is trimmed (blank means none) on both
  sides, and one shorter than 32 bytes is reported at WARN.
- Pub/Sub reload messages are handled on a single `fluxgate-pubsub-listener` thread in
  arrival order, off the Lettuce event loop, with at most 10000 waiting messages (WARN
  and drop beyond that).
- `org.fluxgate.spring.aop.RateLimitExceededExceptionHandler` — a `@RestControllerAdvice`,
  registered by the new `FluxgateAopExceptionHandlerAutoConfiguration` when the aspect is active
  in a servlet application, that answers `RateLimitExceededException` with 429 plus the rate
  limit headers and `Retry-After`, or 503 when `isServiceUnavailable()` is true, through the
  configured `RateLimitResponseWriter`. It is ordered at `RateLimitExceededExceptionHandler.ORDER`
  (`Ordered.HIGHEST_PRECEDENCE + 1000`) so that an application's unordered catch-all
  `@ExceptionHandler(Exception.class)` cannot turn rejections into 500; an advice ordered before
  it wins, and a bean of the type replaces it wherever it is declared.
- `org.fluxgate.spring.handler.PermitsExceedCapacityException` (an
  `InvalidRuleConfigException`) and `RateLimitResponseWriter#writeCostExceeded(...)` — a
  request cost above the capacity of a matching band is answered with 429 and a problem
  document naming the cost and the capacity, without `Retry-After`.
- `fluxgate.ratelimit.response.unavailable-body-template` — the body of the 503 sent when
  rate limiting is unavailable, with the `body-template` placeholders. Unset, the 503 keeps
  using `body-template`.
- `org.fluxgate.spring.filter.FluxgateWaitPermits` — the wait permits shared by the filter
  and the aspect (bean `fluxgateWaitPermits`).
- `FluxgateMetrics(MeterRegistry, MicrometerMetricsRecorder)` and
  `MicrometerMetricsRecorder#endpointTag(String)` — the failure counter shares the
  recorder's endpoint tag policy.
- `fluxgate.control.*` and `fluxgate.ratelimit.rule-sets` are documented:
  [docs/en/guides/yaml-rule-sets.md](docs/en/guides/yaml-rule-sets.md)
  ([한국어](docs/ko/guides/yaml-rule-sets.ko.md)).

### Changed

- `RuleChangeNotificationException` now extends `FluxgateOperationException`
  (still unchecked, same package and constructors), and `RedisRuleSetStore`
  reports a corrupt or incomplete stored rule set as `InvalidRuleConfigException`
  instead of `IllegalStateException`. Code that caught `IllegalStateException`
  from `RedisRuleSetStore` must catch `InvalidRuleConfigException`.
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
- TOKEN_BUCKET and SLIDING_WINDOW bucket TTL is
  `min(max-bucket-ttl, max(1, ceil(window_seconds × 1.1)))` (`fluxgate.redis.max-bucket-ttl`,
  default `7d`); FIXED_WINDOW counters keep their absolute expiry at the window end
  (`PEXPIREAT`; a rejection sets it only on a counter that has no TTL). See item 12 of
  [Breaking / Migration](#breaking--migration).
- Rejections no longer write bucket state; only TTLs are refreshed, via `EXPIRE`,
  which is a no-op for a bucket that does not exist yet.
- `Bucket4jRateLimiter` holds buckets in a Caffeine cache
  (`maximumSize` 100 000) instead of an unbounded map, with one multi-bandwidth bucket
  per `(ruleSetId, ruleId, bands, key)`. A bucket expires after it has been idle for
  the longer of `expireAfterAccess` (1 hour, a minimum) and its longest band window.
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
- Rule sets declared under `fluxgate.ratelimit.rule-sets` are composed with the
  `delegateRuleSetProvider` bean whoever defines it — the MongoDB
  auto-configuration or your application. The bean is replaced by a
  `CompositeRuleSetProvider` (YAML first, the original delegate as fallback) under
  the same name, so hot reload caches both sources. A user-defined delegate no
  longer causes the YAML rule sets to be silently ignored.
  With YAML rule sets configured, the `delegateRuleSetProvider` bean is a
  `CompositeRuleSetProvider` whatever your bean's class is, so **inject it as
  `RateLimitRuleSetProvider`** — injecting your concrete class fails with
  `BeanNotOfRequiredTypeException`. The startup log names the bean and its
  original type when it is replaced.
- With several `RateLimitRuleSetProvider` beans and no `delegateRuleSetProvider`,
  hot reload now **fails startup** with a message naming the candidates instead
  of silently skipping caching. Name your rule source `delegateRuleSetProvider`,
  mark one provider `@Primary`, or set `fluxgate.reload.enabled=false`.
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
  returns the **right-most** XFF hop instead of the left-most (falling back to the remote
  address when that hop is not a valid IP literal). The startup WARN is
  updated accordingly. (E/C-5)
- `fluxgate.metrics.max-endpoint-tags <= 0` means unbounded — the
  `fluxgateEndpointTagLimitMeterFilter` bean is not registered when the value is zero or
  negative. (N-14)
- Rate-limited request and invocation log messages demoted from WARN to DEBUG in the filter
  and the AOP aspect. (N-15)

**0.4 hardening**

- `fluxgate.reload.pubsub.max-message-age` defaults to `60s`, the same value as
  `RedisPubSubReloadStrategy.DEFAULT_MAX_MESSAGE_AGE` (it was `5m` in the property and
  `60s` in the class). Breaking item 35.
- Invalid `fluxgate.*` configuration fails startup: non-positive Redis timeout and
  `max-bucket-ttl`, fallback size and expiry, wait permits and negative wait time, reload
  cache TTL and size, polling interval, Pub/Sub retry interval and replay window.
  YAML rule sets reject an unknown band `zone-id` (it fell back to UTC silently), a rule id
  used twice in one rule set and a band window or capacity that is not positive.
  `FluxgateProperties` validates itself (it implements `Validator`), so this applies
  whether or not a Bean Validation provider is on the classpath. Values documented as
  "zero disables" stay allowed. One WARN is logged when enabled `PER_API_KEY` YAML rules
  exist but the identity source never reads the API key header. Breaking item 38.
- `fluxgate.mongo.ddl-auto=validate` (the default) requires a unique
  `{ruleSetId: 1, id: 1}` index, whatever its name; `create` builds the indexes through
  `MongoRateLimitRuleRepository#ensureIndexes()` and fails startup instead of logging a
  warning. Breaking item 29.
- The public `FluxgateRateLimitFilter` (4-, 7- and 8-argument) and `RateLimitAspect`
  (2-argument) constructors now ignore forwarding headers and fail closed; they trusted
  `X-Forwarded-For` and failed open before. Breaking item 37.
- A rejected `@RateLimit` invocation writes the 429 only when the intercepted method is a
  Spring MVC handler (`@RequestMapping` or a shortcut, also on an implemented interface)
  that does not return a primitive. Every other method throws `RateLimitExceededException`
  instead of returning `null`. Breaking item 39.
- `X-Forwarded-For` (and the configured header) is read across **all** header lines and
  walked right to left. `ip:port`, `[v6]` and `[v6]:port` are reduced to the address; when
  the hop that would be used is not an IP literal the remote address is used, so a forged
  or garbage hop cannot choose the `PER_IP` bucket. Addresses are canonicalised, so
  different spellings of one address share one bucket.
- The filter and the aspect share one set of wait permits (a `FluxgateWaitPermits` bean
  named `fluxgateWaitPermits`, not a bare `Semaphore` bean, so an application injecting a
  `Semaphore` by type is unaffected), so `max-concurrent-waits` bounds both together, and
  `fluxgate.ratelimit.wait-for-refill.enabled=false`, set explicitly, now stops every wait
  including `@RateLimit(waitForRefill = true)`. Left unset, rule `WAIT_FOR_REFILL`
  policies do not wait while the annotation still does.
- A missing default rule set id is reported once (ERROR at filter creation under
  `missing-rule-behavior=DENY`, WARN on the first request under `ALLOW`) instead of
  per request.
- Redis: a cluster whose `cluster_state` is not `ok`, with failing slots, or whose node list
  cannot be read reports DOWN; cluster command timeouts are applied and the topology is
  refreshed; a reset UNLINKs bucket keys page by page; a configuration that fails to build
  closes the connection it owns; `NOSCRIPT` script reloads are throttled; Lettuce failures
  are wrapped in FluxGate exceptions (reset failures included), with `Phase.COMMAND`.
- Redis health no longer creates the lazy Redis connection as a side effect.
- Core: `**/` matches only at path segment boundaries; header lookup is case-insensitive
  with `Locale.ROOT`; `Bucket4jRateLimiter` keeps buckets consistent across reloads, expires
  idle buckets and reports the longest wait among rejecting rules (the Redis limiter does
  the same across bands and rules); rounding is overflow-safe; the circuit breaker counts
  only HALF_OPEN trial calls; script and evaluation failures are not retried; node lists with
  a comma in the password are split correctly.
- MongoDB reads BSON numbers leniently (`int`, `long`, `double`) and rejects unknown enum
  values; a malformed rule document is skipped with a WARN and counted instead of failing
  the whole rule set; divergent copies of the rule set's access control are merged fail-closed,
  independent of document order (union of each deny list, intersection of each allow list, a copy
  without that list counting as empty; allow lists with no entry in common merge to an empty allow
  list, which grants no bypass, with a WARN naming the rule set); every access-control write also
  sets an `aclUpdatedAt` marker so a copy whose lists were all cleared still takes part, and an
  interrupted clear no longer resurrects the revoked allow list (documents with neither marker nor
  list, such as 0.3.x ones, are ignored); `save()` of an existing rule no longer reads the access
  control; an unparseable stored CIDR is a non-retryable `FluxgateOperationException`.
- Header and attribute names in MongoDB rate limit events are escaped reversibly as BSON field
  names: `.` becomes `%2E`, `$` `%24`, NUL `%00` and `%` itself `%25`, and an empty or null name
  is stored as a lone `%`. This replaces the earlier replacement of `.` and `$` by `_`, under
  which `a.b` and `a_b` shared a field; queries over event fields with those characters use the
  escaped names.
- Build: both starters and the samples compile with `-parameters` (Spring 6 `@PathVariable`
  name binding), JaCoCo measures every class, and the unused logstash encoder dependency is
  gone. The release workflow (`release.yml`) derives the version from the `release/X.Y.Z` branch and
  verifies that tag `vX.Y.Z` points at the branch head; `maven-ci.yml` builds whatever version the
  POM declares.
- The samples no longer trust identity headers or client IP headers sent by the client.
- Samples (not published): `fluxgate-sample-mongo` addresses a rule as
  `/admin/rules/{ruleSetId}/{id}`; `fluxgate-sample-api` deletes with
  `DELETE /admin/rules/{ruleSetId}/{id}`, and its `POST /admin/sync` reads each band's `window`
  from the Control-plane JSON instead of assuming 60s, rounds fractional ISO-8601 windows
  (`PT0.5S`) up, and answers 422 when the first band's capacity or window is missing or not
  positive. The samples' Swagger UI version comes from the Maven project version (build-info)
  instead of a hardcoded string, and their OpenAPI info names the MIT license; the standalone
  samples default to a single Redis on `localhost:6379`, `fluxgate.mongo.ddl-auto: create` and
  `fluxgate.reload.strategy: AUTO` (polling until `FLUXGATE_RELOAD_SECRET` is set; the old `PUBSUB`
  default with no secret failed at startup) so the documented quick start works, and no longer set
  the inert `circuit-breaker.fallback`; every sample with a run command has a startup test that
  boots its shipped `application.yml` (Testcontainers Redis/MongoDB where needed, and a local
  stand-in for the rate limit check API that `fluxgate-sample-filter` calls);
  `fluxgate-sample-standalone-java21` declares the `logstash-logback-encoder` its
  `logback-spring.xml` needs; and the sample READMEs' endpoint tables, configuration blocks,
  response examples and comparison tables match the code.

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
   `global`. The value after the prefix is sanitised as described in item 30
   (this entry used to describe a plain `_` replacement and a bare SHA-256 hex for
   long values; superseded by 30). Combined with (1) this is **one** quota reset on
   upgrade, not two. External tooling that builds FluxGate keys must be updated.
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
   rule set answers **503** (see item 36), not 429. Opt out per property with `ALLOW`.
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
12. **The hard 24-hour bucket TTL cap is replaced by `fluxgate.redis.max-bucket-ttl`
    (default `7d`).** A 7-day quota used to reset every 24 hours, so it effectively
    allowed 7× the configured capacity. FIXED_WINDOW counters now expire at their
    window end (`PEXPIREAT`) and are exempt from the cap. TOKEN_BUCKET and
    SLIDING_WINDOW TTLs are capped at `max-bucket-ttl`: a bucket idle for longer
    than that expires and starts full, so a window longer than the cap is
    effectively shortened for idle callers. The limiter logs a WARN once per rule
    when this happens; raise `max-bucket-ttl` if you need longer windows.
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
    read at all — the property is the only source, because the wait permits are
    one `FluxgateWaitPermits` bean shared by the filter and the aspect.
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
    is answered 503 until the connection succeeds.** Re-check your rollout order,
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
25. **Identity headers are opt-in** (C-4). `fluxgate.ratelimit.identity.source`
    defaults to `PRINCIPAL`: without an authenticated principal, `X-User-Id` and
    `X-API-Key` are ignored and `PER_USER` / `PER_API_KEY` rules follow
    `missing-key-behavior` (per-IP by default). Without Spring Security nothing
    resolves an identity at all, which startup reports as a WARN. A deployment
    whose trusted proxy supplies these headers sets `identity.source=HEADERS` or
    `PRINCIPAL_THEN_HEADERS`. The `RequestContextFactory`, `FluxgateRateLimitFilter`
    and `RateLimitAspect` constructors without an `IdentitySource` follow the same
    default. The filter's `userId` / `apiKey` MDC entries follow suit: they hold
    the identity the limiter resolved (the principal by default), and the raw
    identity headers are only logged when header identity is enabled. See the
    0.4 migration guide, section 15.
26. **Pub/Sub rule reload requires a signing secret** (H-3). Without
    `fluxgate.reload.pubsub.secret`, the default `AUTO` strategy with
    `fluxgate.redis.enabled=true` no longer subscribes: it logs a WARN and falls
    back to `POLLING` (`fluxgate.reload.polling.interval`, default `30s`), so
    rule changes arrive by polling instead of push. An explicit
    `fluxgate.reload.strategy=PUBSUB` without a secret fails startup with an
    `IllegalStateException`. The control-support `RuleChangeNotifier`, which is
    only created when `fluxgate.control.redis.uri` is set explicitly, fails
    startup without `fluxgate.control.secret`. Set the same secret on both
    sides, or — for development only — set
    `fluxgate.reload.pubsub.allow-unsigned=true` and
    `fluxgate.control.allow-unsigned=true` (WARN at startup). Programmatic
    `RedisPubSubReloadStrategy` / `RedisRuleChangeNotifier` construction is
    unchanged. See the 0.4 migration guide, section 15.
27. **`@RateLimit(maxWaitTimeMs)` is capped by
    `fluxgate.ratelimit.wait-for-refill.max-wait-time-ms`** (H11). The aspect
    used only the annotation value, so a method could park a worker thread for
    longer than the global setting the documentation described as a cap. The
    effective limit is now the smaller of the two; an annotation above the
    property no longer lengthens the wait. The `RateLimitAspect` constructors
    without a `maxWaitTimeMs` argument use the property default (`5000`).
    WAIT_FOR_REFILL still blocks the request thread in both the filter and the
    aspect — see the migration guide, section 12.

28. **MongoDB rules are identified by `(ruleSetId, id)`; `save` no longer moves a rule.**
    Saving rule `r1` into rule set B used to move it out of rule set A. It now inserts a
    second document; `moveRule(id, fromRuleSetId, toRuleSetId)` is the explicit move. The
    id-only `findById(id)`, `existsById(id)` and `deleteById(id)` are `@Deprecated`;
    `findById(id)` and `deleteById(id)` throw `IllegalStateException` when the id exists in
    several rule sets (`existsById(id)` returns `true`), and
    `saveAccessControl` throws when no document of the rule set matched. *Action:* move to
    the `(ruleSetId, id)` overloads and call `moveRule` where you relied on `save` to move.
29. **MongoDB needs a unique `{ruleSetId: 1, id: 1}` index.** `fluxgate.mongo.ddl-auto=validate`
    (the default) fails startup without one (any name is accepted); `ddl-auto=create` builds
    `ruleSetId_1_id_1_unique` and `id_1` and now fails startup, listing the duplicated pairs,
    instead of logging a warning. *Action:* before upgrading run
    `db.rate_limit_rules.createIndex({ruleSetId: 1, id: 1}, {unique: true, name: "ruleSetId_1_id_1_unique"})`
    (fix duplicates first), or start once with `ddl-auto=create`.
30. **Key values are sanitised injectively.** A value with characters outside
    `[A-Za-z0-9._:@-]` used to have them replaced by `_` (so `a+1` and `a_1` shared a bucket).
    It is now `h:` + the restricted value + `:` + the first 16 hex digits of its SHA-256
    (`a+1` becomes `h:a_1:<16 hex>`) when it is at most 237 characters long; a value that
    needs rewriting and is longer than 237 characters (the 256-character limit minus 19 characters of
    `h:` and digest overhead) becomes `h:<64 hex>`, as does any value over 256 characters.
    The scope prefix stays outside the hash (`user:h:<sha256>`). Values that were already
    clean keep their keys. Sanitising is no longer idempotent: sanitise a raw
    value once, and use `RateLimitKey.of(prefix, rawValue)` in a custom resolver. Configured
    `allowed-keys` / `denied-keys` are normalised the same way (WARN when an entry changes);
    an entry already in encoded form (`user:h:a_1:<16 hex>`) is kept as is. Only the
    built-in prefixes are recognised: for a custom prefix (`tenant:`), write an entry
    whose value gets rewritten in encoded form (`tenant:h:a_1:<16 hex>`).
    *Action:* update external tooling that builds keys; callers whose values were rewritten
    get a fresh bucket once.
31. **Redis bucket key segments are escaped, not replaced.** `ruleSetId` and `ruleId`
    characters `: { } * ? [ ] \`, whitespace and `%` are percent-escaped (`%XX`, UTF-8), and `:`
    and `%` in a band label likewise, so `a:b`, `a_b` and `a b` no longer share a bucket.
    Ids and labels without those characters keep their keys. *Action:* only rule sets,
    rules or labels using such characters reset once; update tooling that scans keys.
32. **`AccessControl` equality includes the IP lists, and a duplicate rule id in one rule set is
    rejected** with `InvalidRuleConfigException` by `RateLimitRuleSet.Builder.build()`
    (two rules with one id shared buckets and metrics). *Action:* give each rule in a rule set
    a unique id.
33. **`RedisConnectionException` has a `Phase` (`CONNECT`, `COMMAND`, `UNKNOWN`) and only
    `CONNECT` is retried.** The constructors without a phase mean `UNKNOWN`, which is treated
    like `COMMAND` and **not** retried; a Lettuce failure while a script runs
    (consume, check, refund) or a reset scans and unlinks keys is `COMMAND`. Retrying a
    command that Redis may already have executed could charge a request twice. *Action:*
    custom code that throws the exception and wants a retry passes `Phase.CONNECT`.
34. **Rule change messages are schema version 2** (nonce, channel bound, replay protected).
    A 0.4 data plane understands versions 1 and 2, but a data plane that only knows version 1
    drops version 2 messages. *Action:* upgrade the **data plane (subscribers) before the
    control plane (publishers)**; during the gap the polling backstop covers rule changes.
35. **`fluxgate.reload.pubsub.max-message-age` defaults to `60s`** (was `5m` in the property).
    A signed message older than that is ignored, so clocks of publisher and subscribers must
    agree to well within a minute. *Action:* set `5m` explicitly to keep the old window.
36. **Rate limiting being unavailable answers HTTP 503, not 429.** A limiter failure under
    `failure-behavior=DENY`, a missing rule set or provider under `missing-rule-behavior=DENY`,
    or a rule set that cannot be built throws `RateLimiterUnavailableException`; the filter
    and the aspect answer 503 (with `Retry-After` only when the wait is known, for example
    while the circuit breaker is open). An exceeded limit stays 429. A rule set that cannot
    be built is a configuration error, not a limiter failure, so it is 503 under
    `failure-behavior=ALLOW` as well; `ALLOW` used to let such requests through. *Action:* clients,
    gateways and alerts that treat 429 as "rate limited" and 503 as "outage" now see the
    outage correctly; code calling `ResilientRateLimiter`, `EngineBackedRateLimitHandler` or
    `MissingRuleSetProviderRateLimitHandler` directly must handle the exception instead of a
    rejected result.
37. **The legacy filter and aspect constructors are secure by default.** The 4-, 7- and
    8-argument `FluxgateRateLimitFilter` constructors and the 2-argument `RateLimitAspect`
    constructor trusted `X-Forwarded-For` and failed open; they now ignore forwarding headers
    and fail closed. *Action:* hand-built filters and aspects that need the old behaviour use
    the constructors taking `clientIpHeader`, `trustClientIpHeader` and `failOpenOnError`.
38. **Invalid configuration fails startup** — an unknown band `zone-id`, a duplicate rule id in
    a YAML rule set, a zero or negative window or capacity, and non-positive timeouts, TTLs,
    intervals or sizes. *Action:* fix the configuration; each message names the property.
39. **A rejected `@RateLimit` on a method that is not an MVC handler throws
    `RateLimitExceededException`** instead of writing a 429 and returning `null` (a primitive
    return type failed with `AopInvocationException`). In a servlet application
    `RateLimitExceededExceptionHandler` answers it with 429, or 503 when
    `isServiceUnavailable()` is true, instead of the 500 an unhandled exception produced.
    The default advice is ordered at `RateLimitExceededExceptionHandler.ORDER`, ahead of
    unordered application advice, so a catch-all `@ExceptionHandler(Exception.class)` no longer
    wins. *Action:* handle the exception in the service layer, or in your own
    `@ControllerAdvice` ordered before the default
    (`@Order(RateLimitExceededExceptionHandler.ORDER - 1)`) to use your own format; an unordered
    advice no longer sees the exception.
40. **FluxGate's MongoDB client is no longer a `MongoClient` bean.** It lives in
    `FluxgateMongoClientHolder`, and the auto-configuration runs after Boot's
    `MongoAutoConfiguration`, so Spring Data MongoDB can no longer write application data to
    the FluxGate cluster. An application bean named `fluxgateMongoClient` is still used (and
    not closed by FluxGate). *Action:* stop injecting FluxGate's client as `MongoClient`.

**Samples and CI**

- Sample defaults are safer: `missing-rule-behavior: DENY`, `allow-unsigned: false`, the actuator
  conditions removed, health `show-details: when_authorized`, and the `docker` profile pointing at the compose service hosts. Headers
  such as `X-User-Id` are documented as demo-only (`identity.source=HEADERS`), sample READMEs pin
  `mongo:7.0.14` / `redis:7.2.5-alpine`, and logged Redis URIs mask everything up to the last `@` of
  the authority.
- CI and release hardening: the release workflow runs with read-only default `permissions` (write only
  on the release job), requires the Redis Cluster ITs (`-Dfluxgate.redis.cluster.require=true`) and
  verifies the `maven-wrapper.jar` SHA-256 before running `mvnw`; the benchmark workflow uses per-job
  permissions and path filters; Dependabot covers the Boot 2 starter and `docker-compose`; CI service
  images are pinned to explicit tags.

### Fixed

**Correctness**

- Synthetic result keys keep their prefix when the id or key after it has to be
  rewritten: `missing-rule-set:<ruleSetId>` and `missing-key:<ruleId>` are built with
  `RateLimitKey.of(prefix, value)`. A rule set id such as `orders v2` used to produce
  `h:missing-rule-set:...`, which `EngineBackedRateLimitHandler` did not recognise as a
  missing rule set. A denied key is `denied:` + the resolved key verbatim (built with
  `RateLimitKey.withPrefix`, which never hashes or shortens): a long key no longer loses its
  `denied:` prefix, and a key that is already encoded (`h:...`) is not encoded a second time.
- `MongoRateLimitRuleRepository.save` addresses a rule by `(ruleSetId, id)`. Saving a
  rule into a different rule set inserts a new document and leaves the original alone
  (it used to move it out of its rule set); `moveRule(id, fromRuleSetId, toRuleSetId)`
  is the explicit move and gives the moved rule the destination rule set's access
  control. See Breaking item 28. Known limitation, documented: a new or moved document
  copies access control in a read followed by a separate write, so a concurrent
  `saveAccessControl` can leave that one document stale until it is called again.
- With hot reload enabled (the default), an application whose only rule source
  was not named `delegateRuleSetProvider` — YAML rule sets only, or a single
  application provider bean under another name — failed to start with a
  circular reference: the reload wiring instantiated "every
  `RateLimitRuleSetProvider`", including the `CachingRuleSetProvider` that was
  being created. Candidates are now selected from bean metadata with the caching
  provider excluded first, so the caching provider wraps that single source.
- A downstream exception ran the filter chain — and the `@RateLimit` business
  method — **twice**: `doFilter` / `proceed()` sat inside the limiter's
  `try`/`catch`, so the catch block invoked it again. Both now run exactly once,
  outside the guarded block. (C2, C-1)
- `@RateLimit`'s `maxConcurrentWaits` was completely ineffective, because the
  semaphore was created per advice invocation; the wait permits are now one
  `FluxgateWaitPermits` bean shared by the filter and the aspect. Unlimited thread
  blocking was a DoS vector. (C1)
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
- A request rejected by a later **rule** no longer drains the rules evaluated
  before it. `RedisRateLimiter` evaluates every matching rule in **one** Lua
  call, all-or-nothing, whenever all their keys may share a script call — always
  on a standalone Redis, and on a cluster when they hash to one slot. Otherwise
  it charges rule by rule and, when one rejects or Redis fails, refunds the rules
  already charged through the new `token_bucket_refund.lua`
  (`RedisTokenBucketStore.refund`): a token bucket is capped at its capacity, a
  sliding window decrements the sub-bucket that was charged, a fixed window
  decrements its counter only while it still counts the charged window. Keys of
  every rule are resolved before anything is charged, so a missing key no longer
  drains earlier rules either. Compensation is not atomic: between charge and
  refund concurrent requests see the earlier rule one permit lower, and a refund
  that cannot run (process death, Redis failure) leaves the permit spent.
  `BucketState.redisTimeMicros()` carries the Redis time of each decision; the
  consume script returns it as an 8th element. The in-memory
  `Bucket4jRateLimiter` (`mode=IN_MEMORY` and the `fallback.mode=IN_MEMORY`
  limiter) is now all-or-nothing across rules: it resolves every key first,
  locks the involved buckets in a global order, checks every rule with
  `estimateAbilityToConsume` and charges only when all of them can serve the
  request. (H8, H-17)
- A FIXED_WINDOW counter could carry its count into the next window: the key held
  no window index, Redis treats a key as expired only when `now > expiry`, so a
  request in the exact expiry millisecond read the old count and `PEXPIREAT`
  extended it — and a calendar window with an app clock ahead of Redis did the
  same with yesterday's count. The counter is now a hash
  `{count, window_end_micros}` under `<bucket key>:fw`, and the script compares
  window ends explicitly; a node whose clock lags keeps the newer window instead
  of resetting it.
- Refill discarded the sub-token remainder, which systematically under-allowed
  high-frequency bands. The timestamp now advances only by the time the credited
  whole tokens cost, so the remainder carries into the next call.
- Windows longer than 24 hours were silently reset by the hard 24-hour TTL cap.
  The cap is now `fluxgate.redis.max-bucket-ttl` (default `7d`) for TOKEN_BUCKET
  and SLIDING_WINDOW, with a WARN once per rule it shortens; FIXED_WINDOW counters
  expire at their window end and are exempt. (H9)
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
  as an explicit override, under the shared `FluxgateWaitPermits`. (H11, H-3)
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

**0.4 hardening**

- `/actuator/health` answered 200 for `DOWN` and `OUT_OF_SERVICE` in every application: the
  `DEGRADED` mapper replaced Boot's with a non-empty mapping, which dropped Boot's defaults.
  The defaults are kept now (see the `FluxgateHealthStatusEnvironmentPostProcessor` entry).
- `fluxgate.limiter.failures` was never emitted and `fluxgate.limiter.bucket_evictions` was
  evaluated before the `MeterRegistry` existed; both are registered. Endpoint tag values
  `other` and `unknown` are exempt from the endpoint tag cap, so the overflow series the
  recorder falls back to is not denied. YAML rule sets get allowed/rejected counters.
- A sliding window no longer rejects forever after a configuration change; `FIXED_WINDOW` is
  exempt from the `max-bucket-ttl` clamp warning.
- A rule-set reload keeps Bucket4j buckets consistent instead of resetting or splitting them.
- A Redis password no longer appears in a URI parse error raised by the Pub/Sub subscriber or
  `RedisRuleChangeNotifier`, nor in the log line of the auto-configured notifier, and the
  notifier publishes outside its connection lock.
- Fixed a `NoProviderFoundException` at startup when `jakarta.validation-api` (for example
  through springdoc) was on the classpath without a Bean Validation provider.
- Redis failed reconnects do not leak connections: `LazyRedisRateLimiter` opens and closes a
  connection per attempt and the server-side client count stays flat.
- `RateLimitEngine` access control: the synthetic fallback key is `ip:` + the sanitised client
  IP, the shape the resolver produces, so an `ip:` key entry matches it; a key resolver failure
  other than `MissingRateLimitKeyException` propagates instead of being swallowed; with only IP
  lists configured no key is resolved.
- `Bucket4jRateLimiter` no longer throws for a band window beyond `Long.MAX_VALUE` nanoseconds
  (it is capped at ~292 years) and computes reset times without overflow.
- `SimpleAntPathMatcher` merges `**/**` only when the first `**` starts a segment, so `a**/**`
  no longer matches `abc`.
- `RedisUris` looks for the end of the credentials only inside the first node's authority: an
  `@` in the query (`?clientName=a@b`) or in a later node (`redis://:p@n1,u@n2`) no longer hides
  the node separator.
- An oversized request cost no longer opens the circuit breaker. A `cost-header` value above
  the capacity of a matching band raised `InvalidRuleConfigException` inside
  `ResilientRateLimiter`, which counted it as a limiter failure and degraded, so an
  unauthenticated client could open the breaker for everyone (a global 503 under
  `failure-behavior=DENY`, no limits under `ALLOW`). The cost is now rejected before the
  breaker with 429, and every other configuration error or `IllegalArgumentException` from
  the primary limiter is rethrown without retry, breaker failure or fallback.
- Such a client or configuration error also no longer counts as a circuit breaker *success*:
  it reset the consecutive failure count of a closed circuit and could close a half-open one
  without the backend ever being reached. `ResilientRateLimiter` now reports it as the new
  `org.fluxgate.core.resilience.IgnoredCallException`, which `DefaultCircuitBreaker` and
  `NoOpCircuitBreaker` record as neither success nor failure (the half-open trial permit is
  given back) and rethrow without using the fallback, and which is never retried.
  `DefaultRetryExecutor` rethrows it (and any other non-retryable failure) at once, also on
  the last attempt, instead of logging it as an exhausted retry chain at ERROR.
- `/actuator/health` and the readiness group aggregated a degraded FluxGate to `UP`: Boot's
  default status order does not list `DEGRADED`, so its aggregator dropped the status even
  though it was mapped to 503. FluxGate now contributes a default order containing it.
- A `@RateLimit` rejection thrown outside an MVC handler method reached Spring MVC as an
  unhandled `RateLimitExceededException` and became HTTP 500; it is 429 or 503 now.
- `fluxgate.limiter.failures` tagged the raw endpoint against a budget of its own, ignored
  `fluxgate.metrics.include-endpoint` and was denied by the endpoint `MeterFilter` once the
  request meters had spent the budget, so failures vanished under high traffic. It now uses
  the recorder's normalised (`{id}`), bounded endpoint tag and is exempt from the filter.
- Concurrent first requests to new endpoints could each pass the endpoint tag budget check
  before any of them was recorded, registering more than `fluxgate.metrics.max-endpoint-tags`
  values; admitting a new value is now atomic.
- `TrustedProxies` and `ClientIpExtractor` parse IP literals themselves. A hostname made of
  hex digits and dots (`cafe1.de`) passed the old character check and was resolved through
  DNS from a forwarding header, and shorthand such as `1.2.3` was expanded to `1.2.0.3`; only
  strict dotted quads and IPv6 (full, compressed, embedded IPv4 as the last two groups only -
  `1.2.3.4::` is rejected - optional zone id) are accepted, and nothing is ever resolved.
- The wait semaphore was a plain `Semaphore` bean, so an application injecting a
  `Semaphore` by type received FluxGate's or failed with an ambiguous injection; it is a
  `FluxgateWaitPermits` bean now.
- `fluxgate.mongo.ddl-auto=validate` accepted a unique `(ruleSetId, id)` index that is
  `sparse`, partial or uses a collation other than `simple`, none of which keeps every rule
  unique; such an index is now named in the startup failure.
- The MongoDB rule converter omitted a band's `zoneId` only when it was the literal `"UTC"`, so
  bands built in Java with the default `ZoneOffset.UTC` were stored as `zoneId: "Z"`. Any UTC
  spelling (`Z`, `UTC`, `Etc/UTC`, `+00:00`) is now omitted and reads back as `ZoneOffset.UTC`;
  documents that already store `"Z"` still load.
- A rejecting Redis `SLIDING_WINDOW` band handed out a `Retry-After` that was too short: it
  waited only until its oldest counted sub-bucket left the window, or, when every count sat in
  the current sub-bucket, until the next sub-bucket started, so a burst was rejected again on
  every retry for almost a whole window (and could lose the longest-wait comparison to a
  `TOKEN_BUCKET` band of the same rule). The script now walks the counted sub-buckets oldest
  to newest and waits for the first one whose departure lets the request fit.
- The Redis `SLIDING_WINDOW` reset time (`X-RateLimit-Reset`, `reset_time_millis`) is rounded
  **up** to the millisecond; a sub-bucket that is not a whole number of milliseconds made it
  up to 1 ms earlier than the moment a rejected request may retry.
- `token_bucket_refund.lua` validates every band before it refunds any. An invalid band after
  a valid one (an unknown algorithm code, too few sliding sub-buckets) used to leave the bands
  before it refunded when the script failed, because Redis does not roll a script back.
- A metrics recorder that threw failed the request after its tokens had been charged:
  `Bucket4jRateLimiter` and `RedisRateLimiter` let the exception escape `tryConsume`, and
  `ResilientRateLimiter` then treated the charged call as a limiter failure and charged the
  in-memory fallback as well (or applied `failure-behavior`). Both limiters now catch a
  recorder's exception (not an `Error`), log it at WARN at most once a minute (DEBUG otherwise) and
  return the decision. `CompositeMetricsRecorder` logs a failing delegate the same way instead
  of an ERROR line per request and still calls the others. The starters' request duration
  timer (filter and aspect) and the `fluxgate.limiter.failures` recorder are isolated likewise:
  a failure there no longer fails a request that was already decided, or replaces the result of
  a `@RateLimit` method from the aspect's `finally` block.
- The throttled WARN about an undecodable request path (`RequestPathResolver`) was never
  logged: its last-warning timestamp started at `Long.MIN_VALUE`, so `now - last` overflowed
  to a negative value and the once-a-minute check never passed. Such requests were still rate
  limited, but only DEBUG recorded them. The first one now warns, and repeats within a minute
  stay at DEBUG.

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
  ignored. Pub/Sub never runs unsigned by default: without a secret `AUTO`
  falls back to polling (WARN), an explicit `PUBSUB` fails startup, and the
  control-support notifier fails startup without `fluxgate.control.secret`. `allow-unsigned=true` on either side is the
  development-only escape hatch and logs a WARN. (H-3)
- `PER_USER` and `PER_API_KEY` no longer take a client-controlled header at face
  value: `fluxgate.ratelimit.identity.source` defaults to `PRINCIPAL`, and header
  identity (`HEADERS`, `PRINCIPAL_THEN_HEADERS`) is an explicit opt-in that logs
  a WARN at startup. An unauthenticated caller can no longer pick its bucket by
  rotating `X-User-Id` / `X-API-Key`. (C-4)
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
- Rule change messages are replay protected (schema version 2: nonce, channel binding,
  60s window), a Redis password never appears in a URI parse error or a notifier log line,
  and a forwarded header with a forged or non-IP hop falls back to the remote address
  instead of choosing the `PER_IP` bucket.
- Key values and Redis key segments are escaped injectively, so two different identities
  can no longer share a bucket or an allow/deny entry (Breaking items 30 and 31).
- The starter's samples no longer trust client-supplied identity or IP headers.

### Deprecated

- `fluxgate.ratelimit.filter-enabled` — inert. The filter is registered by
  `@EnableFluxgateFilter` and switched off with `fluxgate.ratelimit.enabled=false`.
- `fluxgate.resilience.circuit-breaker.fallback` — inert. Express fail-open or
  fail-closed by which `CircuitBreaker` method you call and which fallback you
  pass.
- `@RateLimit(maxConcurrentWaits = …)` — ignored; the wait permits are the
  shared `FluxgateWaitPermits` bean. Use `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits`.
- `FluxgateConstants.Metrics.REQUESTS_TOTAL` — use `Metrics.REQUESTS` with the
  `result` tag.
- `org.fluxgate.redis.script.LuaScripts` and `LuaScriptLoader` (marked
  `forRemoval`) — use `LuaScriptRegistry`.
- `org.fluxgate.redis.store.RedisRuleSetStore`, `RuleSetData` and
  `RedisRateLimiterConfig.getRuleSetStore()` — store rules in MongoDB behind a
  `RateLimitRuleSetProvider`.
- `org.fluxgate.redis.connection.RedisConnectionException` — use
  `org.fluxgate.core.exception.RedisConnectionException`, which it now extends.
- The pre-`TrustedProxies` `ClientIpExtractor` overloads (`extract(request)` and
  `extract(request, header, trustHeader)`); use the four-argument overload taking
  `TrustedProxies`.
- `MongoRateLimitRuleRepository#findById(String)`, `existsById(String)` and
  `deleteById(String)` — use the `(ruleSetId, id)` overloads.
- The 6-argument `RuleChangeMessage(Integer, String, boolean, long, String, String)`
  constructor — use the 7-argument one taking the nonce the signature was computed over, or
  `signed(secret, channel)`. It throws `IllegalArgumentException` for a signature with
  version 2 or later, because the nonce it generates could never match that signature.

### Removed

- The `fluxgate.requests.total` meter — see Breaking item 5.
- `BucketState.getRetryAfterSeconds()`, `RedisTokenBucketStore.close()`,
  `DefaultCircuitBreaker.handleOpenState()`.
- `fluxgate-samples/fluxgate-sample-filter`'s commented-out
  `RedisRateLimitHandler`.
- The separate `@Primary` `compositeRuleSetProvider` bean of
  `FluxgateMongoAutoConfiguration` (present only in 0.4 pre-release builds). The
  YAML + delegate composite is now the `delegateRuleSetProvider` bean itself;
  inject `RateLimitRuleSetProvider` (or qualify by `delegateRuleSetProvider`)
  instead of `compositeRuleSetProvider`.

### Upgrading from 0.3.x

1. **Expect one quota reset.** Bucket keys and the bucket hash layout both
   change, so on first start every caller gets a full bucket. Roll out during a
   low-traffic window if a burst matters to you. Old keys are never read and age
   out on their own TTL. To reclaim the memory sooner, use the dry-run-first
   cleanup in [migration guide §1](docs/en/operations/migration-0.4.md#1-every-quota-resets-once);
   a bare `fluxgate:*` sweep would also delete 0.4 buckets and the Redis rule store.
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
7. **Review fail-closed behaviour and treat 503 as "rate limiting unavailable".**
   With `failure-behavior=DENY` and `missing-rule-behavior=DENY` the filter and the aspect
   answer 503, not 429, when Redis is unreachable or no rule set exists: update client retry
   rules (a client that retries on 429 must now retry on 503) and alerts. If you would rather
   degrade than reject, either set `ALLOW` or — better — set
   `fluxgate.ratelimit.fallback.mode=IN_MEMORY` so limits keep applying per instance during an
   outage.
8. **`DEGRADED` health now answers HTTP 503 by default**
   (`fluxgate.actuator.health.degraded-http-status`, `0` disables), and it now reaches the
   aggregated `/actuator/health` and the readiness group through a default
   `management.endpoint.health.status.order`, so those probes answer 503 too while FluxGate is
   degraded. Remove a hand-written `http-mapping.DEGRADED` entry only if you want the default;
   yours wins, and so does a `status.order` of your own (add `degraded` to it).
9. **Switch integration test commands.** `./mvnw test` no longer runs the
   integration tests; use `./mvnw verify` (or `-DskipITs` to skip them).
10. **Recompile against the exception changes.** `InvalidRuleConfigException`
    from rule building, `RedisConnectionException` from the core package, no
    `null` from the circuit breaker, and `throws IOException` gone from
    `RedisRateLimiterConfig`.
11. **Create the MongoDB unique index before the first start** if you use
    `fluxgate.mongo.ddl-auto=validate` (the default):
    `db.rate_limit_rules.createIndex({ruleSetId: 1, id: 1}, {unique: true, name: "ruleSetId_1_id_1_unique"})`.
    Or start once with `ddl-auto=create`. Duplicate `(ruleSetId, id)` pairs must be removed first.
12. **Roll out Pub/Sub reload data plane first.** Upgrade subscribers before publishers (rule
    change messages are schema version 2), then set the same secret on both sides. Set
    `fluxgate.reload.pubsub.max-message-age=5m` if you need the old replay window.
13. **Check your configuration still starts.** Unknown `zone-id`, duplicate YAML rule ids, a
    zero window and non-positive timeouts, TTLs, intervals or sizes now fail startup.
14. **Decide where caller identity comes from.** `fluxgate.ratelimit.identity.source` now
    defaults to `PRINCIPAL`, so `X-User-Id` / `X-API-Key` are ignored and `PER_USER` /
    `PER_API_KEY` rules fall back per `missing-key-behavior` (per-IP by default) unless a
    Spring Security principal is present. If a trusted gateway authenticates callers and sets
    these headers, set `identity.source=HEADERS` or `PRINCIPAL_THEN_HEADERS`. See Breaking
    item 25.

See [docs/en/operations/migration-0.4.md](docs/en/operations/migration-0.4.md)
for the full upgrade walkthrough
([한국어](docs/ko/operations/migration-0.4.ko.md)).

[Unreleased]: https://github.com/OpenFluxGate/fluxgate/compare/v0.3.7...HEAD
