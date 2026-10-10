# Security Policy

## Supported Versions

Security fixes are applied to the latest released FluxGate line. Projects should upgrade to the newest published version before reporting an issue against an older release.

## Reporting a Vulnerability

Do not open a public GitHub issue for a suspected vulnerability. Report it privately through GitHub's private vulnerability reporting for this repository ("Security" tab → "Report a vulnerability"), or contact the maintainers using the security contact listed in the repository profile.

Include:

- Affected FluxGate version and modules.
- Reproduction steps or a minimal proof of concept.
- Expected impact, such as bypass, denial of service, data exposure, or privilege escalation.
- Deployment assumptions, especially proxy, Redis, MongoDB, and Spring Boot versions.

What to expect:

- **Acknowledgement** within 5 business days, with an initial severity assessment.
- **Triage and a fix plan** within 15 business days for anything rated High or Critical.
- **Disclosure** coordinated with you: a fix ships first, then a GitHub Security Advisory naming the affected versions, and a `### Security` entry in [CHANGELOG.md](CHANGELOG.md). We credit reporters who want to be credited.
- Findings that turn out to be deployment misconfiguration rather than a library defect are answered with documentation, not an advisory. We will say so explicitly rather than closing silently.

Please give us a chance to ship a fix before publishing. There is no bug bounty.

## Secure Defaults

Spring Boot auto-configuration ships hardened defaults, so a version upgrade alone adopts the safe behaviour:

| Property | Default | Why |
|----------|---------|-----|
| `fluxgate.ratelimit.failure-behavior` | `DENY` | A limiter failure (Redis down, script error) answers HTTP 503 instead of letting the request through unlimited |
| `fluxgate.ratelimit.missing-rule-behavior` | `DENY` | A request whose rule set cannot be found is rejected rather than unlimited |
| `fluxgate.ratelimit.missing-key-behavior` | `FALLBACK_TO_IP` | A missing identity limits by IP. Set `REJECT` when an anonymous caller must not inherit the authenticated tier's quota |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | Forwarded client IP headers are ignored, so a client cannot choose its own rate limit bucket |
| `fluxgate.ratelimit.trusted-proxies` | `[]` (empty) | Nothing is trusted to set a forwarding header until you say so |
| `fluxgate.ratelimit.collect-headers` | `false` | Request headers are not copied into `RequestContext`, which metrics recorders may persist |
| `fluxgate.ratelimit.header-allowlist` | `[]` (empty) | Even with collection enabled, only explicitly listed headers are copied |
| `fluxgate.ratelimit.log-query-string` | `false` | Query strings routinely carry tokens, so they stay out of the logging MDC |
| `fluxgate.redis.fail-fast` | `false` | Redis being down does not fail startup; the limiter degrades and reconnects, and `failure-behavior` decides what requests get |

Applications that intentionally need fail-open behavior can opt in with `ALLOW`. A better option for most deployments is `fluxgate.ratelimit.fallback.mode=IN_MEMORY`, which keeps limits in force per instance during a Redis outage instead of choosing between "allow everything" and "reject everything".

### Trusted proxies

`trust-client-ip-header=true` on its own is not enough. Configure the proxies that are allowed to set the header:

```yaml
fluxgate:
  ratelimit:
    trust-client-ip-header: true
    client-ip-header: X-Forwarded-For
    trusted-proxies:
      - 10.0.0.0/8          # internal load balancers
      - 2001:db8::/32       # IPv6 is supported
```

With a list configured, FluxGate walks the `X-Forwarded-For` value from the right, skipping trusted hops, and takes the first candidate that is not a trusted proxy. When the remote address is **not** in the list the header is ignored entirely and the remote address is used, because the header is then either forged or the deployment is misconfigured. Every candidate must parse as an IPv4 or IPv6 literal of at most 45 characters; hostnames are never resolved, so a header value cannot trigger a DNS lookup.

