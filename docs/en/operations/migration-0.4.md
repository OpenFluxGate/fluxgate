# Migrating to 0.4

What changes when you upgrade FluxGate from 0.3.x, in the order you will hit it.

[< Back to Documentation Index](../../README.md) | [한국어](../../ko/operations/migration-0.4.ko.md)

For the complete list of changes see [CHANGELOG.md](../../../CHANGELOG.md).

---

## In one paragraph

The upgrade is source-compatible for almost everyone, and **operationally** it has one visible
effect: every rate limit quota resets once, because bucket keys change shape. The rest is a list of
things that now work which previously did not — filter properties that were never read, a circuit
breaker that was never wired, standard rate limit headers that were documented but absent — plus a
429 body that is now RFC 9457 problem JSON and one Micrometer meter that is gone.

A few **security defaults** also tighten, and those can change who gets limited how: identity
headers are no longer trusted unless you opt in, and Redis Pub/Sub rule reload only runs with a signing
secret. Section 15 lists each one and the property that
restores the 0.3 behaviour.

## 1. Every quota resets once

Bucket keys and the bucket hash layout both change:

| | 0.3.x | 0.4 |
|---|---|---|
| Key | `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel\|default}` | `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>` |
| Key value | `192.168.1.100` | `ip:192.168.1.100` (scope prefix, sanitised) |
| Hash fields | `tokens`, `last_refill_nanos` | `tokens`, `last_refill_micros` |

Three separate changes, **one** reset: on first start every caller gets a full bucket. The `{...}`
hash tag is what pins all bands of one rule and key to a single Redis Cluster slot, which is the
precondition for the multi-band script being atomic there.

**What to do:**

- Roll out during a low-traffic window if a one-off burst matters to you.
- Old keys are never read and age out on their own TTL. To reclaim the memory sooner:
  ```bash
  redis-cli --scan --pattern 'fluxgate:*' | grep -v '^fluxgate:bucket:' | xargs -r redis-cli unlink
  ```
  Check the output before running it — do not delete `fluxgate:ruleset:*` if you still use the
  deprecated Redis rule store.
- **FIXED_WINDOW counters** (new in 0.4) are hashes `{count, window_end_micros}` under
  `<bucket key>:fw`. If you ran a pre-release 0.4 build with FIXED_WINDOW bands, its plain string
  counters under the bare key are no longer read; each expires at the end of its own window, so that
  window's count restarts once. Nothing needs deleting.
- **Update any external tooling** that reads or builds FluxGate keys or bucket hashes. Values in the
  hash are now plain digits rather than `1.76e+18`. Build keys with
  `RedisRateLimiter.BUCKET_KEY_PREFIX` and `bucketKeyPattern(ruleSetId)`, never by string
  concatenation.

## 2. `include-patterns` now matches everything by default

The old default was `{"/*"}`, which matches a **single** path segment — so an application that never
set the property was, in practice, rate limiting nothing below the first segment. The default is now
all paths.

```yaml
fluxgate:
  ratelimit:
    include-patterns:
      - /api/**        # set this explicitly to keep a narrow surface
```

Related: matching now runs against the **normalized** path (URL-decoded, `;`-parameters removed, `//`
collapsed, trailing slash stripped, context path independent), so requests that previously slipped
past an exclude pattern no longer do.

## 3. Properties that were silently ignored now take effect

These were documented but never read by library code:

`include-patterns`, `exclude-patterns`, `filter-order`, `include-headers`, `client-ip-header`,
`trust-client-ip-header`, `missing-rule-behavior`, `metrics.include-endpoint`

**Read your configuration file as if for the first time.** If you set `trust-client-ip-header: true`
years ago and it did nothing, it does something now. If you set `missing-rule-behavior: ALLOW` and
relied on the code's fail-closed behaviour anyway, the property wins now.

Properties also take precedence over the matching `@EnableFluxgateFilter` attribute — which is what
the annotation's Javadoc always promised. If you set both, the property wins.

## 4. Set `trusted-proxies` if you trust a forwarding header

```yaml
fluxgate:
  ratelimit:
    trust-client-ip-header: true
    trusted-proxies:
      - 10.0.0.0/8
      - 2001:db8::/32
```

