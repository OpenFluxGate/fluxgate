# Key Resolver

A `KeyResolver` turns a `RequestContext` and a rule into the **key value** — the part of the bucket
key that identifies *who* is being limited.

[< Back to Documentation Index](../../README.md) | [한국어](../../ko/customization/key-resolver.ko.md)

---

## The interface

```java
package org.fluxgate.core.key;

public interface KeyResolver {
  RateLimitKey resolve(RequestContext context, RateLimitRule rule);
}
```

Note the argument order: **context first, rule second**.

The resolver returns one key value, not a whole bucket key. The rate limiter adds the rule set id,
the rule id and the band label:

```
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
                 └ ruleSetId ┘└ ruleId ┘└─ key value ──┘  └ band label ┘
                └────────── hash tag (pins the cluster slot) ─────────┘
```

A resolver belongs to a **rule set**, not to the limiter: `RateLimitRuleSet.Builder.keyResolver(...)`
is required, and `RedisRateLimiter` / `Bucket4jRateLimiter` call `ruleSet.getKeyResolver()` once per
rule. So different rule sets can resolve keys differently in the same application.

## `LimitScopeKeyResolver`, the default

`org.fluxgate.core.key.LimitScopeKeyResolver` resolves by the rule's `LimitScope`:

| LimitScope | Key source | Resolved key value |
|------------|------------|--------------------|
| `GLOBAL` | constant | `global` |
| `PER_IP` | `context.getClientIp()` | `ip:192.168.1.100` |
| `PER_USER` | `context.getUserId()` | `user:user-123` |
| `PER_API_KEY` | `context.getApiKey()` | `key:abc123` |
| `CUSTOM` | `context.getAttributes().get(rule.getKeyStrategyId())` | `custom:<value>` |
| _(scope is null)_ | falls back to `PER_IP` | `ip:…` |

### Why the scope prefix

Without it, a `userId` that happens to be `10.0.0.5` and a real client IP of `10.0.0.5` share one
bucket — an authenticated user and an anonymous caller spending each other's quota. The prefix makes
the namespaces disjoint.

It also means **the prefix reflects the value's real source**. When a `PER_USER` rule falls back to
the client IP, the key is `ip:…`, not `user:…`. That is deliberate: the key says what was actually
measured.

### Missing values

```yaml
fluxgate:
  ratelimit:
    missing-key-behavior: FALLBACK_TO_IP   # or REJECT
```

| Behaviour | What happens when the scope's value is absent |
|-----------|----------------------------------------------|
| `FALLBACK_TO_IP` (default) | Falls back to the client IP, key prefixed `ip:`. With no IP either, `ip:unknown` |
| `REJECT` | Throws `MissingRateLimitKeyException`; the limiter catches it and returns a rejected result with no wait time |

`FALLBACK_TO_IP` has a consequence worth stating plainly: the rule's **limits still apply
unchanged**, so an anonymous caller inherits the quota configured for the authenticated tier. If
`PER_USER` grants 10 000 requests per hour to signed-in users, an unauthenticated caller gets 10 000
per hour per IP. Use `REJECT`, or a separate rule set for anonymous traffic, when that is not
acceptable.

Constructing it directly:

```java
KeyResolver resolver = new LimitScopeKeyResolver();                          // FALLBACK_TO_IP
KeyResolver strict   = new LimitScopeKeyResolver(MissingKeyBehavior.REJECT);
```

In a Spring Boot application the starter builds this bean from
`fluxgate.ratelimit.missing-key-behavior`; you do not need to.

### Logging

Resolved key values are logged at **DEBUG** only, and masked to the first four characters plus
`***`. A user id or API key never reaches the log in clear text. Fallback messages are DEBUG too —
they used to be WARN, which produced one log line per request on the hot path.

## Key value sanitisation

`org.fluxgate.core.key.KeyValueSanitizer` runs on the value part of every resolved key. The
built-in resolver keeps the scope prefix outside it (`RateLimitKey.ofSanitized`), and the
`RateLimitKey` factories run it too, so a custom resolver gets the same protection.

The encoding is **injective**: two different raw values never produce the same key, so `a+1` and
`a_1` cannot share a bucket or an allow/deny entry.

| Raw value | Sanitised value |
|-----------|-----------------|
| At most 256 characters from `[A-Za-z0-9._:@-]`, not starting with `h:` | Unchanged (`alice`, `10.0.0.1`) |
| Anything else of at most 237 characters | `h:<restricted>:<16 hex>` — `h:`, the value with every disallowed character replaced by `_`, `:` and the first 16 hex digits (a 64-bit truncated digest) of the SHA-256 of the original: `a+1` → `h:a_1:<16 hex>` |
| Longer values | `h:<64 hex>` — the full SHA-256 hex of the original (stable across JVMs) |

The scope prefix is kept in front of the encoded value: a user id `a+1` resolves to
`user:h:a_1:<16 hex>`, and a 300-character user id to `user:h:<64 hex>`. The 256-character limit
applies to the value part; the prefix comes on top.

Sanitising is **not idempotent**: no injective mapping can be, unless it is the identity, so a value
that already starts with `h:` is encoded again. Sanitise a raw value exactly once. That has two
consequences:

