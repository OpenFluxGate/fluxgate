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

The final 0.4 line adds a second layer of hardening that changes behaviour you may already depend on:
rate limiting being unavailable answers **503** instead of 429, MongoDB needs a unique rule index
before it starts, rule change messages move to schema version 2, invalid configuration fails startup,
and key formats are escaped injectively. Section 16 covers each one: what changes, who is affected,
what to do.

## 1. Every quota resets once

Bucket keys and the bucket hash layout both change:

| | 0.3.x | 0.4 |
|---|---|---|
| Key | `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel\|default}` | `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>` |
| Key value | `192.168.1.100` | `ip:192.168.1.100` (scope prefix, sanitised — see section 16.3) |
| Hash fields | `tokens`, `last_refill_nanos` | `tokens`, `last_refill_micros` |

Three separate changes, **one** reset: on first start every caller gets a full bucket. The `{...}`
hash tag is what pins all bands of one rule and key to a single Redis Cluster slot, which is the
precondition for the multi-band script being atomic there.

**What to do:**

- Roll out during a low-traffic window if a one-off burst matters to you.
- Old keys are never read and age out on their own TTL. To reclaim the memory sooner, list the 0.3
  keys first and delete only after checking the list. The `grep` keeps every key family 0.4 still
  uses: the buckets (`fluxgate:bucket:*`, FIXED_WINDOW counters included) and the deprecated Redis
  rule store (`fluxgate:ruleset:*` and its index `fluxgate:rulesets`).
  ```bash
  # 1. Dry run: write the keys that would be removed to a file and review it.
  redis-cli --scan --pattern 'fluxgate:*' \
    | grep -v -e '^fluxgate:bucket:' -e '^fluxgate:ruleset:' -e '^fluxgate:rulesets$' \
    > fluxgate-0.3-keys.txt
  wc -l fluxgate-0.3-keys.txt && head fluxgate-0.3-keys.txt

  # 2. Delete exactly the reviewed keys (NUL-separated, so spaces and quotes in key values are safe).
  tr '\n' '\0' < fluxgate-0.3-keys.txt | xargs -0 -r -n 100 redis-cli unlink
  ```
  Add your usual connection options (`-h`, `-p`, `--user`, `--pass`, `--tls`) to both `redis-cli`
  calls. On Redis Cluster, run both steps against each primary node and use `-n 1` in step 2: a
  multi-key `UNLINK` across slots fails with `CROSSSLOT`.
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
      # the template also renders the 503; give it its own body if clients tell them apart
      unavailable-body-template: '{"error":"Service Unavailable","retryAfter":{retryAfterSeconds}}'
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
default) answers **503** (see section 16.2), `ALLOW` passes them through unlimited. There is now a third, usually better,
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
| `ClientIpExtractor.extract(request)` and `extract(request, header, trustHeader)` are `@Deprecated` (Breaking 24) | Move to the four-argument `extract(request, header, trustHeader, TrustedProxies)`. The one-argument form trusts `X-Forwarded-For` unconditionally; the three-argument form, with no trusted proxy list, takes a hop a client can forge |
| `RedisConnectionException` constructors without a `Phase` mean `UNKNOWN`, which is **not** retried (Breaking 33) | Pass `Phase.CONNECT` from code that throws it and expects a retry (section 16.7) |
| `RateLimitRuleSet.Builder.build()` rejects two rules with one id with `InvalidRuleConfigException` (Breaking 32) | Give each rule in a rule set a unique id |
| `MongoRateLimitRuleRepository.findById(id)`, `existsById(id)`, `deleteById(id)` are `@Deprecated`; `findById(id)` and `deleteById(id)` throw `IllegalStateException` for an id present in several rule sets, `existsById(id)` returns `true` (Breaking 28) | Use the `(ruleSetId, id)` overloads, and `moveRule(id, from, to)` to move a rule (section 16.1) |
| `ResilientRateLimiter`, `EngineBackedRateLimitHandler` and `MissingRuleSetProviderRateLimitHandler` throw `RateLimiterUnavailableException` instead of returning a rejected result (Breaking 36) | Callers of these classes handle the exception (section 16.2) |

