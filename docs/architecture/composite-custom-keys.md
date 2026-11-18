# Opt-in composite CUSTOM identities (0.5.0 candidate)

`CompositeKeyEncoder` is a new opt-in core utility. It does not change existing
CUSTOM strings, resolvers, Spring defaults, sample identities or Redis key formats.
HMAC, secret management and automatic counter migration are outside this change.

## Use after trusted identity extraction

```java
// clientIp and userId must already be resolved/validated by the application.
// In Spring, use RequestContextFactory's ClientIpExtractor/trusted-proxy result,
// not a fresh read of X-Forwarded-For or an unverified tenant/user header.
String value = CompositeKeyEncoder.encode(
    "ip_user_v1", List.of("ip", "user"),
    Map.of("ip", clientIp, "user", userId));
RequestContext context = RequestContext.builder()
    .clientIp(clientIp).userId(userId).attribute("ipUser", value).build();
// On the rule: scope(CUSTOM), keyStrategyId("ipUser").
```

If either component is missing, do not replace it with an IP-only identity or the
literal string `null`. Validate before building `Map.of` (which itself rejects
null). Handle invalid input as an application validation failure, not as permission
to bypass the limiter. Configure `missing-key-behavior=REJECT` for a required CUSTOM
attribute; this does not validate the components of arbitrary legacy strings.
Authentication and tenant membership remain the application's responsibility.

An IP/account pair limit is not an independent per-IP limit plus a per-account
limit. If both protections are needed, retain those separate rules.

## Stable encoding contract

The output is `cs1_` followed by the 43-character unpadded Base64URL encoding of the
full SHA-256 digest: exactly 47 ASCII characters, with no `:`, `=`, `{` or `}`.
The existing sanitizer preserves it. CUSTOM still adds `custom:`. The physical
Redis key still uses `fluxgate:bucket:{escapedSet:escapedRule:custom:VALUE}:band`;
FIXED_WINDOW adds `:fw`. Structural colons are not removed. Bands of the same
rule and identity share the Cluster hash tag; different rules need not share a slot.

The SHA-256 input is this binary frame, in order:

1. One byte: format version `1`.
2. One byte: schema ASCII byte length; then the schema bytes.
3. One byte: field count.
4. For each field in the explicit list order: one byte name length, ASCII name
   bytes, four-byte unsigned big-endian UTF-8 value byte length, value bytes.

Names match `[a-z][a-z0-9_]{0,31}`. There must be 1–8 unique fields and exactly
matching map entries. Each value is nonblank, valid Unicode and at most 1024 UTF-8
bytes. Unpaired UTF-16 surrogates are rejected, not replaced with a common encoding.
The UTF-16 length is bounded before encoding to bound allocation. No component
value is included in validation errors. Do not mutate inputs concurrently.

Schema, field names, order and raw values all contribute to the digest. Map
iteration order does not. Do not silently change schema/order across clients.
There is no trimming, Unicode normalization or case folding. Colons, including
literal IPv6 colons, are preserved in the frame. Changing a separator to `_` or `+`
would not, by itself, establish unambiguous tuple boundaries.

Golden vector: schema `ip_user_v1`, order `[ip,user]`, IP `2001:db8::1`, user
`user-42` yields `cs1_h6GTG0-3fofa5DvMSIHhulszFIhldYEJi4wZlQydCis`.

## IPv6 and trust boundary

The encoder does not parse IP addresses or perform DNS lookups. Reuse the official
extractor's trusted-proxy policy before encoding. Forwarded equivalent IPv6 forms
accepted by that extractor are tested to yield the same composite identity. Do not
strip colons: that can merge different addresses. Arbitrary equivalent IP strings
passed directly to the encoder remain different values.

IPv4-mapped IPv6 versus IPv4 equivalence is not established by this utility. The
existing extractor/JDK address parser may normalize such literals before encoding;
applications must retain the same upstream extraction policy across callers. This
change does not add a new global IP canonicalization policy or rewrite remoteAddr.

## Compatibility, rollout and privacy

Opt-in means existing applications keep their exact behavior until they explicitly
set the new encoded attribute. Existing Java11/21 sample filter composition and
controller display strings are not silently migrated by this utility. If a sample
is adopted with this helper, both its filter and display path must use the same
validated inputs and encoder; display-only concatenation is not evidence of the
actual bucket identity.

Switching a live rule from a legacy composite string to `cs1_...` creates new keys
and may grant fresh quota. Existing counters and allow/deny entries do not migrate.
Do not deploy mixed old/new encoding callers against one logical quota without an
explicit transition plan. Use a maintenance/drain window or a separately reviewed
counter migration. Schema or component normalization changes have the same risk.
Legacy CUSTOM values can themselves equal a new encoded value; this is not a new
reserved namespace or an automatic isolation guarantee. Keep one explicit identity
contract per rule and update allow/deny entries to the actual resolved value.

SHA-256 has theoretical collisions and is not anonymization. Low-entropy IP/account
values can be guessed and hashed offline. Original context attributes may still be
stored by metrics/event recorders. Do not put passwords, tokens or other credentials
in this tuple. Review event retention separately. HMAC is deliberately not added.

## Verification

Core tests cover golden bytes, delimiter ambiguity, schema/order, null/blank,
malformed Unicode, 255/256/257-byte boundaries, the 1024-byte limit, sanitizer and
legacy resolver behavior. Mirrored Spring tests cover trusted forwarded IPv6 forms
and untrusted-proxy fallback. The real Cluster test covers two bands, quota
separation and explicit non-migration from a legacy identity.

Run `./mvnw spotless:check` and `./mvnw -pl fluxgate-core -am test`.
Run the existing CI Java 11/17/21 matrix and
`./mvnw -pl fluxgate-redis-ratelimiter -am -Predis-cluster-it verify` with an isolated
Cluster. A skipped integration test is not verification. No production or migration
safety claim follows from unit tests alone. Load, failover and mixed-version rollout
validation remain separate work.
