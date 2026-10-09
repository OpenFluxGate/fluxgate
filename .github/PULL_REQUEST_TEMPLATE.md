## Summary

<!--
Explain what this PR does in two to three sentences.
Link any related issue: Closes #123
-->



## Checklist

_Complete every item that applies. Remove items that do not apply and explain why._

- [ ] **Tests**: there is a test that fails before this change and passes after, OR this is a documentation-only change.
- [ ] **Unit tests pass** locally: `./mvnw test -pl <changed-modules> -am`
- [ ] **Integration tests pass** locally (if applicable): `./mvnw verify -pl <changed-modules> -am`
- [ ] **Spotless applied**: `./mvnw -q spotless:apply`; `spotless:check` is green.
- [ ] **CHANGELOG entry**: added under `## [Unreleased]` in `CHANGELOG.md`.
- [ ] **Boot2 mirror**: if I changed `fluxgate-spring-boot3-starter`, I applied the same change to `fluxgate-spring-boot2-starter` with `jakarta.` → `javax.` import substitution.
- [ ] **Docs updated**: Javadoc, README, or `docs/` updated if the public API or configuration surface changed.
- [ ] **No version bump**: I have not changed `<version>` in any `pom.xml` (releases are cut by the maintainer).

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that changes existing behaviour)
- [ ] Documentation only
- [ ] Refactoring / code quality (no behaviour change)
- [ ] CI / build tooling

## Testing Evidence

<!--
Paste the surefire/failsafe summary from your local run, e.g.:
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
-->

```
```