Deprecated and inert, kept for binding compatibility: `fluxgate.ratelimit.filter-enabled`,
`fluxgate.resilience.circuit-breaker.fallback`, `@RateLimit(maxConcurrentWaits = …)`,
`FluxgateConstants.Metrics.REQUESTS_TOTAL`, `LuaScripts`, `LuaScriptLoader`, `RedisRuleSetStore`,
`RuleSetData`, `org.fluxgate.redis.connection.RedisConnectionException`.

`@RateLimit(maxConcurrentWaits = …)` is ignored because the wait permits are now one `FluxgateWaitPermits` bean shared by the filter and
the aspect; use
`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` (default lowered from 100 to 50).

## 12. Behaviour changes you did not ask for but should know about

- **The hard 24-hour bucket TTL cap is gone; `fluxgate.redis.max-bucket-ttl` (default `7d`) replaces
  it.** The old cap reset a 7-day quota every 24 hours, so it effectively allowed 7× its capacity.
  FIXED_WINDOW counters now expire at their window end (`PEXPIREAT`) and are exempt from the cap.
  TOKEN_BUCKET and SLIDING_WINDOW TTLs are capped at `max-bucket-ttl`: a bucket idle for longer than
  that expires and starts full, so a window longer than the cap is effectively shortened for idle
  callers, and the limiter logs a WARN once per such rule. Raise `max-bucket-ttl` if you need longer
  windows. If you tuned a long window's capacity around the old behaviour, re-tune it.
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
  now carry `version: 2` and a nonce (section 16.4), and `AUTO` / `PUBSUB` always run a 60s polling backstop.
- **MongoDB failures propagate as FluxGate exceptions** instead of looking like "no rules exist". A
  connection failure used to turn into a global allow or deny depending on `missing-rule-behavior`.
- **`@RateLimit(maxWaitTimeMs)` can no longer exceed `wait-for-refill.max-wait-time-ms`.** The
  effective wait limit is the smaller of the two. WAIT_FOR_REFILL still blocks the request thread in
  both the filter and the aspect; FluxGate does not wait asynchronously because a Servlet `ASYNC`
  re-dispatch would skip filters registered after it. Prefer 429 with `Retry-After` and client
  backoff; non-blocking waiting belongs in a WebFlux stack.
- **A `@RateLimit` method with no HTTP request works**, and throws `RateLimitExceededException` on
  rejection (or always, with `throwOnReject = true`).
- **Rule change notifications are published after the transaction commits** (Breaking 23).
  `@NotifyRuleChange` / `@NotifyFullReload` on a method inside a `@Transactional` boundary publish
  in `afterCommit` and **do not publish at all when the transaction rolls back**. A test or an
  operational script that assumed "published as soon as the method returns" needs updating: assert
  after the commit, not after the call. A failed publish is retried three times on a separate daemon
  thread, so a Redis hiccup no longer fails the business transaction.

## 13. Health and operations