- **Custom resolvers pass raw values.** `RateLimitKey.of(prefix, rawValue)` keeps `prefix` and
  sanitises only `rawValue` — exactly the shape the built-in resolver produces, so
  `RateLimitKey.of("user:", userId)` matches its keys and your `user:` allow/deny entries.
  `RateLimitKey.of(full)` sanitises the **whole** string, prefix included: `RateLimitKey.of("user:a+1")`
  is `h:user:a_1:<16 hex>`, not `user:h:a_1:<16 hex>`.
- **Allow/deny entries with a built-in prefix may be written either way.** `allowed-keys` /
  `denied-keys` entries are normalised like resolved keys (`user:a+1` becomes
  `user:h:a_1:<16 hex>`, with a `WARN`). An entry that is already in encoded form —
  `user:h:a_1:<16 hex>` copied from a log or metric, or a bare `h:...` value — is kept as is, so
  normalising is idempotent. A raw value that merely looks encoded is re-encoded by the resolver, so
  such an entry only matches the identity whose encoding it is.
- **Custom prefixes need the encoded form.** Only `ip:`, `user:`, `key:` and `custom:` are
  recognised; any other entry is sanitised as a whole, because `tenant:a+1` could equally be
  `RateLimitKey.of("tenant:", "a+1")` or `RateLimitKey.of("tenant:a+1")`. For keys built with
  `RateLimitKey.of("tenant:", value)`, a raw entry matches only when the value is clean
  (`tenant:acme`). When the value gets rewritten, copy the encoded key from a log or metric
  (`tenant:h:a_1:<16 hex>`); the raw `tenant:a+1` becomes `h:tenant:a_1:<16 hex>` and never matches.

`:` is **allowed**, because composite `CUSTOM` keys use it as a separator. What sanitisation blocks
is storage metacharacters: `{` and `}` cannot escape the cluster hash tag, and `*`, `?`, `[` cannot
smuggle a glob into a `SCAN` pattern. Since `:` survives, avoid `:` inside a **rule id** or **rule
set id** — those are your own identifiers, and keeping them `:`-free keeps a bucket key
unambiguously parseable.

## Writing your own

Implement the interface and register it — as a `KeyResolver` bean in a Spring Boot application, or
directly on the rule set:

```java
public class TenantAwareKeyResolver implements KeyResolver {

  private final KeyResolver delegate = new LimitScopeKeyResolver();

  @Override
  public RateLimitKey resolve(RequestContext context, RateLimitRule rule) {
    Object tenant = context.getAttributes().get("tenantId");
    if (tenant == null) {
      return delegate.resolve(context, rule);
    }
    String tenantId = tenant.toString();
    String scoped = delegate.resolve(context, rule).value(); // e.g. "user:alice"
    // Fixed prefix outside, untrusted value sanitised; the length makes the split unambiguous.
    return RateLimitKey.of("tenant:", tenantId.length() + ":" + tenantId + ":" + scoped);
  }
}
```

Why `RateLimitKey.of(prefix, rawValue)` and not `RateLimitKey.of("tenant:" + ...)`:

- `of(String)` sanitises the **whole** string. One character outside `[A-Za-z0-9._:@-]` in a tenant id
  rewrites the prefix together with it (`h:tenant:...`), so the key no longer starts with `tenant:` and
  cannot be found by prefix in Redis, logs or metrics.
- `of(prefix, rawValue)` keeps the constant `tenant:` prefix outside and sanitises only the value,
  exactly as the built-in resolver keeps `user:` outside the user id. The 256-character cap applies to
  the value only. The prefix must be a fixed, clean name ending in `:` such as `tenant:` (it is
  rejected otherwise), so the untrusted tenant id belongs in the value, never in the prefix.
- `:` is allowed in both the tenant id and the delegate key, so `a` + `b:user:c` and `a:b` + `user:c`
  would join to the same text. Putting the tenant id's length first keeps the two apart; sanitising is
  injective, so distinct values stay distinct keys.

```java
@Bean
public KeyResolver keyResolver() {
    return new TenantAwareKeyResolver();
}
```

```java
// Or on a rule set you build yourself
RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("api-limits")
    .rules(rules)
    .keyResolver(new TenantAwareKeyResolver())
    .build();
```

Three rules for a custom resolver:

1. **Never return `null`.** The limiters require a key and will fail the request loudly if you break
   that.
2. **Prefix every component** you compose, so `ip:10.0.0.1:user:u-1` cannot be confused with a user
   id that literally contains colons.
3. **Keep it cheap and side-effect free.** It runs once per rule per request on the hot path. No
   I/O, no blocking, no logging of the raw value.

## Changing a key shape resets quotas

The key value is part of the bucket key, so changing how you resolve it moves every bucket. Callers
get a full quota once, and the old keys age out on their own TTL. That is what happened on the
0.3.x → 0.4 upgrade, when scope prefixes and sanitisation were introduced — see
[Migrating to 0.4](../operations/migration-0.4.md).

---

## Related

- [Request Context](request-context.md) - what the resolver reads
- [Engine Layer](../architecture/engine-layer.md)
- [Redis Rate Limiter](../../../fluxgate-redis-ratelimiter/README.md) - the full bucket key format
- [Security Policy](../../../SECURITY.md) - key sanitisation and tenant isolation
- [Documentation Index](../../README.md)