With an **empty** list and trust enabled, the **right-most** valid hop is used — it was appended by the immediate proxy and is harder to forge than the left-most client-supplied value. Nothing can be verified end-to-end, however, so configure `trusted-proxies` for any multi-hop deployment. One WARN is logged at startup. **`trust-client-ip-header=true` with an empty `trusted-proxies` should be treated as an unfinished configuration, not a supported one.**

Your proxy must also strip incoming client-supplied forwarding headers. FluxGate cannot tell a header your load balancer appended from one the client sent.

### Identity headers are client input

By default `PER_USER` reads `RequestContext.userId` and `PER_API_KEY` reads `RequestContext.apiKey`, and the servlet filter populates both **from request headers** (`X-User-Id` and `X-API-Key`). A client therefore chooses which bucket it spends: sending a different `X-User-Id` per request bypasses a per-user limit, and sending someone else's identifier exhausts their quota.

This is the correct behaviour for a gateway sitting behind an authenticating edge that rewrites those headers. In every other deployment, set the values from the authenticated principal with a `RequestContextCustomizer`, which runs last and overrides whatever the filter extracted:

```java
@Bean
public RequestContextCustomizer authenticatedIdentityCustomizer() {
  return (builder, request) -> {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
      builder.userId(auth.getName());          // never the X-User-Id header
      builder.apiKey(null);                    // drop any client supplied API key
    }
    return builder;
  };
}
```

Two further points:

