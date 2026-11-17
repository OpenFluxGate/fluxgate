# Redis-only 0.3.8 release

Only `io.github.openfluxgate:fluxgate-redis-ratelimiter:0.3.8` is published.
The public parent and core stay at 0.3.7. The owner authorized PR preparation and
`v0.3.8` tagging for this single-artifact release; the tag does not version other modules.

Push `development/0.3.8-scan` for non-secret Java 11/17/21 validation. Only a push of
exact tag `v0.3.8` can enter the publish job after the same matrix succeeds. The workflow
checks out the immutable event SHA and verifies that module code exactly matches reviewed
patch `c0993cd5`, descended from public v0.3.7. New workflow registration on main is not required
for push triggers. Do not create a `release/*` branch: the historical workflow would publish
the whole reactor with unsafe version/passphrase handling.

The publish job uses the existing protected `release` environment without changing settings.
Review/approval or branch restrictions may block the tag; do not bypass them. It uses the
approved signing fingerprint, validates POM/main/source/javadoc signatures, and deploys only
`-f fluxgate-redis-ratelimiter/pom.xml`. Never use root deploy, `-am` or versions:set.
The Central plugin auto-publishes irreversibly. Preflight requires Central404; other errors
fail closed. Post-publication checks compare all four final artifact/signature pairs byte-for-byte.
A post-publication verification failure does not undo publication. Check Central before retrying.

Module-only clean verify uses a fresh Maven cache and real standalone/cluster Redis fixtures.
Existing starter0.3.7 consumers must explicitly request Redis0.3.8; changing this artifact does
not automatically upgrade their dependency choice. Local integration and consumer evidence
is separate from GitHub matrix results. This narrow SCAN fix does not claim to resolve all
legacy dependency vulnerabilities. No scan suppression or protection relaxation is authorized.

Signed verification runs before deploy; deploy reruns verification/signing from the same
reviewed source. Final uploaded bytes are checked afterward. GPG/secret data is not uploaded
as artifacts. No GitHub release creation or other module publication is performed.

The artifact gate disassembles every shipped class and rejects Lettuce KEYS calls/raw
KEYS dispatch, checks packaged Lua for KEYS calls, and requires both built-in SCAN paths.
The public method name `keys` remains for compatibility. External applications or custom
RedisConnectionProvider implementations are outside this artifact's control.

Redis ACL users must have SCAN permission on every scanned primary. An identity permitted
only KEYS will now receive NOPERM; there is deliberately no KEYS fallback. Validate ACLs
in staging before rollout. This release does not change server permissions automatically.
