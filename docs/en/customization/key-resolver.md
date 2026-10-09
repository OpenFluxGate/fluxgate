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

`org.fluxgate.core.key.KeyValueSanitizer` runs on every resolved value, and again in the
`RateLimitKey` constructor so a custom resolver gets the same protection.

| Rule | Effect |
|------|--------|
| Character set | Anything outside `[A-Za-z0-9._:@-]` becomes `_` |
| Length | Longer than 256 characters → replaced by the SHA-256 hex digest (64 chars, stable across JVMs) |
| Idempotent | Sanitising an already sanitised value returns it unchanged |

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
    // Prefix every component, and let RateLimitKey sanitise the result.
    return RateLimitKey.of("tenant:" + tenant + ":" + delegate.resolve(context, rule).value());
  }
}
```

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
