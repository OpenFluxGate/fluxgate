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
- **A rejected request no longer drains the other bands of its rule.** Across *rules* consumption is
  still not atomic: evaluation stops at the first rejecting rule, and earlier rules keep what they
  charged. Use one rule with several bands when you need strict atomicity.
- **`collect-headers` defaults to `false`**, so `RequestContext.getHeaders()` is empty unless you opt
  in and allow-list names. `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and
  `X-API-Key` are never copied.
- **Pub/Sub reload messages that cannot be parsed are ignored**, not treated as a full reload. Messages
  now carry `version: 1`, and `AUTO` / `PUBSUB` always run a 60s polling backstop.
- **MongoDB failures propagate as FluxGate exceptions** instead of looking like "no rules exist". A
  connection failure used to turn into a global allow or deny depending on `missing-rule-behavior`.
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

---

## Related

- [Changelog](../../../CHANGELOG.md) - the complete list, with the breaking section
- [Security Policy](../../../SECURITY.md) - secure defaults and the identity-header caveat
- [Key Resolver](../customization/key-resolver.md) - why key shape changes reset quotas
- [Documentation Index](../../README.md)