With a list configured, FluxGate walks `X-Forwarded-For` from the right, skipping trusted hops.
With an **empty** list and trust enabled, the **right-most** valid hop is used and startup logs one
WARN — it was appended by the immediate proxy and is harder to forge than the left-most
client-supplied value, but configure `trusted-proxies` for any multi-hop deployment.

## 5. The 429 body changed

```
0.3.x:  {"error":"Too Many Requests","retryAfter":30}
        Content-Type: application/json

0.4:    {"type":"about:blank","title":"Too Many Requests","status":429,
         "detail":"Rate limit exceeded, retry after 30 seconds","retryAfterMillis":29340}
        Content-Type: application/problem+json;charset=UTF-8
```

Clients that parsed the old keys will break. Two ways back:

```yaml
fluxgate:
  ratelimit:
    response:
      content-type: application/json
      body-template: '{"error":"Too Many Requests","retryAfter":{retryAfterSeconds}}'
```

Or take the body over completely:

```java
@Bean
public RateLimitResponseWriter rateLimitResponseWriter() {
    return (request, response, result) -> {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limited\"}");
    };
}
```

## 6. Response headers you did not have before

`X-RateLimit-Limit` and `X-RateLimit-Reset` were documented in 0.3.x but never written — only
`X-RateLimit-Remaining` and `Retry-After` were. Both are now written, alongside the IETF family:

```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800     # epoch seconds
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 27               # delta seconds
RateLimit-Policy: 100;w=60
Retry-After: 27
```

Turn either family off with `fluxgate.ratelimit.response.include-legacy-headers` /
`include-standard-headers`, or both with `fluxgate.ratelimit.include-headers=false`. A value the
limiter reports as unknown (`-1`) is omitted rather than written as `-1`.

`Retry-After` is now rounded **up** with a floor of 1 second. A sub-second delay used to truncate to
`Retry-After: 0`, which sent clients into a busy loop.

## 7. `fluxgate.requests.total` is gone

It exported as `fluxgate_requests_total` under Micrometer's Prometheus naming and collided with
`fluxgate.requests`, which carries a `result` tag. Sum over the tag instead:

```promql
# before
rate(fluxgate_requests_total[5m])

# after
sum(rate(fluxgate_requests_total{result=~"allowed|rejected"}[5m]))
```

The packaged dashboard and alert rules under `docker/` already filter on `result`, so they need no
change.

Also new: `endpoint` tag values are normalized (`/api/users/42` → `/api/users/{id}`) and capped by
`fluxgate.metrics.max-endpoint-tags` (default 1000). If your dashboards matched literal paths with
ids in them, they will now match `{id}`.

## 8. You can probably delete your handler

If your `FluxgateRateLimitHandler` existed only to adapt Redis to the filter, delete it. With a
`RateLimiter` (Redis or in-memory) and a `RateLimitRuleSetProvider` on the context, the starter
registers `EngineBackedRateLimitHandler` for you:

```java
// 0.3.x
@EnableFluxgateFilter(handler = MyRedisRateLimitHandler.class)

// 0.4
@EnableFluxgateFilter
```

Your own bean still wins if you keep it. Keep it when it does something the library does not — call a
central service over HTTP, apply business rules, consult a second store.

The starter resolves the rule set provider by looking for a bean named `delegateRuleSetProvider`
first, and falls back to a unique `RateLimitRuleSetProvider` bean when that name is absent. A single
provider bean under any name therefore works.

## 9. Review what happens when Redis is down

Redis being unreachable **no longer fails application startup**. The limiter connects lazily and
reconnects in the background with exponential backoff; request threads are never blocked waiting for
a reconnect. Restore the old eager behaviour with:

```yaml
fluxgate:
  redis:
    fail-fast: true
```

While Redis is down, `fluxgate.ratelimit.failure-behavior` decides what requests get — `DENY` (the
default) answers 429, `ALLOW` passes them through unlimited. There is now a third, usually better,
option:

```yaml
fluxgate:
  ratelimit:
    fallback:
      mode: IN_MEMORY        # keep per-instance limits during the outage
      max-buckets: 100000
      expire-after-access: 1h
```

Watch `fluxgate.limiter.failures` with `action=fallback_in_memory` to know when that is happening.

