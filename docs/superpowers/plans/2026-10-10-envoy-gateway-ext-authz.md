# Envoy Gateway ExtAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route a pilot HTTPRoute through a Java FluxGate authorization service backed by Redis, with observable allow, quota deny, ACL deny, and backend failure behavior.

**Architecture:** A new executable Spring Boot 3 module accepts Envoy Gateway HTTP extAuth checks. It builds a trusted request context, applies one server-owned YAML rule set through the canonical RateLimitEngine, and returns only HTTP status and safe response headers. Envoy Gateway SecurityPolicy targets the pilot HTTPRoute; Redis stores distributed buckets.

**Tech Stack:** Java 17, Spring Boot 3.3.5, FluxGate core/Boot3 starter/Redis limiter, Maven, Envoy Gateway 1.9.2, kind, Redis.

**Spec:** `docs/architecture/envoy-gateway-authz-design.ko.html`

## Global Constraints

- Work only in `feature/envoy-gateway-authz` worktree; do not modify Claude's active worktree.
- Pilot one matching rate-limit rule, one server-owned rule-set ID, one permit per request.
- ExtAuth uses HTTP: 200 allow, 403 ACL, 429 quota, 503 configuration or Redis failure; fail closed.
- Do not trust client-supplied rule-set ID, identity headers, or forwarded IP without an explicit trusted gateway boundary.
- Do not depend on the request body or forward credentials to the backend.
- No new third-party dependency beyond the existing Spring Boot and FluxGate dependencies.

## Review Focus

- Spoofed forwarded IP or identity headers must not select another client's bucket.
- Missing, empty, or multiple matching rules must never allow traffic or partially consume several rules.
- Redis outages must return 503 rather than a quota-shaped 429 or an allow.
- Every HTTP method and original path must be evaluated once, without requiring a body.
- Direct-to-backend bypass is a deployment boundary and must be documented/blocked in the local manifest where possible.

---

### Task 1: Typed decision causes

**Files:** `fluxgate-core/src/main/java/org/fluxgate/core/ratelimiter/RateLimitResult.java`, `fluxgate-core/src/main/java/org/fluxgate/core/engine/RateLimitEngine.java`, `fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/handler/ResilientRateLimiter.java`, corresponding tests.

**Interfaces:** Produce `RateLimitResult.getDecisionReason()` with `QUOTA`, `ACCESS_DENIED`, `ACCESS_BYPASS`, `MISSING_RULE_SET`, `BACKEND_FAILURE`, `UNSPECIFIED` values. Existing factories and callers remain source-compatible.

- [x] Add failing tests for ACL, missing rule set, quota, bypass, and resilient limiter failure reason.
- [x] Run targeted tests and observe failure.
- [x] Add reason field and set it at the canonical creation sites; quota is the default for rejected results carrying a matched rule.
- [x] Run targeted tests and `./mvnw -q -pl fluxgate-core,fluxgate-spring-boot3-starter -am test` successfully.

### Task 2: HTTP extAuth service

**Files:** root `pom.xml`, new `fluxgate-envoy-extauth/` POM, application, configuration, controller, policy service, YAML, and tests.

**Interfaces:** Produce `/**` HTTP check handler, `GET /healthz`, and `AuthzDecisionService.decide(RequestContext)`; consume canonical `RateLimitEngine`, provider, matcher, and typed result. Rule set and permits come from server configuration.

- [x] Add failing unit and MVC tests for 200, 403, 429 with Retry-After, 503 on missing/no/multiple matching rules and Redis failure, all-method support, and spoofed identity headers.
- [x] Run targeted tests and observe failure.
- [x] Implement the smallest controller and service; preflight requires exactly one matching rule with a nonempty band, but lets the engine decide ACL bypass/deny first.
- [x] Add executable jar packaging and YAML with a single GLOBAL rule; make gateway source and original path handling explicit. User identity and forwarded IP are untrusted in this pilot.
- [x] Run module tests and `./mvnw -q -pl fluxgate-envoy-extauth -am verify` successfully.

### Task 3: Local Kubernetes integration

**Files:** `fluxgate-envoy-extauth/Dockerfile`, `deploy/envoy-gateway-local/` manifests and guide, design doc adjustments.

**Interfaces:** Service port 8080; SecurityPolicy on one HTTPRoute; Redis Service; Gateway routing to echo backend.

- [x] Add local manifests and command guide with explicit `kind-fluxgate-eg` context and Envoy Gateway 1.9.2 pin.
- [x] Build jar and image, deploy to isolated kind cluster, verify Gateway route conditions and direct service health.
- [x] Exercise gateway with 200, 429, ACL 403, and Redis outage 503 including OPTIONS; record observed responses.
- [x] Verify changed module tests, formatting, and manifest validity; document kind's absent LoadBalancer address and NetworkPolicy enforcement.

### Task 4: Whole-branch review

**Files:** branch diff and any fixes from review.

- [x] Independently review the complete diff for security and behavior regressions.
- [x] Fix critical/important findings and rerun relevant tests.
- [x] Summarize implementation, evidence, and remaining deployment limits.
