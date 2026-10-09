# Contributing to FluxGate

Thank you for your interest in contributing to FluxGate! This document covers
everything you need to set up a development environment, follow the coding
standards, and submit a change.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Review Process](#review-process)
- [Good First Issues](#good-first-issues)

## Code of Conduct

All participants must follow the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
Report violations privately using GitHub's **Security → "Report a vulnerability"** form,
which provides an encrypted channel to the maintainers.

## Getting Started

### Prerequisites

| Tool | Minimum version |
|---|---|
| Java | 11 (to build core + boot2 starter); 17 for boot3 starter; 21 for samples |
| Maven | 3.8 (use the provided `./mvnw` wrapper — do not use a system Maven) |
| Docker | 24+ (for integration tests; unit tests run without Docker) |
| Git | 2.x |

### Fork and Clone

```bash
git clone https://github.com/YOUR-USERNAME/fluxgate.git
cd fluxgate
git remote add upstream https://github.com/OpenFluxGate/fluxgate.git
```

## Development Setup

### Quick Start

From a fresh clone to a green build:

```bash
# 1. Unit tests only — needs just a JDK, no Docker
./mvnw test

# 2. Full build with integration tests — Docker must be running
#    (Testcontainers starts Redis and MongoDB for you)
./mvnw verify

# 3. Format before committing
./mvnw spotless:apply
```

You do not need to start any containers by hand for steps 1–3. The sections below are for
running against long-lived local services, the Redis Cluster tests, and the samples.

### Start Infrastructure

We provide Docker Compose files in the `docker/` directory:

| File | Services |
|---|---|
| `docker/full.yml` | Redis standalone, MongoDB, ELK — recommended |
| `docker/redis-standalone.yml` | Redis standalone only |
| `docker/redis-cluster.yml` | Redis cluster (3 nodes) on ports 7100-7105 |
| `docker/mongo.yml` | MongoDB only |

```bash
docker compose -f docker/full.yml up -d
docker compose -f docker/full.yml ps
```

### Environment Variables

Integration tests read these from the environment or fall back to Testcontainers:

```bash
export FLUXGATE_REDIS_URI=redis://localhost:6379
export FLUXGATE_MONGO_URI=mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
export FLUXGATE_MONGO_DB=fluxgate
```

### Build the Project

```bash
# Build everything, skipping tests
./mvnw install -DskipTests

# Run only unit tests (no Docker required)
./mvnw test

# Run unit + integration tests (Docker required, or the above env vars set)
./mvnw verify

# Run unit + integration tests including Redis Cluster tests
./mvnw verify -Predis-cluster-it

# Skip integration tests explicitly
./mvnw verify -DskipITs
```

The Redis Cluster profile needs a running cluster:

```bash
docker compose -f docker/redis-cluster.yml up -d
./mvnw -pl fluxgate-redis-ratelimiter -Predis-cluster-it verify
docker compose -f docker/redis-cluster.yml down
```

### Troubleshooting

| Symptom | Fix |
|---|---|
| Integration tests are skipped | Docker is not running (Testcontainers cannot start). Start Docker and re-run `./mvnw verify`. |
| `port is already allocated` on `docker compose up` | Another Redis/MongoDB is using `6379`/`27017`. Stop it, or skip the compose files and let Testcontainers pick random ports. |
| `container name "/redis-cluster-test" is already in use` | A stopped container from an earlier run exists: `docker rm redis-cluster-test`. |
| MongoDB `Authentication failed` | An old Docker volume was created with a different password. Reset it with `docker compose -f docker/mongo.yml down -v` (deletes local data). |
| A module cannot resolve a sibling `SNAPSHOT` | Build with `-am` (`./mvnw test -pl <module> -am`) so dependencies are built from source. |

## Making Changes

1. Sync with upstream: `git fetch upstream && git rebase upstream/main`
2. Create a feature branch: `git checkout -b feat/my-feature`
3. Make your changes (see standards below).
4. Run `./mvnw -q spotless:apply` to format Java code.
5. Run `./mvnw test -pl <changed-modules> -am` to verify unit tests pass.
6. Commit using [Conventional Commits](#commit-messages).
7. Push and open a pull request.

Do not commit local environment or tool files: `.env*`, `CLAUDE.md`, `AGENTS.md`, `.claude/`,
`.omc/` are gitignored. Commit messages and pull requests must not carry AI attribution trailers.

## Coding Standards

### Java Style

* **Google Java Format** is enforced by Spotless. Run `./mvnw spotless:apply`
  before every commit; CI will reject a PR that fails `spotless:check`.
* **2-space indent**; no tabs.
* **Java 11 language level** in `fluxgate-core`, `fluxgate-redis-ratelimiter`,
  `fluxgate-mongo-adapter`, `fluxgate-control-support`, and `fluxgate-spring-boot2-starter`.
  No `record`, no `switch` expressions, no text blocks (`"""`), no `var`,
  no `Stream.toList()`, no `String.formatted` in these modules.
* **Java 17 language level** in `fluxgate-spring-boot3-starter` only.
* **SLF4J logging**: `private static final Logger log = LoggerFactory.getLogger(MyClass.class);`
  Never use `System.out.println` or `java.util.logging`.
* **Javadoc** on every `public` type and method. Match the voice and style of
  neighbouring Javadoc in the same file.

### Spring Boot 2 Mirror Requirement

The boot2 (`fluxgate-spring-boot2-starter`) and boot3 (`fluxgate-spring-boot3-starter`)
starters are kept byte-identical apart from `jakarta.*` → `javax.*` imports. Any
change to the boot3 starter must be mirrored into the boot2 starter with those
import substitutions applied. The PR checklist reminds you of this.

### Commit Messages

FluxGate uses [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(redis): add multi-band Lua script for atomic consumption
fix(core): correct nanosToMillis rounding in RateLimitResponse
docs(boot3): document trusted-proxies property
test(mongo): add Testcontainers IT for rule reload
chore(deps): bump bucket4j to 8.15.0
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `ci`, `revert`.

Breaking changes: add `!` after the type (`feat!:`) and a `BREAKING CHANGE:` footer.

### CHANGELOG

Add an entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md) for every
user-visible change. Use the sections from [Keep a Changelog](https://keepachangelog.com/):
`Added`, `Changed`, `Fixed`, `Deprecated`, `Removed`, `Security`.

## Testing

### Test Tiers

| Tier | Command | Requires Docker? | File pattern |
|---|---|---|---|
| Unit | `./mvnw test` | No | `*Test.java` (not `*IntegrationTest`, not `*IT`) |
| Integration | `./mvnw verify` | Yes (or env vars) | `*IntegrationTest.java`, `*IT.java` |

Unit tests must run with no external services. Integration tests use
Testcontainers and skip themselves automatically when neither a suitable Docker
daemon nor the env-var URIs are available. Do not add `@Disabled` — use
`Assumptions.assumeTrue(dockerAvailable)` via the existing
`RedisContainerSupport` / `MongoContainerSupport` base classes.

### Coverage

New code should maintain the existing 80 % line / 70 % branch coverage thresholds.
JaCoCo reports appear in `target/site/jacoco/index.html` after `./mvnw verify`.

### Writing Tests

* Every behavioural change needs a regression test that fails _before_ your
  change and passes _after_.
* Use `assertj` fluent assertions; avoid raw JUnit `assertEquals`.
* Name tests `givenX_whenY_thenZ` or the imperative verb form used in the file.

## Submitting Changes

1. Open a PR against `main`.
2. Fill in the [pull request template](.github/PULL_REQUEST_TEMPLATE.md) fully.
3. The CI matrix runs Java 11, 17, and 21; ensure all three pass.
4. Address review comments within 14 days or the PR may be closed to keep the
   queue manageable.

## Review Process

All merged changes require at least one approval from [@rojae](https://github.com/rojae).
Reviews are best-effort; expect one to two weeks for straightforward changes.
Complex changes may take longer — open an issue first to discuss the design.

## Good First Issues

Issues labelled [`good first issue`](https://github.com/OpenFluxGate/fluxgate/labels/good%20first%20issue)
are specifically selected for new contributors. They:

* Have a clear acceptance criterion in the issue body.
* Touch a bounded area of the codebase.
* Have a test strategy outlined.

To claim one, leave a comment saying you are working on it so the maintainer can
assign it to you. If you get stuck, ask in the issue — no question is too basic.
