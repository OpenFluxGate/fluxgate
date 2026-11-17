# FluxGate k6 Load Testing

rate-limiter test with `k6`

## Quick Start

### 1. k6 container start

```bash
cd docker/k6
docker-compose up -d
```

### 2. Run test

```bash
# Rate Limit Accuracy Test
docker exec fluxgate-k6 k6 run /scripts/rate-limit-test.js

# Stress Test
docker exec fluxgate-k6 k6 run /scripts/stress-test.js
```


## Accuracy-test preconditions

The accuracy script is a **fresh-bucket burst test**, not a general rolling-window
load test. Use an isolated development endpoint/key with an initially full bucket,
capacity `EXPECTED_LIMIT`, and a refill interval of
`WINDOW_SECONDS / EXPECTED_LIMIT` seconds per token. Do not share this key with
other traffic. The first `EXPECTED_LIMIT` requests must return 200 and the next
five must return 429 with both rate-limit headers. The burst must finish before
the first refill; otherwise the accuracy test fails rather than claim a result
for an invalid experiment. The script does not clear Redis or modify live rules.

All checks, decision accuracy, zero unexpected HTTP failures and the complete
iteration count are enforced as thresholds. 429 is explicitly an expected HTTP
response in both scripts; 5xx/network errors remain failures. The stress test
retains its separate <1% unexpected-error tolerance and is **not** a zero-error
production acceptance gate.

Run the offline verdict regression tests (Node.js, no service traffic):

```bash
node --test docker/k6/tests/verdict.test.cjs
```

These mocked tests verify verdict logic, not real k6 execution, Redis behavior,
performance or production readiness. Run the actual k6 scripts in an isolated
configured environment before relying on their measurements.

## Isolated filter/Redis verification

`tests/fixture/FilterFixture.java` connects the real Spring Boot 3
`FluxgateRateLimitFilter` to the real Redis rate limiter, using the Redis sample's
10-token/60-second rule. Its controller does not consume a second token. Compile
it against the Redis sample executable jar's extracted `BOOT-INF/classes` and
`BOOT-INF/lib/*`, then run `fixture.FilterFixture` with that classpath.

Use a new, empty **local-only Redis instance**, disable persistence, and pass
`--server.address=127.0.0.1`, a free `--server.port`, a matching
`--fluxgate.redis.uri=redis://127.0.0.1:<isolated-port>`, and
`--fluxgate.reload.enabled=false`. This fixture does not need MongoDB or rule
hot reload. Wait for `/api/status`, which does not consume benchmark tokens.
Set `BASE_URL=http://127.0.0.1:<http-port>/benchmark` for the unchanged scripts.
For native k6 rather than Docker, redirect only the `/results/` output paths in a
temporary script copy to a writable directory. Stop both local processes after
the test. Never point this fixture at an existing Redis deployment.

The Redis sample's ordinary `/api/test` is a **manual rate-limiter endpoint**, not
this filter fixture. It does not return `X-RateLimit-Remaining` on rejection and
therefore intentionally fails the accuracy script's header contract. Do not
remove that check to make the manual endpoint pass.

A reduced stress smoke test can use `VUS=5 ITERATIONS=20`; this is not the default
one-million-request stress workload or a production capacity measurement.