Retry and the circuit breaker are also live now (`fluxgate.resilience.*`); in 0.3.x they were complete
code wired to nothing. Note that `circuit-breaker.failure-threshold` is now an `Integer`, unset by
default, and applied only when you set it: unset, the breaker uses a sliding-window failure rate
(`sliding-window-size` 20, `failure-rate-threshold` 50%, `minimum-number-of-calls` 10).

## 10. Development without Redis

New, and useful for local profiles and tests:

```yaml
fluxgate:
  redis:
    enabled: false
  ratelimit:
    mode: IN_MEMORY          # or AUTO, which falls back to in-memory when Redis is off
```

Limits then apply **per instance**, which is correct for one process and wrong for a cluster. The
choice is logged at INFO on startup. For tests, `fluxgate-testkit` packages the same wiring behind
`FluxgateInMemoryExtension`.

## 11. Source-level changes to recompile against

| Change | What to do |
|--------|------------|
| `RateLimitRuleSet.build()` / `RateLimitRule.build()` throw `InvalidRuleConfigException` | Update `catch` blocks that expected `IllegalArgumentException` / `IllegalStateException` |
| `RateLimitRule.build()` rejects two bands with the same derived key label | Give the bands distinct labels, or distinct `(capacity, window)` pairs |
| `permits > band capacity` throws `InvalidRuleConfigException` | Validate cost against capacity before calling |
| Redis connection failures throw `org.fluxgate.core.exception.RedisConnectionException` | Existing `catch` blocks still compile: the module-local type now extends it |
| `RedisRateLimiterConfig` constructors no longer declare `throws IOException` | Remove a `catch (IOException)` around the constructor if you have one |
| `CircuitBreaker.execute(...)` always throws `CircuitBreakerOpenException` while open, and nothing returns `null` | Use `executeWithFallback(operation, action, fallback)` |
| Removed: `BucketState.getRetryAfterSeconds()`, `RedisTokenBucketStore.close()`, `DefaultCircuitBreaker.handleOpenState()` | See the replacements in the CHANGELOG |
| `fluxgate.ratelimit.filter-order` is an `Integer`, unset by default | Handle `null` if you read the property directly |
| `RateLimitResult.allowedWithoutRule()` reports `remainingTokens = -1` | Treat `-1` as "unknown" and omit the header, do not print `-1` |
| Rejected results carry the **real** remaining tokens | Code that asserted `0` on rejection needs updating |

Deprecated and inert, kept for binding compatibility: `fluxgate.ratelimit.filter-enabled`,
`fluxgate.resilience.circuit-breaker.fallback`, `@RateLimit(maxConcurrentWaits = …)`,
`FluxgateConstants.Metrics.REQUESTS_TOTAL`, `LuaScripts`, `LuaScriptLoader`, `RedisRuleSetStore`,
`RuleSetData`, `org.fluxgate.redis.connection.RedisConnectionException`.

`@RateLimit(maxConcurrentWaits = …)` is ignored because the wait semaphore is now aspect-wide; use
`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` (default lowered from 100 to 50).

## 12. Behaviour changes you did not ask for but should know about

- **Windows longer than 24 hours are enforced for their full length.** The old TTL cap reset a 7-day
  quota every 24 hours, so it effectively allowed 7× its capacity. If you configured a long window
  and tuned the capacity around the broken behaviour, re-tune it.
- **Two unlabelled bands of one rule no longer collide.** A band with no explicit label keys itself as
  `<capacity>-per-<windowSeconds>s`, so a rule that appeared to enforce only one of its two bands now
  enforces both. Sub-second windows use `-per-<millis>ms`.
- **Renaming a band label moves that band's bucket**, resetting it once.
- **A rejected request no longer drains the other bands of its rule, nor earlier rules.** On a
  standalone Redis, or when all keys of the matching rules hash to one cluster slot, every rule is
  evaluated in one Lua call, all-or-nothing. Otherwise (a cluster, rules in different slots) rules are
  charged one by one and the rules already charged are refunded when a later one rejects. That
  compensation is not atomic: for one round trip per rule, concurrent requests see the earlier rule a
  permit lower, and a refund that cannot run (process death, Redis failure in between) leaves the
  permit spent. Use one rule with several bands when you need strict atomicity on a cluster. The
  in-memory limiter (`mode=IN_MEMORY`, and the `IN_MEMORY` fallback) is all-or-nothing across rules
  without any of these caveats.
