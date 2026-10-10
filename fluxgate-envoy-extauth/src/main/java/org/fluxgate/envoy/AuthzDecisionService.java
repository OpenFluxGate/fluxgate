package org.fluxgate.envoy;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Delegates Envoy checks to the existing FluxGate engine and its configured rule provider. */
@Service
public final class AuthzDecisionService {
  private static final Logger log = LoggerFactory.getLogger(AuthzDecisionService.class);

  private final RateLimitEngine engine;
  private final String ruleSetId;
  private final RateLimitRuleSetProvider provider;
  private final Semaphore decisionSlots;
  private final AtomicLong admitted = new AtomicLong();
  private final AtomicLong completed = new AtomicLong();
  private final AtomicLong inflight = new AtomicLong();
  private final AtomicLong peak = new AtomicLong();
  private final AtomicLong admissionRejected = new AtomicLong();
  private final AtomicLong invalidPermits = new AtomicLong();
  private final AtomicLong unavailableSnapshot = new AtomicLong();
  private final AtomicLong waitPolicy = new AtomicLong();
  private final AtomicLong engineInfrastructure = new AtomicLong();
  private final AtomicLong runtimeException = new AtomicLong();

  /**
   * Anonymous process-lifetime counters; concurrent reads are observational, not transactional.
   * Unavailable snapshots include empty, missing, and WAIT-filtered policies. The separate WAIT
   * counter records only an engine result. Completion means the admitted service call terminated,
   * including abrupt failure, rather than that an HTTP response was delivered.
   */
  public record Diagnostics(
      long admitted,
      long completed,
      long inflight,
      long peak,
      long admissionRejected,
      long invalidPermits,
      long unavailableSnapshot,
      long waitPolicy,
      long engineInfrastructure,
      long runtimeException) {
    String json() {
      return "{\"admitted\":"
          + admitted
          + ",\"completed\":"
          + completed
          + ",\"inflight\":"
          + inflight
          + ",\"peak\":"
          + peak
          + ",\"503\":{\"admission_rejected\":"
          + admissionRejected
          + ",\"invalid_permits\":"
          + invalidPermits
          + ",\"unavailable_snapshot\":"
          + unavailableSnapshot
          + ",\"wait_policy\":"
          + waitPolicy
          + ",\"engine_infrastructure\":"
          + engineInfrastructure
          + ",\"runtime_exception\":"
          + runtimeException
          + "}}";
    }
  }

  /** Does no policy, engine, readiness, or external I/O. Completion includes abrupt failures. */
  public Diagnostics diagnostics() {
    return new Diagnostics(
        admitted.get(),
        completed.get(),
        inflight.get(),
        peak.get(),
        admissionRejected.get(),
        invalidPermits.get(),
        unavailableSnapshot.get(),
        waitPolicy.get(),
        engineInfrastructure.get(),
        runtimeException.get());
  }

  @Autowired
  public AuthzDecisionService(
      RateLimitEngine engine,
      RateLimitRuleSetProvider provider,
      @Value("${fluxgate.envoy.rule-set-id:gateway-pilot}") String ruleSetId,
      @Value("${fluxgate.envoy.max-concurrent-decisions:32}") int maxConcurrentDecisions) {
    if (maxConcurrentDecisions <= 0) {
      throw new IllegalArgumentException("maxConcurrentDecisions must be positive");
    }
    this.engine = Objects.requireNonNull(engine, "engine");
    this.provider = provider;
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId");
    this.decisionSlots = new Semaphore(maxConcurrentDecisions);
  }

  public AuthzDecisionService(
      RateLimitEngine engine, RateLimitRuleSetProvider provider, String ruleSetId) {
    this(engine, provider, ruleSetId, 32);
  }

  /** Compatibility constructor for direct embedding; production always injects the provider. */
  public AuthzDecisionService(RateLimitEngine engine, String ruleSetId) {
    this(engine, null, ruleSetId);
  }

  public boolean isReady(String selectedRuleSetId) {
    if (provider == null) {
      return false;
    }
    try {
      return readySnapshot(selectedRuleSetId).isPresent();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private Optional<RateLimitRuleSet> readySnapshot(String selectedRuleSetId) {
    return provider
        .findById(selectedRuleSetId)
        .filter(rs -> !rs.getRules().isEmpty())
        .filter(
            rs ->
                rs.getRules().stream()
                    .noneMatch(
                        rule ->
                            rule.getOnLimitExceedPolicy() == OnLimitExceedPolicy.WAIT_FOR_REFILL));
  }

  public AuthzDecision decide(RequestContext context) {
    return decide(ruleSetId, context, 1L);
  }

  public AuthzDecision decide(String selectedRuleSetId, RequestContext context, long permits) {
    // Admission never queues and precedes policy I/O, consumption, and synchronous audit work.
    if (!decisionSlots.tryAcquire()) {
      admissionRejected.incrementAndGet();
      return AuthzDecision.of(503);
    }
    admitted.incrementAndGet();
    peak.accumulateAndGet(inflight.incrementAndGet(), Math::max);
    try {
      if (permits <= 0) {
        invalidPermits.incrementAndGet();
        return AuthzDecision.of(503);
      }
      RateLimitResult result;
      if (provider == null) {
        result = engine.check(selectedRuleSetId, context, permits);
      } else {
        Optional<RateLimitRuleSet> snapshot = readySnapshot(selectedRuleSetId);
        if (snapshot.isEmpty()) {
          unavailableSnapshot.incrementAndGet();
          return AuthzDecision.of(503);
        }
        result = engine.checkUsingSnapshot(snapshot.get(), context, permits);
      }
      if (result.getPolicy() == OnLimitExceedPolicy.WAIT_FOR_REFILL) {
        waitPolicy.incrementAndGet();
        return AuthzDecision.of(503);
      }
      if (result.isAllowed()) {
        return AuthzDecision.of(200);
      }
      if (result.getDecisionReason() == RateLimitResult.DecisionReason.ACCESS_DENIED
          || result.getDecisionReason() == RateLimitResult.DecisionReason.MISSING_KEY) {
        return AuthzDecision.of(403);
      }
      if (result.getDecisionReason() == RateLimitResult.DecisionReason.QUOTA) {
        long nanos = result.getNanosToWaitForRefill();
        long seconds = nanos <= 0 ? 1 : 1 + (nanos - 1) / 1_000_000_000L;
        return new AuthzDecision(429, seconds);
      }
      engineInfrastructure.incrementAndGet();
      return AuthzDecision.of(503);
    } catch (RuntimeException e) {
      runtimeException.incrementAndGet();
      log.warn("Envoy authorization backend failed", e);
      return AuthzDecision.of(503);
    } finally {
      completed.incrementAndGet();
      inflight.decrementAndGet();
      decisionSlots.release();
    }
  }
}