- **The default `fluxgate.ratelimit.filter-order=1` already runs after Spring Security** (whose chain defaults to `-100`), so you do not need to configure anything. Only a deliberately negative value breaks this — see [Filter order trade-off](#filter-order-trade-off).
- Combine it with `fluxgate.ratelimit.missing-key-behavior=REJECT` if an unauthenticated request must not fall back to the IP-based bucket and inherit the authenticated tier's quota.

Since 0.4 you can also get this without writing a bean:

```yaml
fluxgate:
  ratelimit:
    identity:
      source: PRINCIPAL          # HEADERS | PRINCIPAL | PRINCIPAL_THEN_HEADERS
```

Unset, `source` is `PRINCIPAL` (since 0.4), with or without Spring Security, and the effective value is logged once at startup. `PRINCIPAL` ignores the identity headers entirely; a request without a principal follows `missing-key-behavior`. `HEADERS` and `PRINCIPAL_THEN_HEADERS` are an explicit opt-in for deployments whose trusted proxy sets or strips `user-id-header` / `api-key-header`, and startup logs a WARN naming those headers whenever one of them is in effect.

### Filter order trade-off

There are two defensible places to put the filter, and the choice is a real trade-off rather than a best practice:

| `filter-order` | Runs | What works | What you give up |
|----------------|------|-----------|------------------|
| `1` (default) | After Spring Security (`-100`) | `PER_USER`, `PER_API_KEY` and `CUSTOM` from the authenticated principal; `identity.source=PRINCIPAL` | Authentication runs before the limit, so a flood still pays for JWT validation or a session lookup |
| `< -100` | Before Spring Security | `PER_IP` only, as a cheap pre-auth shield that sheds load before any crypto | No principal exists yet, so a principal-based customizer and `identity.source=PRINCIPAL` silently resolve nothing — every identity scope degrades per `missing-key-behavior` |

Registering **two** filters is often the right answer: a pre-auth `PER_IP` filter with a generous limit, and a post-auth filter with the real per-user quotas. Give them different `include-patterns` and rule set ids.

`X-API-Key` is never copied into `RequestContext.getHeaders()`, even when header collection is enabled and the header is allow-listed, but it does still populate `RequestContext.getApiKey()`. `Session-Id` is likewise no longer collected — it used to be copied unconditionally.

### Key sanitisation

Resolved key values are namespaced and normalised before they become part of a bucket key:

- Each value carries a scope prefix: `ip:`, `user:`, `key:`, `custom:`, or the constant `global`. A `userId` that happens to look like `10.0.0.5` can no longer share a bucket with a real client IP.
- The value after the prefix is encoded **injectively**, so a value cannot inject **storage metacharacters** or break out of the cluster hash tag (`{` and `}` cannot escape the `{...}` tag, and `*`, `?`, `[` cannot smuggle a glob into a `SCAN` pattern), and two identities can never share a bucket because sanitising made them look alike:
  - a value of at most 256 characters from `[A-Za-z0-9._:@-]` that does not start with `h:` is kept unchanged;
  - any other value of at most 237 characters becomes `h:` + the value with every disallowed character replaced by `_` + `:` + the first 16 hex digits (a 64-bit truncated digest) of its SHA-256, so `a+1` becomes `h:a_1:<16 hex>` and no longer collides with `a_1`;
  - a longer value becomes `h:<64 hex>`, the full SHA-256, which bounds key length and Redis memory per caller.
- The scope prefix stays outside the encoding: `user:h:a_1:<16 hex>`, `user:h:<64 hex>`. The 256-character limit applies to the value; the prefix comes on top.
- Sanitising is **not idempotent** (no injective encoding can be): a value starting with `h:` is encoded again, so sanitise a raw value exactly once. `LimitScopeKeyResolver` sanitises the value and builds the key with `RateLimitKey.ofSanitized`; a custom `KeyResolver` should use `RateLimitKey.of(prefix, rawValue)` for the same shape, while `RateLimitKey.of(full)` sanitises the whole string, prefix included. Either way a custom resolver cannot bypass sanitisation.
- `allowed-keys` / `denied-keys` entries are normalised the same way; an entry already in encoded form (`user:h:...`, copied from a log) is kept as is. Only the built-in prefixes (`ip:`, `user:`, `key:`, `custom:`) are recognised, so an entry with a custom prefix (`tenant:`) whose value gets rewritten must be written in encoded form (`tenant:h:a_1:<16 hex>`).
- `:` is deliberately **allowed**, because a composite `CUSTOM` key uses it as a separator (`ip:10.0.0.1:user:u-1`). The consequence is that you should keep `:` out of your own **rule ids** and **rule set ids** — those are identifiers you choose, and keeping them colon-free keeps a bucket key unambiguously parseable.
- Key values never reach the logs in clear text: they are masked to the first four characters plus `***`, and only at DEBUG.

When you build a composite `CUSTOM` key yourself, prefix each component (`"ip:" + ip + ":user:" + userId`) so the components stay unambiguous after sanitisation.

## Redis and Tenant Isolation

Redis bucket keys use the `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>` layout. The `{...}` hash tag pins all bands of one rule and key to a single cluster slot, which is what makes multi-band consumption atomic.

In shared Redis deployments, use tenant-specific rule set IDs or include a validated tenant identifier in custom key strategies. Do not trust tenant IDs from client headers until your application has authenticated and authorized them.

Bucket deletion is scoped: `deleteAllBuckets()` matches `fluxgate:bucket:*` only and `deleteBucketsByRuleSetId(id)` uses `RedisRateLimiter.bucketKeyPattern(id)` with glob metacharacters escaped, so a reload can never delete rule definitions stored under `fluxgate:ruleset:*`. Deletion uses `SCAN` + `UNLINK`, not `KEYS` + `DEL`, so a reset does not block a single-threaded Redis for O(N).

Redis URIs are logged as `host:port[/db]` only — a URI of the form `redis://:password@host` is never logged in clear text. Both the Redis module (`RedisUriUtils.mask`) and the Spring starter go through the same masking; the starter previously had its own implementation that did not.

Redis Cluster integration tests are opt-in through the `redis-cluster-it` Maven profile so local unit tests remain deterministic. CI enables the profile when the Redis Cluster service is available.

## Rule Reload Pub/Sub Messages

The hot reload channel (`fluxgate.reload.pubsub.channel`, default `fluxgate:rule-reload`) is a **control-plane** channel. Anyone who can `PUBLISH` to it can influence the data plane, so treat write access to that Redis as equivalent to write access to your rate limit rules.

FluxGate hardens the consumer side:

- Messages carry an explicit schema `version` (currently `1`). An absent version is read as `1` for older publishers; an unknown version is rejected.
- A message that is empty, not valid JSON, not a JSON object, or carries an unknown version is logged at WARN and **ignored**. It used to be treated as a full reload, which meant one malformed message wiped every bucket — an unauthenticated global rate limit bypass window.
- Two payloads are a deliberate full reload: the literal `"*"` and a JSON object with `{"fullReload": true}`. Everything else must name a rule set.
- Reload listeners run in a defined order, cache invalidation before bucket reset, and a failed invalidation skips the reset rather than resetting buckets against a stale cache.
- `AUTO` and `PUBSUB` strategies always run a polling backstop (`fluxgate.reload.pubsub.backstop-polling-interval`, default `60s`), so a dropped message self-heals instead of leaving the data plane on stale rules indefinitely.

Publishers are authenticated with a shared HMAC-SHA256 secret, and since 0.4 Pub/Sub never runs unsigned by default: without `fluxgate.reload.pubsub.secret` the data plane's `AUTO` strategy falls back to polling with a WARN and an explicit `PUBSUB` refuses to start, and the control-support notifier refuses to start without `fluxgate.control.secret`. With a secret, every unsigned, wrongly signed or stale message — the legacy `"*"` included — is logged at WARN and ignored. `fluxgate.reload.pubsub.allow-unsigned=true` / `fluxgate.control.allow-unsigned=true` restore the unauthenticated channel for development and log a WARN at startup; never set them in production.

A signature proves who published, not that Redis is private. Protect the channel at the infrastructure layer as well:

- Require Redis AUTH and use TLS (`rediss://`) for anything crossing a network boundary.
- Restrict network reachability of Redis to the application and control plane.
- Use Redis ACLs to limit `PUBLISH` on the reload channel to the control plane's user, and consider a dedicated Redis database or instance for the control channel.
- Monitor `RuleChangeNotifierMetrics.getFailedNotifications()` — a rising count means the data plane may be running stale rules.

## Path Normalisation Limits and Proxy Responsibility

Include and exclude patterns are matched against a normalised path, not the raw URI. What FluxGate normalises:

| Vector | Handled by FluxGate |
|--------|--------------------|
| Matrix parameters (`/api/users;x=1`) | Yes — removed |
| Percent-encoded characters (`%41` → `A`) | Yes — decoded |
| Dot segments (`/api/./users`, `/api/../admin`) | Yes — removed |
| **Percent-encoded** dot segments (`%2e%2e`, `%2E%2e`) | Yes — decoded first, then removed |
| Duplicate slashes (`/api//users`) | Yes — collapsed |
| Trailing slash (`/api/users/`) | Yes — stripped |
| Context path | Yes — matching is context-path independent |
| Undecodable path (malformed `%`) | Treated as **in scope**, not passed through raw |

What FluxGate cannot fix:

- **Encoded slashes (`%2f`) and backslashes (`%5c`).** Whether `%2f` is a path separator is a servlet-container decision (`ALLOW_ENCODED_SLASH` in Tomcat, and it is off by default for good reason). FluxGate sees whatever the container decided, and any divergence between the container's mapping and the library's normalisation is a gap no amount of library code closes.
- **Ambiguity your proxy introduces.** A proxy that rewrites or re-encodes a path changes what the application sees.

So, regardless of FluxGate's normalisation: **configure your reverse proxy to reject requests whose path contains `%2e`, `%2f` or `%5c`.** That is a one-line rule in most proxies and it removes the whole class.

Also prefer `exclude-patterns` that cannot be widened by a path trick — exclude `/actuator/**` rather than relying on a prefix — and remember that `/*` matches a single segment while `/**` matches any depth.

## Weighted Requests (`cost-header`)

`fluxgate.ratelimit.cost-header` lets a request declare how many permits it costs. That is not inherently dangerous — the caller is spending its own quota — but it is dangerous on a **shared** bucket.

- **Only expose it to authenticated, trusted callers.** An unauthenticated endpoint with a cost header is a lever for amplifying a single request into a full quota.
- **Never combine it with a shared scope.** On `GLOBAL`, or on `PER_IP` behind NAT or a corporate proxy, one caller can exhaust the bucket everyone else shares with a single expensive request.
- **`max-cost` must be positive.** `0` or less used to mean "unlimited permits per request"; with `cost-header` set it is now a **startup failure**, not a silent free pass. Pick a value that reflects your most expensive real endpoint, not a round number.
- **Prefer a server-side cost.** Compute the cost per endpoint in a `RequestContextCustomizer` or a custom `FluxgateRateLimitHandler` and ignore the header. The client then cannot get it wrong or lie about it.

## Data Protection in Metrics and Events

Two paths take request data out of the request and into storage. Both are opt-in, and both need a decision from you.

**`collect-headers` feeds a persistent store.** When it is on, allow-listed headers are copied into `RequestContext`, and `RequestContext` is handed to every `RateLimitMetricsRecorder`. `MongoRateLimitMetricsRecorder` writes it to MongoDB. So a header you allow-list is a header you have chosen to **store**. `Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization` and `X-API-Key` are never copied whatever the allow list says, but anything else you list will be.

**`fluxgate.mongo.event-collection` stores one document per decision.** Each carries `clientIp`, `userId` and an API key fingerprint — personal data and credential material. Two consequences:

```yaml
fluxgate:
  mongo:
    event-collection: rate_limit_events
    event-retention: 30d        # TTL index on createdAt; 0 = no index, you manage retention
```

- **Set a retention period.** The default creates a 30-day TTL index. `0` disables the index, in which case you are responsible for expiry — and an old backup stays a breach for as long as it exists.
- **Decide whether you need the identifiers at all.** If you only want volumes and rejection rates, hash or omit `userId` and `clientIp` with a custom `RateLimitMetricsRecorder`. Rate limiting works perfectly well without a per-caller audit trail.

**Metrics cardinality is also a data decision.** `fluxgate.metrics.include-endpoint=true` produces one time series per endpoint. Normalisation (`endpoint-normalization`, on by default) replaces numeric, UUID and 24-hex segments with `{id}`, which removes most identifiers from label values. `max-endpoint-tags` (default 1000) then caps the rest across **every** FluxGate meter — counters, timers and gauges alike — and anything past the cap is tagged `endpoint="other"` rather than dropped, so a cardinality flood degrades the detail instead of the metric. A `MeterFilter` remains behind that as defence in depth for custom recorders.

A path that carries an identifier in a shape normalisation does not recognise — an email address, a slug containing a customer name — still becomes a label. Check your paths before turning `include-endpoint` on in a regulated environment.

## The In-Memory Limiter Is Not a Security Boundary

`fluxgate.ratelimit.mode=IN_MEMORY` and `fluxgate.ratelimit.fallback.mode=IN_MEMORY` are for a single instance, for development, and for riding out a Redis outage. Beyond the obvious "limits are per instance", there is a security-relevant property:

**Cache eviction resets tokens.** Buckets live in a bounded Caffeine cache (`fallback.max-buckets`, `fallback.expire-after-access`). A bucket evicted for size or idleness starts full again. Combined with a **forgeable** identity scope, that is a bypass: a caller who can rotate `X-User-Id` creates enough distinct keys to evict its own throttled bucket, then comes back with a full one.

Mitigations, in order of effectiveness:

1. Do not use an in-memory limiter as your only defence on an endpoint that matters. It is a fallback, not a design.
2. Make the identity unforgeable — `identity.source=PRINCIPAL`, or a `RequestContextCustomizer` that reads the authenticated principal.
3. Size `max-buckets` above your realistic distinct-key count, and keep `expire-after-access` at least as long as your longest band window, so a bucket cannot expire before the quota it represents does.

## Redis Bucket TTL and Long Windows

A bucket normally lives its band's window plus 10%. Removing the old 24-hour cap made long windows work correctly, but it also means a rule with a 30-day window keeps one Redis hash per distinct key alive for 30 days — and identity keys are cheap to forge.

```yaml
fluxgate:
  redis:
    max-bucket-ttl: 7d     # upper bound on any bucket's TTL
```

Seven days keeps long windows usable while bounding the memory an unauthenticated caller can pin down. The limiter warns once per rule whose window the cap shortens, so you will see it if a rule is affected.

If you raise it, raise it deliberately, and **only for scopes whose cardinality you control** — `GLOBAL` is one key, `PER_USER` with an authenticated principal is bounded by your user count, `PER_IP` on a public endpoint is unbounded.

## Actuator Exposure

The health endpoint is the most commonly over-exposed part of a Spring Boot application, and FluxGate's indicator has something worth protecting.

```yaml
management:
  endpoint:
    health:
      show-details: when_authorized     # never `always` on a reachable endpoint
      status:
        http-mapping:
          DEGRADED: 503
fluxgate:
  actuator:
    health:
      include-endpoint-details: false   # the default
```

- **`show-details: always` plus `include-endpoint-details: true` publishes reconnaissance** — internal Redis and MongoDB `host:port`, cluster node counts, and dependency failure messages. `include-endpoint-details` is `false` by default for exactly this reason; the payload still reports the status and the exception type, and the full message is logged at WARN where it belongs.
- **One gap to be aware of:** `FluxgateMongoAutoConfiguration` still masks the MongoDB URI with its own local regex rather than going through a shared masker, unlike the Redis path. Prefer supplying the Mongo URI through an environment variable so the value never appears in a committed configuration file in the first place.
- **Keep the actuator port and path off your public ingress.** `management.server.port` on a separate port is the simplest way.

## Local Development Compose Files

The files under `docker/` are labelled **local development only**. They publish every port on `127.0.0.1` and Redis runs without a password, which is acceptable only because of that loopback binding. Do not reuse them as a production topology, and do not change the bindings to `0.0.0.0` on a shared host.

## FluxGate Studio

[FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio) is a separate repository with its own security policy. It writes the rules this library enforces, so its authorization boundary is part of your rate limiting boundary. Before exposing it anywhere:

- [ ] **Run it with `SPRING_PROFILES_ACTIVE=prod`.** Without that profile, JWT audience validation is off and the Swagger UI is served — the development defaults, not a hardened configuration.
- [ ] **Decide an access policy for `/api-docs/**` and the Swagger UI.** They are `permitAll` outside the prod profile and describe every mutating endpoint.
- [ ] **Require an admin role on the mutating endpoints.** Authentication alone means every logged-in user can delete every rule.
- [ ] **Add security response headers to the UI** (`X-Frame-Options` or a `frame-ancestors` CSP, `X-Content-Type-Options`), otherwise the admin console is clickjackable.
- [ ] **Validate path segments in the BFF proxy allow list.** A prefix check is not enough: percent-encoded dot segments walk out of an allowed prefix into any backend path. Decode first, reject `..` and encoded separators, then match whole segments.
- [ ] **Bound request body size and collection sizes** on the proxy and the import endpoint.
- [ ] **Never point Studio at the same Redis database as your data plane's reload channel** without an ACL. Write access to that channel is write access to every bucket.

Findings in Studio or in its demo deployments belong in that repository's tracker, not this one — with the exception of anything that turns out to be a defect in a library SPI, which belongs here.

## Operational Signals

Monitor `/actuator/health/fluxgate` for `DEGRADED`, `dependencyIssues=true`, `failureBehavior`, and `missingRuleBehavior`. `DEGRADED` maps to HTTP 200 unless you add `management.endpoint.health.status.http-mapping.DEGRADED=503`.

Monitor `fluxgate.limiter.failures` and alert on:

| `action` tag | Meaning |
|--------------|---------|
| `fail_open` | Requests are passing through unlimited. Alert on any non-zero value in production |
| `fail_closed` | Requests are being rejected because a dependency is down. Treat as a dependency incident |
| `fallback_in_memory` | Limits are currently enforced per instance, not globally |

The packaged [`docker/prometheus/fluxgate-alerts.yml`](docker/prometheus/fluxgate-alerts.yml) contains ready-made rules for these signals.
