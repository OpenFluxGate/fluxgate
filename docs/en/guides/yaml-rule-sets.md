# YAML Rule Sets Guide

Define rate limit rules in `application.yml` under `fluxgate.ratelimit.rule-sets` instead of storing
them in MongoDB. A rule can match by path, HTTP method and header, use one of three algorithms, follow
a calendar quota, and sit behind an allow/deny list. Everything is validated when the application
starts.

[< Back to Documentation Index](../../README.md) | [한국어](../../ko/guides/yaml-rule-sets.ko.md)

## Contents

- [A first rule set](#a-first-rule-set)
- [How YAML rule sets are used](#how-yaml-rule-sets-are-used)
- [Rule set keys](#rule-set-keys)
- [Rule keys](#rule-keys)
- [Matcher](#matcher)
- [Band keys and algorithms](#band-keys-and-algorithms)
- [Calendar quotas](#calendar-quotas-quota-period-and-zone-id)
- [Access control](#access-control)
- [Complete example](#complete-example)
- [Startup validation](#startup-validation)
- [Related](#related)

---

## A first rule set

```yaml
fluxgate:
  ratelimit:
    default-rule-set-id: api-limits
    rule-sets:
      - id: api-limits
        description: Default API limits
        rules:
          - id: per-ip-100rpm
            scope: PER_IP
            matcher:
              path-patterns: [/api/**]
            bands:
              - capacity: 100
                window: 60s
```

With `@EnableFluxgateFilter` and `fluxgate.ratelimit.mode=IN_MEMORY` (or Redis) that is the whole setup:
no MongoDB, no handler class. The starter registers `PropertiesRuleSetProvider`, which builds every rule
set eagerly at startup.

## How YAML rule sets are used

- A request selects a rule set by id: `fluxgate.ratelimit.default-rule-set-id`, the
  `@EnableFluxgateFilter` attribute, or `@RateLimit(ruleSetId = ...)`.
- YAML rule sets are looked up **first**. When MongoDB is enabled (or your own
  `delegateRuleSetProvider` bean exists), an id that YAML does not define falls back to it, and hot reload
  caches both sources. A YAML rule set wins over a MongoDB rule set with the same id.
- YAML rule sets are static: they change with a restart, not with hot reload. Use MongoDB when rules must
  change at runtime.
- The filter's `include-patterns` / `exclude-patterns` decide whether FluxGate looks at a request at all;
  the rule's `matcher` then decides which rules of the rule set apply to it.
- Within a rule set the matching, enabled rules are evaluated from the highest `priority` down, ties
  broken by rule id; all of them must have tokens for the request to pass. A request that matches no rule
  passes without a limit and without a remaining-tokens header.

## Rule set keys

`fluxgate.ratelimit.rule-sets[n]`

| Key | Default | Description |
|-----|---------|-------------|
| `id` | required | Unique rule set id. A missing or duplicate id fails startup |
| `description` | _(none)_ | Human-readable text |
| `access-control` | empty | Allow/deny lists evaluated before any limiter, see [Access control](#access-control) |
| `rules` | `[]` | The rules of this rule set |

## Rule keys

`fluxgate.ratelimit.rule-sets[n].rules[m]`

| Key | Default | Description |
|-----|---------|-------------|
| `id` | required | Rule id, unique **within the rule set**. A missing or duplicate id fails startup |
| `name` | the `id` | Display name |
| `enabled` | `true` | A disabled rule is never evaluated |
| `priority` | `0` | Higher values are evaluated first; ties are broken by rule id ascending |
| `scope` | `PER_IP` | `GLOBAL`, `PER_IP`, `PER_USER`, `PER_API_KEY` or `CUSTOM` — which bucket a request maps to |
| `key-strategy-id` | `ip` | For `CUSTOM`, the `RequestContext` attribute that carries the key |
| `on-limit-exceed-policy` | `REJECT_REQUEST` | `REJECT_REQUEST` or `WAIT_FOR_REFILL` (see `fluxgate.ratelimit.wait-for-refill.*`) |
| `attributes` | `{}` | Free-form map passed through to the rule |
| `matcher` | match all | See [Matcher](#matcher) |
| `bands` | `[]` | The limits, see [Band keys and algorithms](#band-keys-and-algorithms) |

Identity scopes follow `fluxgate.ratelimit.identity.source` (default `PRINCIPAL`): with `PER_USER` or
`PER_API_KEY` and no authenticated principal, `missing-key-behavior` decides. A WARN is logged at startup
when enabled `PER_API_KEY` rules exist but the identity source never reads the API key header.

## Matcher

`fluxgate.ratelimit.rule-sets[n].rules[m].matcher`

All constraints are ANDed. An empty or absent constraint means "match any". Exclusion beats inclusion.

| Key | Type | Description |
|-----|------|-------------|
| `methods` | list | HTTP methods, e.g. `[GET, POST]`. Upper-cased with `Locale.ROOT`. Empty: any method |
| `path-patterns` | list | Ant-style include patterns, e.g. `[/api/**, /v2/**]`. Empty: any path |
| `exclude-path-patterns` | list | Ant-style exclude patterns, e.g. `[/api/health]`. A path matching one is never selected, even when it also matches an include pattern |
| `header-equals` | map | Header name to exact expected value, e.g. `x-tier: premium` |
| `header-present` | list | Header names that must be present in the request |

Header names are case-insensitive. Patterns follow Ant rules: `?` one character, `*` within a segment,
`**` across segments, and `**/` only at a segment boundary.

```yaml
matcher:
  methods: [POST, PUT]
  path-patterns: [/api/**]
  exclude-path-patterns: [/api/health, /api/metrics]
  header-equals:
    x-tier: premium
  header-present: [x-request-id]
```

## Band keys and algorithms

`fluxgate.ratelimit.rule-sets[n].rules[m].bands[k]`

| Key | Default | Description |
|-----|---------|-------------|
| `capacity` | required | Requests (tokens) allowed in the window. Must be positive |
| `window` | required | Window length: `60s`, `1m`, `PT1H`, `30d`. Must be positive |
| `algorithm` | `TOKEN_BUCKET` | `TOKEN_BUCKET`, `SLIDING_WINDOW` or `FIXED_WINDOW` |
| `quota-period` | _(none)_ | `DAILY`, `WEEKLY` or `MONTHLY`. Only valid with `FIXED_WINDOW` |
| `zone-id` | `UTC` | IANA zone for calendar alignment, e.g. `Asia/Seoul`. An unknown id fails startup |
| `sliding-window-buckets` | `10` | Sub-buckets of a `SLIDING_WINDOW`; must be within 2 to 60 |
| `label` | _(derived)_ | Display label for metrics and the admin UI. When set it also becomes the storage bucket key segment |

| Algorithm | Behaviour |
|-----------|-----------|
| `TOKEN_BUCKET` | Tokens refill continuously at `capacity / window`; allows bursts up to `capacity`. The default, and the only algorithm before 0.4 |
| `SLIDING_WINDOW` | The window is split into `sliding-window-buckets` sub-buckets and only the most recent full window counts: smoother than a fixed window, bounded memory |
| `FIXED_WINDOW` | A tumbling counter. With `quota-period` the counter resets at a calendar boundary |

A band without a label derives one from its configuration, which is also its Redis key segment:
`100-per-60s` (token bucket), `100-per-60s-sw` (sliding window), `100-per-60s-fw` (fixed window) and
`1000-per-30d-monthly` (calendar quota). Several bands in one rule must each have a distinct label; two
that derive the same one are rejected. Renaming a label moves that band's bucket, which resets it once.

```yaml
bands:
  - capacity: 10          # burst protection
    window: 1s
  - capacity: 1000        # sustained rate
    window: 1h
    algorithm: SLIDING_WINDOW
    sliding-window-buckets: 12
```

## Calendar quotas: `quota-period` and `zone-id`

`FIXED_WINDOW` with a `quota-period` resets at midnight (`DAILY`), Monday midnight (`WEEKLY`) or the first
of the month at midnight (`MONTHLY`) in the band's `zone-id`:

```yaml
bands:
  - capacity: 10000
    window: 30d
    algorithm: FIXED_WINDOW
    quota-period: MONTHLY
    zone-id: Asia/Seoul
```

`window` is still required and is part of the derived label (`10000-per-30d-monthly`). Always set `zone-id`
when your users are not in UTC: an unknown id used to fall back to UTC silently and shift every boundary,
and now fails startup. A window longer than 24 hours is no longer reset every 24 hours; the bucket TTL is
bounded by `fluxgate.redis.max-bucket-ttl` (default `7d`), and the limiter warns once per rule whose window
the cap shortens (a `FIXED_WINDOW` counter is exempt from that warning).

## Access control

`fluxgate.ratelimit.rule-sets[n].access-control`

Evaluated before any limiter. **Deny beats allow**; when neither list matches, normal rate limiting applies.

| Key | Effect |
|-----|--------|
| `denied-ips` | CIDRs rejected immediately (no limiter is consulted; the filter answers 429 with no wait time) |
| `denied-keys` | Resolved key values rejected immediately |
| `allowed-ips` | CIDRs that **bypass** rate limiting entirely |
| `allowed-keys` | Resolved key values that bypass rate limiting |

IP lists use CIDR syntax (IPv4 and IPv6) and are checked against the request's client IP for every scope,
so a denied IP is blocked even for `PER_USER` rules. Key lists compare against the **resolved** key, scope
prefix included: `user:alice`, `key:internal-service`, `ip:192.0.2.1`. Configured keys go through the same
sanitiser as request values, so `user:a+1` still matches the identity `a+1`; a WARN names every entry that
changed. An unparseable CIDR fails startup.

```yaml
access-control:
  denied-ips: [203.0.113.0/24]
  allowed-ips: [10.0.0.0/8]
  allowed-keys: [key:internal-service]
  denied-keys: [user:abuser]
```

## Complete example

```yaml
fluxgate:
  ratelimit:
    default-rule-set-id: public-api
    rule-sets:
      - id: public-api
        description: Public API
        access-control:
          denied-ips: [203.0.113.0/24]
          allowed-keys: [key:internal-service]
        rules:
          - id: premium-writes
            priority: 20
            scope: PER_API_KEY
            on-limit-exceed-policy: REJECT_REQUEST
            matcher:
              methods: [POST, PUT, DELETE]
              path-patterns: [/api/**]
              exclude-path-patterns: [/api/health]
              header-equals:
                x-tier: premium
            bands:
              - capacity: 600
                window: 1m
          - id: anonymous-reads
            priority: 10
            scope: PER_IP
            matcher:
              methods: [GET]
              path-patterns: [/api/**]
            bands:
              - capacity: 30
                window: 1s
                label: burst
              - capacity: 5000
                window: 1h
                algorithm: SLIDING_WINDOW
                sliding-window-buckets: 12
          - id: monthly-export-quota
            scope: PER_USER
            matcher:
              path-patterns: [/api/export/**]
            bands:
              - capacity: 10000
                window: 30d
                algorithm: FIXED_WINDOW
                quota-period: MONTHLY
                zone-id: Asia/Seoul
```

## Startup validation

`PropertiesRuleSetProvider` builds and validates every rule set while the application starts; a mistake
fails the boot with a message naming the rule set, rule or property instead of misbehaving later:

| Mistake | Result |
|---------|--------|
| Rule set without `id`, or the same rule set id twice | startup fails |
| Rule without `id`, or the same rule id twice in one rule set | startup fails |
| Band without `window`, or `window` zero or negative | startup fails |
| Band `capacity` zero or negative | startup fails |
| Unknown `zone-id` | startup fails (no silent UTC fallback) |
| `quota-period` with an algorithm other than `FIXED_WINDOW` | startup fails |
| `sliding-window-buckets` outside 2 to 60 with `SLIDING_WINDOW` | startup fails |
| Two bands of one rule that derive the same label | startup fails |
| Unparseable CIDR in `access-control` | startup fails |
| Enabled `PER_API_KEY` rules while `identity.source` never reads the API key header | WARN |

## Related

- [Migrating to 0.4](../operations/migration-0.4.md) - startup validation and key format changes
- [@RateLimit Annotation Guide](annotation.md) - method-level limits that use these rule sets
- [Key Resolver](../customization/key-resolver.md) - how the key of each scope is built
- [Main README](../../../README.md) - the configuration reference
