package org.fluxgate.spring.aop;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.util.LogThrottle;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.filter.ProblemDetailRateLimitResponseWriter;
import org.fluxgate.spring.filter.RateLimitDurationRecorder;
import org.fluxgate.spring.filter.RateLimitHeaderWriter;
import org.fluxgate.spring.filter.RateLimitResponseWriter;
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.fluxgate.spring.filter.RequestContextFactory;
import org.fluxgate.spring.handler.PermitsExceedCapacityException;
import org.fluxgate.spring.handler.RateLimiterUnavailableException;
import org.fluxgate.spring.util.RequestPathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Aspect that applies rate limiting to methods annotated with {@link RateLimit}.
 *
 * <p>This aspect intercepts method calls and checks rate limits before execution. If the rate limit
 * is exceeded it writes a 429 Too Many Requests response when the intercepted method is a Spring
 * MVC request handler; any other method (a service, a scheduled task) gets {@link
 * RateLimitExceededException}, as does a handler whose annotation asks for it via {@link
 * RateLimit#throwOnReject()}.
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
   * Whether {@code @RateLimit(waitForRefill = true)} may wait. False only when {@code
   * fluxgate.ratelimit.wait-for-refill.enabled} is explicitly false (the global kill switch).
   */
  private final boolean annotationWaitsAllowed;

  /**
   * Global upper bound on a WAIT_FOR_REFILL wait, from {@code
   * fluxgate.ratelimit.wait-for-refill.max-wait-time-ms}. The effective bound is the smaller of
   * this and {@link RateLimit#maxWaitTimeMs()}.
   */
  private final long maxWaitTimeMs;

  /**
   * Bounds how many threads may be parked waiting for a refill.
   *
   * <p>C1: this used to be created per invocation, so {@code tryAcquire()} always succeeded and
   * every worker thread could sleep. It now outlives the invocation: under the auto-configuration
   * it is the application-wide {@link org.fluxgate.spring.filter.FluxgateWaitPermits} bean, shared
   * with the filter so the bound is global; the legacy constructors that take {@code
   * maxConcurrentWaits} give the aspect its own semaphore instead.
   */
  private final Semaphore waitSemaphore;

  /** Default of {@code fluxgate.ratelimit.wait-for-refill.max-wait-time-ms}. */
  private static final long DEFAULT_MAX_WAIT_TIME_MS = 5000L;

  private final RequestContextFactory contextFactory;
  private final RateLimitHeaderWriter headerWriter;
  private final RateLimitResponseWriter responseWriter;
  private final RateLimitDurationRecorder durationRecorder;

  /** Throttles the warning about a failing duration recorder. */
  private final LogThrottle durationRecorderWarnings = new LogThrottle();

  /** Whether a {@code @RateLimit} without any rule set id has been reported. */
  private final AtomicBoolean missingRuleSetIdReported = new AtomicBoolean();

  /**
   * Creates an aspect with safe defaults: forwarding headers are ignored (the client IP is the
   * remote address) and invocations are rejected when the rate limiter fails, matching the {@code
   * fluxgate.ratelimit} property defaults. Before 0.4.0 this constructor trusted {@code
   * X-Forwarded-For} and failed open.
   *
   * @param handler the rate limit handler
   * @param contextCustomizer optional request context customizer
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      @Autowired(required = false) RequestContextCustomizer contextCustomizer) {
    this(handler, contextCustomizer, Headers.X_FORWARDED_FOR, false, false, "", false);
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
   * Creates an aspect whose waits are capped at the default {@code
   * fluxgate.ratelimit.wait-for-refill.max-wait-time-ms} (5000 ms).
   *
   * @param handler the rate limit handler (required)
   * @param defaultRuleSetId rule set used when the annotation does not name one (nullable)
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param denyWhenRuleMissing reject when no rule set is configured at all
   * @param waitForRefillEnabled honour a rule's WAIT_FOR_REFILL policy
   * @param maxConcurrentWaits permits of this aspect's own wait semaphore, not shared with the
   *     filter
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
    this(
        handler,
        defaultRuleSetId,
        failOpenOnError,
        denyWhenRuleMissing,
        waitForRefillEnabled,
        maxConcurrentWaits,
        DEFAULT_MAX_WAIT_TIME_MS,
        contextFactory,
        headerWriter,
        responseWriter,
        durationRecorder);
  }

  /**
   * Creates an aspect with its own wait semaphore whose annotation waits are always honoured.
   *
   * <p>Waiting for a refill blocks the calling thread: an aspect cannot hand the invocation back to
   * the container, so {@code maxWaitTimeMs} and {@code maxConcurrentWaits} are the only bounds on
   * how many threads are parked and for how long. Prefer rejecting with {@code Retry-After} and
   * letting the client back off.
   *
   * @param handler the rate limit handler (required)
   * @param defaultRuleSetId rule set used when the annotation does not name one (nullable)
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param denyWhenRuleMissing reject when no rule set is configured at all
   * @param waitForRefillEnabled honour a rule's WAIT_FOR_REFILL policy
   * @param maxConcurrentWaits permits of this aspect's own wait semaphore, not shared with the
   *     filter
   * @param maxWaitTimeMs global upper bound on a wait; the annotation can only lower it
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
      long maxWaitTimeMs,
      RequestContextFactory contextFactory,
      RateLimitHeaderWriter headerWriter,
      RateLimitResponseWriter responseWriter,
      RateLimitDurationRecorder durationRecorder) {
    this(
        handler,
        defaultRuleSetId,
        failOpenOnError,
        denyWhenRuleMissing,
        waitForRefillEnabled,
        true,
        new Semaphore(Math.max(1, maxConcurrentWaits)),
        maxWaitTimeMs,
        contextFactory,
        headerWriter,
        responseWriter,
        durationRecorder);
  }

  /**
   * Creates a fully configured aspect. This is the constructor the auto-configuration uses; the
   * others delegate to it.
   *
   * @param handler the rate limit handler (required)
   * @param defaultRuleSetId rule set used when the annotation does not name one (nullable)
   * @param failOpenOnError allow the invocation when the rate limiter fails
   * @param denyWhenRuleMissing reject when no rule set is configured at all
   * @param waitForRefillEnabled honour a rule's WAIT_FOR_REFILL policy
   * @param annotationWaitsAllowed honour {@code @RateLimit(waitForRefill = true)}; false is the
   *     global kill switch
   * @param waitSemaphore bounds concurrent waits; shared with the filter so the bound is global
   * @param maxWaitTimeMs global upper bound on a wait; the annotation can only lower it
   * @param contextFactory factory building the {@link RequestContext} (required)
   * @param headerWriter writer for rate limit headers (required)
   * @param responseWriter writer for the 429 body (required)
   * @param durationRecorder optional recorder for the invocation duration timer (nullable)
   * @since 0.4.0
   */
  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      String defaultRuleSetId,
      boolean failOpenOnError,
      boolean denyWhenRuleMissing,
      boolean waitForRefillEnabled,
      boolean annotationWaitsAllowed,
      Semaphore waitSemaphore,
      long maxWaitTimeMs,
      RequestContextFactory contextFactory,
      RateLimitHeaderWriter headerWriter,
      RateLimitResponseWriter responseWriter,
      RateLimitDurationRecorder durationRecorder) {
    this.annotationWaitsAllowed = annotationWaitsAllowed;
    this.maxWaitTimeMs = maxWaitTimeMs;
    this.handler = handler;
    this.defaultRuleSetId = defaultRuleSetId;
    this.failOpenOnError = failOpenOnError;
    this.denyWhenRuleMissing = denyWhenRuleMissing;
    this.waitForRefillEnabled = waitForRefillEnabled;
    this.waitSemaphore = waitSemaphore != null ? waitSemaphore : new Semaphore(1);
    this.contextFactory = contextFactory;
    this.headerWriter = headerWriter;
    this.responseWriter = responseWriter;
    this.durationRecorder = durationRecorder;
  }

  /**
   * Returns the global upper bound on a WAIT_FOR_REFILL wait.
   *
   * @return the bound in milliseconds
   */
  public long getMaxWaitTimeMs() {
    return maxWaitTimeMs;
  }

  /**
   * Intercepts methods annotated with {@link RateLimit}.
   *
   * @param joinPoint the join point
   * @param rateLimit the rate limit annotation
   * @return the method result if allowed, null if a handler was rate limited and the response was
   *     written
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
   * @return the method result if allowed, null if a handler was rate limited and the response was
   *     written
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
        logMissingRuleSetId(joinPoint);
        return rejectUnavailable(
            joinPoint,
            request,
            response,
            RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER,
            rateLimit);
      }
      logMissingRuleSetId(joinPoint);
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
      if (decision.unavailable) {
        return rejectUnavailable(
            joinPoint, request, response, decision.retryAfterMillis, rateLimit);
      }
      if (decision.allowed) {
        headerWriter.write(response, decision.result);
        return joinPoint.proceed();
      }
      return reject(joinPoint, request, response, decision.result, rateLimit);
    } finally {
      recordDuration(ruleSetId, endpoint, request, startTimeMs);
    }
  }

  /**
   * Item 9: a {@code @RateLimit} without a rule set id and no default is reported once - at ERROR
   * when such invocations are rejected, at WARN when they pass unlimited - and at DEBUG afterwards,
   * instead of a WARN line per invocation.
   */
  private void logMissingRuleSetId(ProceedingJoinPoint joinPoint) {
    if (missingRuleSetIdReported.compareAndSet(false, true)) {
      if (denyWhenRuleMissing) {
        log.error(
            "@RateLimit without ruleSetId and no fluxgate.ratelimit.default-rule-set-id (first seen"
                + " on {}): such invocations are rejected (503) because"
                + " fluxgate.ratelimit.missing-rule-behavior=DENY",
            joinPoint.getSignature());
      } else {
        log.warn(
            "@RateLimit without ruleSetId and no fluxgate.ratelimit.default-rule-set-id (first seen"
                + " on {}): such invocations are not rate limited",
            joinPoint.getSignature());
      }
      return;
    }
    log.debug("No ruleSetId for {}", joinPoint.getSignature());
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
    } catch (PermitsExceedCapacityException e) {
      // RateLimit#permits() is fixed in code, so a cost above the band capacity is a configuration
      // problem here - answered like any other invalid rule configuration (503), never failed open.
      log.error(
          "@RateLimit(permits = {}) exceeds the capacity of rule set '{}': {}",
          permits,
          ruleSetId,
          e.getMessage());
      return Decision.unavailable(RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER);
    } catch (RateLimiterUnavailableException e) {
      // failure-behavior / missing-rule-behavior already decided to reject.
      log.debug("Rate limiting unavailable, rejecting invocation: {}", e.getMessage());
      return Decision.unavailable(e.getRetryAfterMillis());
    } catch (Exception e) {
      if (failOpenOnError) {
        log.error("Error during rate limiting, allowing invocation", e);
        return Decision.allowed(null);
      }
      log.error("Error during rate limiting, rejecting invocation", e);
      return Decision.unavailable(RateLimiterUnavailableException.UNKNOWN_RETRY_AFTER);
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
      // Item 11: wait-for-refill.enabled=false is a kill switch for annotation waits too.
      return annotationWaitsAllowed;
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
    // H11: the wait blocks this thread, so the global property is a hard ceiling; the annotation
    // can only lower it.
    long effectiveMaxWaitMs = Math.min(rateLimit.maxWaitTimeMs(), maxWaitTimeMs);
    if (waitTimeMs > effectiveMaxWaitMs) {
      log.debug("Wait time {} ms exceeds max {} ms, rejecting", waitTimeMs, effectiveMaxWaitMs);
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
   * <p>R5: the response is only written when the intercepted method is a Spring MVC handler
   * ({@code @RequestMapping} or one of its shortcuts) running in a servlet request, and its return
   * type can carry {@code null}. Any other method - a service called while a request is being
   * handled, a scheduled task, a method returning a primitive - gets {@link
   * RateLimitExceededException}: returning {@code null} to a Java caller that never expected one
   * was the old behaviour.
   *
   * @return null after writing the response
   * @throws RateLimitExceededException when the response is not written
   */
  private Object reject(
      ProceedingJoinPoint joinPoint,
      HttpServletRequest request,
      HttpServletResponse response,
      RateLimitResponse result,
      RateLimit rateLimit)
      throws IOException {
    if (response == null || rateLimit.throwOnReject() || !isWebHandler(joinPoint)) {
      throw new RateLimitExceededException(result);
    }
    headerWriter.write(response, result);
    log.debug("Invocation rate limited");
    responseWriter.write(request, response, result);
    return null;
  }

  /**
   * Item 10: rejects an invocation because rate limiting is unavailable or not configured. A web
   * handler gets HTTP 503 (with {@code Retry-After} when the wait is known); any other caller gets
   * {@link RateLimitExceededException} with {@link
   * RateLimitExceededException#isServiceUnavailable()} set.
   */
  private Object rejectUnavailable(
      ProceedingJoinPoint joinPoint,
      HttpServletRequest request,
      HttpServletResponse response,
      long retryAfterMillis,
      RateLimit rateLimit)
      throws IOException {
    if (response == null || rateLimit.throwOnReject() || !isWebHandler(joinPoint)) {
      throw new RateLimitExceededException(RateLimitResponse.rejected(retryAfterMillis), true);
    }
    if (retryAfterMillis > 0 && !response.isCommitted()) {
      response.setHeader(Headers.RETRY_AFTER, Long.toString((retryAfterMillis + 999L) / 1000L));
    }
    log.debug("Invocation rejected, rate limiting unavailable");
    responseWriter.writeUnavailable(request, response, retryAfterMillis);
    return null;
  }

  /**
   * Whether the intercepted method is a request handler whose result may be replaced by {@code
   * null}: annotated with {@code @RequestMapping} (directly, through a shortcut such as {@code
   * GetMapping}, or on an interface it implements) and not returning a primitive.
   */
  private static boolean isWebHandler(ProceedingJoinPoint joinPoint) {
    if (!(joinPoint.getSignature() instanceof MethodSignature)) {
      return false;
    }
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    if (method == null) {
      return false;
    }
    Object target = joinPoint.getTarget();
    if (target != null) {
      method = AopUtils.getMostSpecificMethod(method, AopUtils.getTargetClass(target));
    }
    Class<?> returnType = method.getReturnType();
    if (returnType.isPrimitive() && returnType != void.class) {
      return false;
    }
    return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
  }

  private void recordDuration(
      String ruleSetId, String endpoint, HttpServletRequest request, long startTimeMs) {
    if (durationRecorder == null) {
      return;
    }
    // Called from a finally block: a metrics failure must not replace the method's outcome.
    try {
      durationRecorder.recordDuration(
          ruleSetId,
          endpoint,
          request != null ? request.getMethod() : INVOCATION_METHOD,
          Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    } catch (Exception e) {
      if (durationRecorderWarnings.tryAcquire()) {
        log.warn(
            "Duration recorder {} failed; the invocation is unaffected. Further failures within {}"
                + " are logged at DEBUG.",
            durationRecorder.getClass().getName(),
            LogThrottle.DEFAULT_INTERVAL,
            e);
      } else {
        log.debug("Duration recorder {} failed", durationRecorder.getClass().getName(), e);
      }
    }
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
    private final boolean unavailable;
    private final long retryAfterMillis;

    private Decision(
        boolean allowed, RateLimitResponse result, boolean unavailable, long retryAfterMillis) {
      this.allowed = allowed;
      this.result = result;
      this.unavailable = unavailable;
      this.retryAfterMillis = retryAfterMillis;
    }

    static Decision allowed(RateLimitResponse result) {
      return new Decision(true, result, false, 0L);
    }

    static Decision rejected(RateLimitResponse result) {
      return new Decision(false, result, false, 0L);
    }

    static Decision unavailable(long retryAfterMillis) {
      return new Decision(false, null, true, retryAfterMillis);
    }
  }
}