- **`collect-headers` defaults to `false`**, so `RequestContext.getHeaders()` is empty unless you opt
  in and allow-list names. `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and
  `X-API-Key` are never copied.
- **Pub/Sub reload messages that cannot be parsed are ignored**, not treated as a full reload. Messages
  now carry `version: 1`, and `AUTO` / `PUBSUB` always run a 60s polling backstop.
- **MongoDB failures propagate as FluxGate exceptions** instead of looking like "no rules exist". A
  connection failure used to turn into a global allow or deny depending on `missing-rule-behavior`.
- **`@RateLimit(maxWaitTimeMs)` can no longer exceed `wait-for-refill.max-wait-time-ms`.** The
  effective wait limit is the smaller of the two. WAIT_FOR_REFILL still blocks the request thread in
  both the filter and the aspect; FluxGate does not wait asynchronously because a Servlet `ASYNC`
  re-dispatch would skip filters registered after it. Prefer 429 with `Retry-After` and client
  backoff; non-blocking waiting belongs in a WebFlux stack.
- **A `@RateLimit` method with no HTTP request works**, and throws `RateLimitExceededException` on
  rejection (or always, with `throwOnReject = true`).

## 13. Health and operations

Map the custom `DEGRADED` status if a load balancer probes health — Spring Boot maps it to HTTP 200
by default:

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          DEGRADED: 503
```

The health indicator now probes for real filter and aspect beans instead of reporting the
`filter-enabled` property, so `filterEnabled: false` while the filter was running is fixed.

`fluxgate.limiter.failures` tag values to alert on:

| `action` | Meaning |
|----------|---------|
| `fail_open` | Requests are passing through unlimited — alert on any non-zero value |
| `fail_closed` | Requests are rejected because a dependency is down |
| `fallback_in_memory` | Limits are enforced per instance, not globally |

Ready-made assets: [`docker/grafana/fluxgate-dashboard.json`](../../../docker/grafana/fluxgate-dashboard.json)
and [`docker/prometheus/fluxgate-alerts.yml`](../../../docker/prometheus/fluxgate-alerts.yml).

## 14. Test commands changed

```bash
./mvnw test              # unit tests only - no Docker, no Redis, no MongoDB
./mvnw verify            # unit + integration tests (failsafe, *IntegrationTest / *IT)
./mvnw verify -DskipITs  # skip the integration tier explicitly
```

`./mvnw test` no longer runs the integration tests. They use Testcontainers, and **skip** rather than
fail when neither `FLUXGATE_REDIS_URI` / `FLUXGATE_MONGO_URI` nor a Docker daemon is available. They
also no longer run `flushdb()` or drop collections they did not create, so pointing them at a shared
development server is safe.

If you renamed FluxGate's integration tests in a fork, note that
`RedisRateLimiterTest` → `RedisRateLimiterIntegrationTest` and
`RedisTokenBucketStoreTest` → `RedisTokenBucketStoreIntegrationTest`.

## 15. Secure defaults that now need an explicit opt-in

### Identity headers are ignored unless you enable them

`PER_USER` and `PER_API_KEY` used to read `X-User-Id` and `X-API-Key` whenever there was no
authenticated principal. Those headers are client input, so an anonymous caller could pick — or
rotate — the bucket it spent. In 0.4 `fluxgate.ratelimit.identity.source` defaults to `PRINCIPAL`,
with or without Spring Security:

| Request | 0.3 / early 0.4 snapshots | 0.4 default (`PRINCIPAL`) |
|---------|---------------------------|---------------------------|
| Authenticated | header (0.3), principal (snapshots) | principal name |
| Unauthenticated, `X-User-Id` / `X-API-Key` sent | header value | **no identity** → `missing-key-behavior` |
| No Spring Security on the classpath | header value | **no identity** → `missing-key-behavior` (startup WARN) |

A request without an identity follows `fluxgate.ratelimit.missing-key-behavior`: `FALLBACK_TO_IP`
(default) limits it per IP, `REJECT` answers 429. The API key is never taken from the principal, so a
`PER_API_KEY` rule needs either header opt-in or a `RequestContextCustomizer` that sets it from your
own verified credential.

If a trusted proxy or gateway sets or strips these headers, opt back in explicitly:

