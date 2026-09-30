package org.fluxgate.spring.aop;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.filter.ProblemDetailRateLimitResponseWriter;
import org.fluxgate.spring.filter.RateLimitDurationRecorder;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.fluxgate.spring.util.RequestPathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Aspect that applies rate limiting to methods annotated with {@link RateLimit}.
 *
 * <p>This aspect intercepts method calls and checks rate limits before execution. If the rate limit
 * is exceeded it writes a 429 Too Many Requests response, or throws {@link
 * RateLimitExceededException} when there is no servlet response to write to or the annotation asks
 * for it via {@link RateLimit#throwOnReject()}.
 *
 * <p>The intercepted method is invoked <b>exactly once</b> and always outside the rate limiter's
 * try/catch, so a business exception is never mistaken for a rate limiter failure and never causes
 * the method to run twice.
 *
 * <p>The request context is built by the same {@link RequestContextFactory} the filter uses, so
 * {@code PER_USER}, {@code PER_API_KEY} and {@code CUSTOM} scopes resolve identically in both
 * modes.
 *
 * @see RateLimit
 * @see FluxgateRateLimitHandler
 */
@Aspect
public class RateLimitAspect {

  private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

  /** Method marker used in place of an HTTP method for invocations without a request. */
  private static final String INVOCATION_METHOD = "INTERNAL";

  private static final long SINGLE_PERMIT = 1L;

  private final FluxgateRateLimitHandler handler;
  private final String defaultRuleSetId;
  private final boolean failOpenOnError;
  private final boolean denyWhenRuleMissing;
  private final boolean waitForRefillEnabled;

  /**
   * Bounds how many threads may be parked waiting for a refill.
   *
   * <p>C1: this used to be created per invocation, so {@code tryAcquire()} always succeeded and
   * every worker thread could sleep. One aspect-wide semaphore is the only version of this that
   * limits anything.
   */
  private final Semaphore waitSemaphore;

  private final RequestContextFactory contextFactory;
  private final RateLimitHeaderWriter headerWriter;
  private final RateLimitResponseWriter responseWriter;
  private final RateLimitDurationRecorder durationRecorder;

  /**
   * Creates an aspect with legacy defaults.
   *
   * @param handler the rate limit handler
   * @param contextCustomizer optional request context customizer
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      @Autowired(required = false) RequestContextCustomizer contextCustomizer) {
    this(handler, contextCustomizer, Headers.X_FORWARDED_FOR, true, true, "", false);
  }

  /**
   * Creates an aspect with security-sensitive settings.
   *
   * @param handler the rate limit handler
   * @param contextCustomizer optional request context customizer
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param defaultRuleSetId rule set used when the annotation does not name one
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      @Autowired(required = false) RequestContextCustomizer contextCustomizer,
      String clientIpHeader,
      boolean trustClientIpHeader,
      boolean failOpenOnError,
      String defaultRuleSetId) {
    this(
        handler,
        contextCustomizer,
        clientIpHeader,
        trustClientIpHeader,
        failOpenOnError,
        defaultRuleSetId,
        false);
  }

  /**
   * Creates an aspect with security-sensitive settings.
   *
   * @param handler the rate limit handler
   * @param contextCustomizer optional request context customizer
   * @param clientIpHeader forwarding header to inspect when trusted
   * @param trustClientIpHeader whether forwarding headers are trusted
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param defaultRuleSetId rule set used when the annotation does not name one
   * @param denyWhenRuleMissing reject when no rule set is configured at all
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      @Autowired(required = false) RequestContextCustomizer contextCustomizer,
      String clientIpHeader,
      boolean trustClientIpHeader,
      boolean failOpenOnError,
      String defaultRuleSetId,
      boolean denyWhenRuleMissing) {
    this(
        handler,
        defaultRuleSetId,
        failOpenOnError,
        denyWhenRuleMissing,
        false,
        50,
        new RequestContextFactory(
            clientIpHeader, trustClientIpHeader, null, false, null, contextCustomizer),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  /**
   * Creates a fully configured aspect. This is the constructor the auto-configuration uses; the
   * others delegate to it with legacy defaults.
   *
   * @param handler the rate limit handler (required)
   * @param defaultRuleSetId rule set used when the annotation does not name one (nullable)
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param denyWhenRuleMissing reject when no rule set is configured at all
   * @param waitForRefillEnabled honour a rule's WAIT_FOR_REFILL policy
   * @param maxConcurrentWaits permits of the aspect-wide wait semaphore
   * @param contextFactory factory building the {@link RequestContext} (required)
   * @param headerWriter writer for rate limit headers (required)
   * @param responseWriter writer for the 429 body (required)
   * @param durationRecorder optional recorder for the invocation duration timer (nullable)
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      String defaultRuleSetId,
      boolean failOpenOnError,
      boolean denyWhenRuleMissing,
      boolean waitForRefillEnabled,
      int maxConcurrentWaits,
      RequestContextFactory contextFactory,
      RateLimitHeaderWriter headerWriter,
      RateLimitResponseWriter responseWriter,
      RateLimitDurationRecorder durationRecorder) {
    this.handler = handler;
    this.defaultRuleSetId = defaultRuleSetId;
    this.failOpenOnError = failOpenOnError;
    this.denyWhenRuleMissing = denyWhenRuleMissing;
    this.waitForRefillEnabled = waitForRefillEnabled;
    this.waitSemaphore = new Semaphore(Math.max(1, maxConcurrentWaits));
    this.contextFactory = contextFactory;
    this.headerWriter = headerWriter;
    this.responseWriter = responseWriter;
    this.durationRecorder = durationRecorder;
  }

  /**
   * Intercepts methods annotated with {@link RateLimit}.
   *
   * @param joinPoint the join point
   * @param rateLimit the rate limit annotation
   * @return the method result if allowed, null if rate limited and the response was written
   * @throws Throwable if the method throws an exception
   */
  @Around("@annotation(rateLimit)")
  public Object aroundMethod(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    return doRateLimit(joinPoint, rateLimit);
  }

  /**
   * Intercepts all public methods in classes annotated with {@link RateLimit}.
   *
   * @param joinPoint the join point
   * @param rateLimit the rate limit annotation on the class
   * @return the method result if allowed, null if rate limited and the response was written
   * @throws Throwable if the method throws an exception
   */
  @Around("@within(rateLimit) && !@annotation(org.fluxgate.spring.annotation.RateLimit)")
  public Object aroundClass(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    return doRateLimit(joinPoint, rateLimit);
  }

  private Object doRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    HttpServletRequest request = getCurrentRequest();
    HttpServletResponse response = getCurrentResponse();
    long startTimeMs = System.currentTimeMillis();

    String ruleSetId =
        StringUtils.hasText(rateLimit.ruleSetId()) ? rateLimit.ruleSetId() : defaultRuleSetId;
    if (!StringUtils.hasText(ruleSetId)) {
      if (denyWhenRuleMissing) {
        log.warn("No ruleSetId specified, rejecting invocation");
        return reject(request, response, RateLimitResponse.rejected(0), rateLimit);
      }
      log.warn("No ruleSetId specified, skipping rate limiting");
      return joinPoint.proceed();
    }

    String endpoint = endpointOf(joinPoint, request);
    RequestContext context =
        request != null
            ? contextFactory.create(request, endpoint)
            : contextFactory.createForInvocation(endpoint, INVOCATION_METHOD);

    Decision decision = decide(context, ruleSetId, rateLimit);

    // C2: the target method runs exactly once, outside the rate limiter's try/catch.
    try {
      if (decision.allowed) {
        headerWriter.write(response, decision.result);
        return joinPoint.proceed();
      }
      return reject(request, response, decision.result, rateLimit);
    } finally {
      recordDuration(ruleSetId, endpoint, request, startTimeMs);
    }
  }

  /** Runs the rate limiter and decides whether the invocation may proceed. */
  private Decision decide(RequestContext context, String ruleSetId, RateLimit rateLimit) {
    long permits = Math.max(SINGLE_PERMIT, rateLimit.permits());
    try {
      RateLimitResponse result = tryConsume(context, ruleSetId, permits);

      if (!result.isAllowed() && shouldWaitForRefill(rateLimit, result)) {
        result = waitForRefill(context, ruleSetId, permits, rateLimit, result);
      }

      if (result.isAllowed()) {
        return Decision.allowed(result);
      }
      return Decision.rejected(result);
    } catch (Exception e) {
      if (failOpenOnError) {
        log.error("Error during rate limiting, allowing invocation", e);
        return Decision.allowed(null);
      }
      log.error("Error during rate limiting, rejecting invocation", e);
      return Decision.rejected(RateLimitResponse.rejected(0));
    }
  }

  /**
   * Whether this rejection should wait for a refill instead of failing immediately.
   *
   * <p>H11: the rule's own {@code OnLimitExceedPolicy} decides, which is what the filter does.
   * {@code @RateLimit(waitForRefill = true)} is an explicit per-method override and always wins;
   * otherwise the rule is followed, gated by the global {@code
   * fluxgate.ratelimit.wait-for-refill.enabled} switch.
   */
  private boolean shouldWaitForRefill(RateLimit rateLimit, RateLimitResponse result) {
    if (rateLimit.waitForRefill()) {
      return true;
    }
    return waitForRefillEnabled && result.shouldWaitForRefill();
  }

  private RateLimitResponse waitForRefill(
      RequestContext context,
      String ruleSetId,
      long permits,
      RateLimit rateLimit,
      RateLimitResponse result) {

    long waitTimeMs = result.getRetryAfterMillis();
    long maxWaitTimeMs = rateLimit.maxWaitTimeMs();
    if (waitTimeMs > maxWaitTimeMs) {
      log.debug("Wait time {} ms exceeds max {} ms, rejecting", waitTimeMs, maxWaitTimeMs);
      return result;
    }

    if (!waitSemaphore.tryAcquire()) {
      log.debug("Too many concurrent waits, rejecting");
      return result;
    }

    try {
      log.debug("Waiting {} ms for token refill", waitTimeMs);
      TimeUnit.MILLISECONDS.sleep(Math.max(0L, waitTimeMs));

      RateLimitResponse retryResult = tryConsume(context, ruleSetId, permits);
      if (!retryResult.isAllowed()) {
        log.debug("Invocation still rate limited after wait");
      }
      return retryResult;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Wait for token refill interrupted");
      return result;
    } finally {
      waitSemaphore.release();
    }
  }

  private RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    // The 3-arg form is optional for handlers, so only use it for a weighted invocation.
    return permits == SINGLE_PERMIT
        ? handler.tryConsume(context, ruleSetId)
        : handler.tryConsume(context, ruleSetId, permits);
  }

  /**
   * Renders the rejection.
   *
   * @return always null, so the intercepted method's caller sees no value
   */
  private Object reject(
      HttpServletRequest request,
      HttpServletResponse response,
      RateLimitResponse result,
      RateLimit rateLimit)
      throws IOException {
    if (response == null || rateLimit.throwOnReject()) {
      throw new RateLimitExceededException(result);
    }
    headerWriter.write(response, result);
    log.debug("Invocation rate limited");
    responseWriter.write(request, response, result);
    return null;
  }

  private void recordDuration(
      String ruleSetId, String endpoint, HttpServletRequest request, long startTimeMs) {
    if (durationRecorder == null) {
      return;
    }
    durationRecorder.recordDuration(
        ruleSetId,
        endpoint,
        request != null ? request.getMethod() : INVOCATION_METHOD,
        Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
  }

  /**
   * Endpoint identifier for the invocation: the request path in a web context, otherwise the
   * intercepted signature so non-web invocations still produce a stable, low cardinality value.
   */
  private static String endpointOf(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
    if (request != null) {
      return RequestPathResolver.resolve(request);
    }
    return joinPoint.getSignature().getDeclaringType().getSimpleName()
        + "."
        + joinPoint.getSignature().getName();
  }

  /** Gets the current HttpServletRequest from RequestContextHolder, or null outside a request. */
  private static HttpServletRequest getCurrentRequest() {
    ServletRequestAttributes attrs = currentAttributes();
    return attrs != null ? attrs.getRequest() : null;
  }

  /** Gets the current HttpServletResponse from RequestContextHolder, or null outside a request. */
  private static HttpServletResponse getCurrentResponse() {
    ServletRequestAttributes attrs = currentAttributes();
    return attrs != null ? attrs.getResponse() : null;
  }

  private static ServletRequestAttributes currentAttributes() {
    try {
      return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes
          ? (ServletRequestAttributes) RequestContextHolder.getRequestAttributes()
          : null;
    } catch (IllegalStateException e) {
      return null;
    }
  }

  /** Outcome of the rate limit evaluation: either proceed, or reject with this response. */
  private static final class Decision {

    private final boolean allowed;
    private final RateLimitResponse result;

    private Decision(boolean allowed, RateLimitResponse result) {
      this.allowed = allowed;
      this.result = result;
    }

    static Decision allowed(RateLimitResponse result) {
      return new Decision(true, result);
    }

    static Decision rejected(RateLimitResponse result) {
      return new Decision(false, result);
    }
  }
}
