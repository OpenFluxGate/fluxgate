# Security Policy

## Supported Versions

Security fixes are applied to the latest released FluxGate line. Projects should upgrade to the newest published version before reporting an issue against an older release.

## Reporting a Vulnerability

Do not open a public GitHub issue for a suspected vulnerability. Report it privately through GitHub's private vulnerability reporting for this repository, or contact the maintainers using the security contact listed in the repository profile.

Include:

- Affected FluxGate version and modules.
- Reproduction steps or a minimal proof of concept.
- Expected impact, such as bypass, denial of service, data exposure, or privilege escalation.
- Deployment assumptions, especially proxy, Redis, MongoDB, and Spring Boot versions.

## Security Defaults

Spring Boot auto-configuration uses hardened defaults:

- `fluxgate.ratelimit.failure-behavior=DENY`
- `fluxgate.ratelimit.missing-rule-behavior=DENY`
- `fluxgate.ratelimit.trust-client-ip-header=false`

Applications that intentionally need fail-open behavior can opt in with `ALLOW`. Applications behind a trusted reverse proxy can opt in to forwarded IP extraction with `trust-client-ip-header=true` after ensuring the proxy strips untrusted incoming forwarding headers.

## Redis and Tenant Isolation

Redis bucket keys use the `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}` layout. In shared Redis deployments, use tenant-specific rule set IDs or include a validated tenant identifier in custom key strategies. Do not trust tenant IDs from client headers until your application has authenticated and authorized them.

Redis Cluster integration tests are opt-in through the `redis-cluster-it` Maven profile so local unit tests remain deterministic. CI enables the profile when the Redis Cluster service is available.

## Operational Signals

Monitor `/actuator/health/fluxgate` for `DEGRADED`, `dependencyIssues=true`, `failureBehavior`, and `missingRuleBehavior`. Monitor `fluxgate.limiter.failures` and alert on `action=fail_open` in production.