```yaml
fluxgate:
  ratelimit:
    identity:
      source: PRINCIPAL_THEN_HEADERS   # or HEADERS
      user-id-header: X-User-Id        # the header your proxy sets
      api-key-header: X-API-Key
```

Startup logs a WARN naming the headers whenever `HEADERS` or `PRINCIPAL_THEN_HEADERS` is in effect.
The `userId` / `apiKey` MDC entries now carry the identity the limiter resolved — the principal name
by default — and the raw identity headers appear in logs only when header identity is enabled. Log
queries that keyed on `userId` from `X-User-Id` for anonymous traffic will see it empty.
The convenience constructors of `RequestContextFactory`, `FluxgateRateLimitFilter` and
`RateLimitAspect` that take no `IdentitySource` follow the same `PRINCIPAL` default.

### Pub/Sub rule reload needs a signing secret

A rule reload message tells every data plane instance to reload rules and reset buckets. Unsigned,
`PUBLISH fluxgate:rule-reload '*'` from anyone who can reach Redis drops every token bucket. In 0.4
signing is required on both sides:

| Side | Required | Without it |
|------|----------|------------|
| Data plane, `AUTO` (default) with `fluxgate.redis.enabled=true` | `fluxgate.reload.pubsub.secret` | WARN, falls back to `POLLING` — rule changes arrive within `fluxgate.reload.polling.interval` (30s) instead of immediately |
| Data plane, explicit `fluxgate.reload.strategy=PUBSUB` | `fluxgate.reload.pubsub.secret` | startup fails (`IllegalStateException`) |
| Control plane with `fluxgate-control-support` (only active when `fluxgate.control.redis.uri` is set) | `fluxgate.control.secret` | startup fails (`IllegalStateException`) |

```yaml
# data plane
fluxgate:
  reload:
    pubsub:
      secret: ${FLUXGATE_RELOAD_SECRET}
---
# control plane
fluxgate:
  control:
    secret: ${FLUXGATE_RELOAD_SECRET}   # the same value
```

Roll the secret out to the control plane and every data plane in the same deployment: a data plane
with a secret ignores unsigned messages, so a control plane still publishing unsigned is silently
ignored until the polling backstop (`backstop-polling-interval`, 60s) catches up.

If your data planes run `AUTO` and you do nothing, they keep working on polling; watch for the WARN
and add the secret to get push-based reload back. Alternatives: `fluxgate.reload.strategy=POLLING`
needs no channel at all. For local development only,
`fluxgate.reload.pubsub.allow-unsigned=true` and `fluxgate.control.allow-unsigned=true` restore the
unauthenticated channel and log a WARN at startup; they are ignored once a secret is set.

## Upgrade checklist

- [ ] Plan the one-off quota reset; pick a low-traffic window
- [ ] Update external tooling that reads FluxGate keys or bucket hashes
- [ ] Set `include-patterns` explicitly if you relied on the old `/*` default
- [ ] Re-read your `fluxgate.*` configuration: properties that were ignored now apply
- [ ] Set `trusted-proxies` if `trust-client-ip-header=true`
- [ ] Update clients that parse the 429 body, or set `response.body-template`
- [ ] Update dashboards using `fluxgate_requests_total` without a `result` tag
- [ ] Delete a handler that only adapted Redis
- [ ] Decide between `failure-behavior` and `fallback.mode=IN_MEMORY` for a Redis outage
- [ ] Add `management.endpoint.health.status.http-mapping.DEGRADED=503`
- [ ] Switch CI from `./mvnw test` to `./mvnw verify` for integration coverage
- [ ] Recompile and fix the exception and removed-method changes in section 11
- [ ] Set `identity.source=HEADERS` or `PRINCIPAL_THEN_HEADERS` if a trusted proxy supplies identity headers (section 15)
- [ ] Set the same `fluxgate.reload.pubsub.secret` / `fluxgate.control.secret` on data and control planes if you use Pub/Sub reload (section 15)

---

## Related

- [Changelog](../../../CHANGELOG.md) - the complete list, with the breaking section
- [Security Policy](../../../SECURITY.md) - secure defaults and the identity-header caveat
- [Key Resolver](../customization/key-resolver.md) - why key shape changes reset quotas
- [Documentation Index](../../README.md)
