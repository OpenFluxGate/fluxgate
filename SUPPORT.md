# Getting Help with FluxGate

## Documentation

The fastest route to an answer is the documentation:

| Resource | URL |
|---|---|
| README | [README.md](README.md) |
| Full property reference | [Spring Boot 3 Starter README](fluxgate-spring-boot3-starter/README.md) |
| Architecture overview | [docs/en/architecture/](docs/en/architecture/) |
| Migration guide (0.4) | [docs/en/operations/migration-0.4.md](docs/en/operations/migration-0.4.md) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |

## Community Support (GitHub Discussions / Issues)

For questions, ideas, and general usage help, open a **GitHub Discussion** or a
[question issue](https://github.com/OpenFluxGate/fluxgate/issues/new?template=question.yml).

Please include:

* FluxGate version (`implementation 'io.github.openfluxgate:fluxgate-spring-boot3-starter:X.Y.Z'`)
* Java version (`java -version`)
* Spring Boot version
* Redis version (if using the distributed limiter)
* A minimal reproducer or configuration snippet

## Security Vulnerabilities

Do **not** open a public issue. Use the private vulnerability report on GitHub:
**Security tab → "Report a vulnerability"**. See [SECURITY.md](SECURITY.md) for
the full policy.

## What This Project Does Not Provide

FluxGate is an open-source library maintained by a small team. We do not offer:

* SLAs or guaranteed response times
* Paid support contracts
* Help with deployment infrastructure (Kubernetes, cloud providers, proxies)
  beyond what is documented

We answer questions on a best-effort basis. Complex integration questions are
better suited for [Stack Overflow](https://stackoverflow.com) tagged
`rate-limiting` and `spring-boot`.

## Supported Versions

See [SECURITY.md § Supported Versions](SECURITY.md) and
[GOVERNANCE.md § Supported Versions Matrix](GOVERNANCE.md) for the Spring Boot
and Java compatibility matrix.