The custom `DEGRADED` status now answers **HTTP 503 by default**. FluxGate contributes
`management.endpoint.health.status.http-mapping` defaults as the lowest-precedence property source:
`down=503` and `out-of-service=503` (Spring Boot's own defaults) plus
`degraded=<fluxgate.actuator.health.degraded-http-status>`, which is `503`:

```yaml
fluxgate:
  actuator:
    health:
      degraded-http-status: 503   # 0 (or negative) adds no DEGRADED mapping; Boot then answers 200
```

Only statuses you have not mapped yourself are added, so any `http-mapping` entry or
`HttpCodeStatusMapper` bean of your own still wins. A hand-written `DEGRADED: 503` mapping is
no longer needed, and it no longer turns `DOWN` into HTTP 200 as it did in earlier 0.4 builds (a
non-empty mapping makes Spring Boot drop its own `DOWN` and `OUT_OF_SERVICE` defaults; FluxGate now
supplies them whenever you have not mapped them). If you set `degraded-http-status: 0` or disable the
health indicator, FluxGate adds no mapping and Spring Boot's rule applies again, so include `DOWN: 503`
in your own mapping.

The status also reaches the **aggregated** health now. Spring Boot's default
`management.endpoint.health.status.order` does not list `DEGRADED`, and its aggregator drops a status it
does not know, so `/actuator/health` and the `readiness` group used to report `UP` (HTTP 200) while
`/actuator/health/fluxgate` was `DEGRADED`. FluxGate contributes
`management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown` as the same
lowest-precedence default, which groups without an order of their own inherit: a degraded FluxGate now
makes the root endpoint and a readiness group that includes `fluxgate` answer **503**. An order you set
yourself - for the endpoint or a group - is kept; if it leaves out `degraded`, a WARN at startup says
so. `degraded-http-status: 0` keeps the order (`DEGRADED` aggregates but answers 200);
`fluxgate.actuator.health.enabled=false` adds neither.

A Redis Cluster that answers `PING` but reports `cluster_state` other than `ok`, failing slots, or an
unreadable node list is reported `DOWN`. The health check never creates the lazy Redis connection as a
side effect, and the payload stays free of `host:port` and failure messages unless
`fluxgate.actuator.health.include-endpoint-details=true`.

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

## 16. Hardening changes in the final 0.4 line

These changes tighten behaviour that earlier 0.4 builds and 0.3.x got wrong. They matter most if you run
MongoDB rules, use Pub/Sub reload, treat 429 and 503 differently, or construct FluxGate components by
hand. The numbers are the items of the CHANGELOG's *Breaking / Migration* list.

### 16.1 MongoDB: unique rule index and rule identity (Breaking 28, 29)

**What changes.** A rule is identified by `(ruleSetId, id)`, and the collection needs a unique index
on exactly those keys. Saving rule `r1` into rule set B used to move it out of rule set A; it now
inserts a second document, and `moveRule(id, fromRuleSetId, toRuleSetId)` is the explicit move.
`findById(id)`, `existsById(id)` and `deleteById(id)` are `@Deprecated`. `findById(id)` and
`deleteById(id)` throw `IllegalStateException` when the id exists in several rule sets;
`existsById(id)` answers yes/no and returns `true` for such an id. `saveAccessControl` throws when no
document of the rule set exists.

**Who is affected.** Everyone with `fluxgate.mongo.enabled=true`, because the default
`ddl-auto=validate` now checks for the index at startup:

| `fluxgate.mongo.ddl-auto` | Behaviour |
|---------------------------|-----------|
| `validate` (default) | Fails startup unless a unique index on `{ruleSetId: 1, id: 1}` exists. The index name does not matter, but a `sparse` index, a `partialFilterExpression` or a collation other than `simple` is rejected, because it does not keep every rule unique. The message carries the `createIndex` command and the `ddl-auto=create` hint |
| `create` | Creates the collection and calls `MongoRateLimitRuleRepository#ensureIndexes()`, which builds `ruleSetId_1_id_1_unique` (unique) and `id_1`. Duplicate `(ruleSetId, id)` pairs or a conflicting index fail startup with an `IllegalStateException` that lists the pairs — it used to log a warning and serve an ambiguous rule set |

**What to do.** Before upgrading an existing deployment, create the index on the rule collection
(`rate_limit_rules` unless you set `fluxgate.mongo.rule-collection`):

```javascript
db.rate_limit_rules.createIndex(
  { ruleSetId: 1, id: 1 },
  { unique: true, name: "ruleSetId_1_id_1_unique" }
)
```

If the command fails with a duplicate key error, two documents share a `(ruleSetId, id)` pair. List them,
delete or rename the extras, and run it again:

```javascript
db.rate_limit_rules.aggregate([
  { $group: { _id: { ruleSetId: "$ruleSetId", id: "$id" }, n: { $sum: 1 } } },
  { $match: { n: { $gt: 1 } } }
])
```

Alternatively start one instance once with `fluxgate.mongo.ddl-auto=create`, then switch back to
`validate`. The standalone samples default to `validate`; a collection first created by
`fluxgate-sample-mongo` (which uses `create`) already has the index. In code, move to the
`(ruleSetId, id)` overloads and replace "save into another rule set to move" with `moveRule`.

Two further Mongo changes need no action but explain new log lines: BSON numbers are read leniently
(`int`, `long` and `double`) while an unknown enum value is rejected, and a malformed rule document is
skipped with a WARN (and counted) instead of failing the whole rule set. Rule documents gain an
`aclUpdatedAt` field whenever their access control is written (`saveAccessControl`, a new rule,
`moveRule`); 0.3.x ignores it. Copies of the access control that diverge after an interrupted
`saveAccessControl` are merged fail-closed (deny lists united, allow lists intersected, a missing list
counting as empty, with a WARN), and documents with neither `aclUpdatedAt` nor a list - for example
rules inserted by 0.3.x - are left out of the merge. Rate limit events are now
written from a bounded queue (10000) by a daemon thread, so a slow MongoDB no longer blocks requests; a
full queue drops the event. Header and attribute names in events are escaped reversibly (`.` as `%2E`,
`$` as `%24`, NUL as `%00`, `%` as `%25`, an empty name as `%`) instead of `.` and `$` becoming `_`, so a
query over such a field uses the escaped name.

### 16.2 503 instead of 429 when rate limiting is unavailable (Breaking 36)

**What changes.** A 429 now means exactly one thing: a limit was exceeded.

| Situation | Status |
|-----------|--------|
| Limit exceeded | 429, `Retry-After` |
| Limiter failure with `failure-behavior=DENY` (Redis down, retries exhausted, circuit open) | **503**; `Retry-After` only when the wait is known, for example while the circuit breaker is open |
| No rule set or provider with `missing-rule-behavior=DENY` | **503** |
| `failure-behavior=ALLOW` / `missing-rule-behavior=ALLOW` | request passes, except for a rule set that cannot be built |
| A rule set that cannot be built (`InvalidRuleConfigException`), under **any** `failure-behavior` | **503**. Before 0.4 `failure-behavior=ALLOW` let these requests through; it now covers limiter failures only |
| `fallback.mode=IN_MEMORY` during an outage | per-instance limits, 429 when exceeded |
| A `cost-header` value (after `max-cost`) above the capacity of a matching band | **429** without `Retry-After`; the problem document names the cost and the capacity. A client error: never counted against the circuit breaker, never answered by the fallback or `failure-behavior` |

**Who is affected.** Clients and gateways that retry or alert on 429 only, dashboards counting 4xx as
client errors, and any code calling `ResilientRateLimiter`, `EngineBackedRateLimitHandler` or
`MissingRuleSetProviderRateLimitHandler` directly: they throw `RateLimiterUnavailableException` where
they used to return a rejected result. An outage is now visible as 5xx, which is what monitoring
expects.

**What to do.** Make clients retry 503 with backoff, move the "rate limiter is down" alert to 5xx, and
keep 429 handling for real throttling. A custom `RateLimitResponseWriter` inherits a default
`writeUnavailable(request, response, retryAfterMillis)`; override it to change the 503 body, or set
`response.unavailable-body-template`. It also inherits `writeCostExceeded(request, response, permits,
capacity)` for an oversized cost. A `@RateLimit` method called outside an HTTP handler gets
`RateLimitExceededException` whose `isServiceUnavailable()` tells the two cases apart; in a servlet
application FluxGate's default exception handler answers it with 429 or 503 (section 16.6).

An oversized request cost used to be treated as a limiter failure: it raised an invalid-rule error inside
the resilient limiter, so a client sending large `cost-header` values could open the circuit breaker
for everyone - every request a 503 under `failure-behavior=DENY`, no limits at all under `ALLOW`. Such a
cost is now rejected before the breaker with 429. If you would rather clamp than reject, keep `max-cost`
at or below the smallest band capacity of the rules the header applies to. The aspect's
`@RateLimit(permits)` is fixed in code, so a value above the capacity is a configuration error there
and answered with 503.

### 16.3 Key formats are injective (Breaking 30, 31)

**What changes.** Two different identities can no longer land in one bucket or one allow/deny entry.

| Input | 0.4 key value |
|-------|---------------|
| User id `alice` (only `[A-Za-z0-9._:@-]`) | `user:alice` (unchanged) |
| User id `a+1` | `user:h:a_1:<16 hex>` — marker, restricted value, first 16 hex digits of the SHA-256 of the original |
| A value that needs rewriting and is longer than 237 characters, or any value over 256 characters | `user:h:<64 hex>` — the scope prefix stays outside the hash |

Redis bucket key segments are percent-escaped instead of replaced by `_`: in a `ruleSetId` or `ruleId`
the characters `:` `{` `}` `*` `?` `[` `]` `\`, whitespace and `%` become `%XX` (UTF-8), and `:` and `%`
in a band label do too. `a:b`, `a_b` and `a b` therefore get different buckets, and a band label `x:fw`
can no longer write to the `FIXED_WINDOW` counter of a band labelled `x`.

**Who is affected.** Only callers whose key value contained a character outside `[A-Za-z0-9._:@-]` or
exceeded 256 characters, and only rule sets, rules or band labels using the escaped characters. Their
buckets restart full once (on top of the section 1 reset when coming from 0.3.x). Sanitising is no
longer idempotent: never sanitise a value twice.

**What to do.** Update external tooling that builds or scans keys (use `RedisRateLimiter.BUCKET_KEY_PREFIX`
and `bucketKeyPattern(ruleSetId)`). Configured `allowed-keys` / `denied-keys` are normalised with the
same rules, and a WARN names every entry that changed, so a deny entry such as `user:a+1` keeps matching
its caller; an entry already in encoded form (`user:h:a_1:<16 hex>`, copied from a log) is kept as is.
Only `ip:`, `user:`, `key:` and `custom:` are recognised: an entry with a custom prefix such as `tenant:`
whose value gets rewritten must be written in encoded form (`tenant:h:a_1:<16 hex>`). A
custom `KeyResolver` that builds prefixed keys should call `RateLimitKey.of(prefix, rawValue)`, which
sanitises only the value like the built-in resolver; `RateLimitKey.of(key)` sanitises the whole string. A
rule set containing two rules with one id is rejected with `InvalidRuleConfigException`
(they shared buckets and metrics), and `AccessControl` equality now includes the IP lists, so a reload
that only changes allowed or denied IPs is applied.

### 16.4 Pub/Sub rule change messages: schema version 2 and a 60 second window (Breaking 34, 35)

**What changes.** Rule change messages carry `version: 2` and a random `nonce`. The signature covers a
length-prefixed canonical form that binds the **channel** and the nonce. The subscriber remembers
accepted nonces for the replay window and ignores a replayed message, a message signed for another
channel, and a version 2 message without a nonce. Version 1 and unsigned messages keep their previous
rules. `fluxgate.reload.pubsub.max-message-age` now defaults to `60s` (the property said `5m`, the
strategy class said `60s`).

**Who is affected.** Everyone running Pub/Sub reload with `fluxgate-control-support` (or any publisher
built on `RuleChangeMessage`).

**What to do — roll out in this order:**

1. Upgrade every **data plane** instance (subscribers) first. They understand versions 1 and 2.
2. Then upgrade the **control plane** (publishers), which starts sending version 2.
3. Use the same secret on both sides (section 15). A secret is trimmed on both sides and a blank one
   means "none"; one shorter than 32 bytes is reported at WARN.

Doing it the other way round is not destructive but slow: a data plane that only knows version 1 drops
a version 2 message as an unknown schema version, so rule changes wait for the polling backstop
(`backstop-polling-interval`, 60s). The window is a clock comparison between publisher and subscriber:
keep their clocks within a few seconds, or set `max-message-age: 5m` explicitly to keep the old
tolerance. The window only applies when a secret is set. If you changed the channel on one side, set
`fluxgate.control.redis.channel` and `fluxgate.reload.pubsub.channel` to the same value: a message signed
for another channel is ignored.

Signed version 1 messages from a not yet upgraded publisher are still accepted during the rollout
(`fluxgate.reload.pubsub.accept-legacy-signed`, default `true`); the first one accepted is logged at
WARN. Version 1 binds neither the channel nor a nonce, so once every control plane publishes version 2
set `fluxgate.reload.pubsub.accept-legacy-signed=false` and the data plane ignores version 1 messages.

Messages are also handled on a single `fluxgate-pubsub-listener` thread, in arrival order, off the
Lettuce event loop. At most 10000 messages wait; beyond that further messages are dropped with a WARN and
the backstop polling picks the change up.

### 16.5 Invalid configuration fails startup (Breaking 38)

`FluxgateProperties` validates itself while binding — no Bean Validation provider is needed — and the
YAML rule sets are built eagerly at startup. These used to be accepted and misbehave at runtime:

| Configuration | Rule |
|---------------|------|
| `redis.timeout-ms`, `redis.max-bucket-ttl` | must be positive |
| `ratelimit.fallback.max-buckets`, `fallback.expire-after-access` | must be positive |
| `ratelimit.wait-for-refill.max-wait-time-ms` | must not be negative; `max-concurrent-waits` must be positive |
| `reload.cache.ttl`, `cache.max-size`, `polling.interval`, `pubsub.retry-interval`, `pubsub.max-message-age` | must be positive |
| `reload.cache.negative-ttl`, `polling.initial-delay`, `pubsub.backstop-polling-interval` | zero allowed ("disabled"), not negative |
| YAML band `zone-id` | an unknown id fails (it fell back to UTC silently and moved every calendar boundary) |
| YAML rule `id`, rule set `id` | a rule id used twice in one rule set, a duplicate rule set id, or a missing id fails |
| YAML band `window`, `capacity` | missing, zero or negative fails |

The message names the property, for example `fluxgate.redis.timeout-ms must be > 0 (got 0)`. Start the
application once in a test environment to find offenders. A WARN is also logged when enabled
`PER_API_KEY` YAML rules exist but `identity.source` never reads the API key header (the rule would
apply to nobody). See the [YAML rule sets guide](../guides/yaml-rule-sets.md) for the schema.

### 16.6 Hand-built components and framework wiring (Breaking 32, 37, 39, 40)

- **Legacy constructors are secure by default (37).** `new FluxgateRateLimitFilter(handler, ruleSetId,
  include, exclude)` and its 7- and 8-argument forms, and `new RateLimitAspect(handler, customizer)`,
  trusted `X-Forwarded-For` and failed open. They now ignore forwarding headers and fail closed (a
  limiter failure is then a 503, section 16.2). Use the constructors taking `clientIpHeader`,
  `trustClientIpHeader` and `failOpenOnError` to opt back in.
- **`@RateLimit` outside an MVC handler throws (39).** The 429 is written only when the intercepted
  method is a Spring MVC handler (`@RequestMapping` or a shortcut, also on an implemented interface)
  whose return type is not a primitive. A service method, a scheduled task or a message listener gets
  `RateLimitExceededException` — the old code wrote a 429 to the current response and returned `null`
  to a caller that never expected one, and failed with `AopInvocationException` on primitives. In a
  servlet application `RateLimitExceededExceptionHandler`, a `@RestControllerAdvice` registered
  whenever the aspect is active, answers the exception with **429** and `Retry-After`, or **503** when
  `isServiceUnavailable()` is true, through the configured `RateLimitResponseWriter` (it used to
  surface as HTTP 500). It is ordered at `RateLimitExceededExceptionHandler.ORDER`
  (`Ordered.HIGHEST_PRECEDENCE + 1000`), so an unordered catch-all
  `@ExceptionHandler(Exception.class)` in your own advice no longer turns rejections into 500. To use
  your own format, handle the exception in an advice ordered before it
  (`@Order(RateLimitExceededExceptionHandler.ORDER - 1)`; an unordered advice never sees the
  exception), define a `RateLimitExceededExceptionHandler` bean, or catch it.
- **FluxGate's MongoDB client is not a `MongoClient` bean (40).** It lives in `FluxgateMongoClientHolder`
  and the auto-configuration runs after Spring Boot's `MongoAutoConfiguration`. Previously Spring Data
  MongoDB could end up writing application data to the FluxGate cluster, or the `MongoClient` injection
  became ambiguous and the Boot 3 context failed. If you injected FluxGate's client, inject
  `FluxgateMongoClientHolder` or `fluxgateMongoDatabase` instead. A `MongoClient` bean you named
  `fluxgateMongoClient` is still used by FluxGate and is not closed by it.
- **Duplicate rule ids and `AccessControl` (32)** — see section 16.3.
- **Forwarded headers (behaviour change).** With `trust-client-ip-header=true`, all header lines are
  read and the hops are walked from the right; `ip:port`, `[v6]` and `[v6]:port` are reduced to the
  address, and a hop that is not an IP literal falls back to the remote address instead of being used.
- **Waiting.** The filter and the aspect share one set of wait permits (`FluxgateWaitPermits`, not a
  `Semaphore` bean), so `max-concurrent-waits` bounds both.
  Setting `wait-for-refill.enabled=false` explicitly now also stops `@RateLimit(waitForRefill = true)`.

### 16.7 Redis connection failures carry a phase (Breaking 33)

`org.fluxgate.core.exception.RedisConnectionException` has a `Phase`, and the retry policy retries only
the first one:

| Phase | When | Retried |
|-------|------|---------|
| `CONNECT` | Establishing the standalone or cluster connection, before any command was sent | Yes (`fluxgate.resilience.retry.*`) |
| `COMMAND` | A Lettuce failure while `EVALSHA` / `EVAL` runs (consume, check, refund), or while a reset scans and unlinks keys | No — Redis may already have executed the command, and retrying a consume could charge one request twice |
| `UNKNOWN` | Constructors without a phase | No |

Timeouts are reported as `FluxgateTimeoutException` and governed by `retry.retry-on-timeout`. In
practice a Redis outage hits `CONNECT` and is retried, while a connection that drops mid-request gives
the request one attempt and then, under `failure-behavior=DENY`, a 503 (section 16.2). If you throw
`RedisConnectionException` from a custom store or wrapper and want it retried, use the constructor that
takes `Phase.CONNECT`. Reset failures used to surface as raw Lettuce exceptions; they are now
`RedisConnectionException(COMMAND)` or `FluxgateTimeoutException`.

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
- [ ] Check that load balancer and Kubernetes probes on `/actuator/health` and `/actuator/health/readiness` treat `DEGRADED` as you intend: it now aggregates there and answers 503 by default (section 13)
- [ ] Switch CI from `./mvnw test` to `./mvnw verify` for integration coverage
- [ ] Recompile and fix the exception and removed-method changes in section 11
- [ ] Set `identity.source=HEADERS` or `PRINCIPAL_THEN_HEADERS` if a trusted proxy supplies identity headers (section 15)
- [ ] Set the same `fluxgate.reload.pubsub.secret` / `fluxgate.control.secret` on data and control planes if you use Pub/Sub reload (section 15)
- [ ] Create the unique `{ruleSetId: 1, id: 1}` MongoDB index before starting with `ddl-auto=validate` (section 16.1)
- [ ] Treat 503 as "rate limiting unavailable" in clients, gateways and alerts; 429 now means only "limit exceeded" (section 16.2)
- [ ] Update tooling and `allowed-keys` / `denied-keys` entries that depend on key shapes (section 16.3)
- [ ] Upgrade data planes before control planes for Pub/Sub reload, and decide on `max-message-age` (section 16.4)
- [ ] Start the application once in staging to surface configuration that now fails validation (section 16.5)
- [ ] Review hand-built filters and aspects, `@RateLimit` on services, and `MongoClient` injections (section 16.6)
- [ ] Pass `Phase.CONNECT` from custom code that throws `RedisConnectionException` and expects a retry (section 16.7)

---

## Related

- [Changelog](../../../CHANGELOG.md) - the complete list, with the breaking section
- [Security Policy](../../../SECURITY.md) - secure defaults and the identity-header caveat
- [Key Resolver](../customization/key-resolver.md) - why key shape changes reset quotas
- [YAML rule sets guide](../guides/yaml-rule-sets.md) - the `fluxgate.ratelimit.rule-sets` schema
- [Documentation Index](../../README.md)
