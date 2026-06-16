package org.fluxgate.spring.aop;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;

import java.io.IOException;
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
import org.fluxgate.spring.filter.RequestContextCustomizer;
import org.fluxgate.spring.util.ClientIpExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Aspect that applies rate limiting to methods annotated with {@link RateLimit}.
 *
 * <p>This aspect intercepts method calls and checks rate limits before execution. If the rate limit
 * is exceeded, it returns a 429 Too Many Requests response.
 *
 * @see RateLimit
 * @see FluxgateRateLimitHandler
 */
@Aspect
@Component
public class RateLimitAspect {

  private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

  private final FluxgateRateLimitHandler handler;
  private final RequestContextCustomizer contextCustomizer;

  public RateLimitAspect(
      FluxgateRateLimitHandler handler,
      @Autowired(required = false) RequestContextCustomizer contextCustomizer) {
    this.handler = handler;
    this.contextCustomizer =
        contextCustomizer != null ? contextCustomizer : RequestContextCustomizer.identity();
  }

  /**
   * Intercepts methods annotated with {@link RateLimit}.
   *
   * @param joinPoint the join point
   * @param rateLimit the rate limit annotation
   * @return the method result if allowed, null if rate limited
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
   * @return the method result if allowed, null if rate limited
   * @throws Throwable if the method throws an exception
   */
  @Around("@within(rateLimit) && !@annotation(org.fluxgate.spring.annotation.RateLimit)")
  public Object aroundClass(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    return doRateLimit(joinPoint, rateLimit);
  }

  private Object doRateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
    HttpServletRequest request = getCurrentRequest();
    HttpServletResponse response = getCurrentResponse();

    if (request == null || response == null) {
      log.warn("No HTTP context available, skipping rate limiting");
      return joinPoint.proceed();
    }

    String ruleSetId = rateLimit.ruleSetId();
    if (!StringUtils.hasText(ruleSetId)) {
      log.warn("No ruleSetId specified, skipping rate limiting");
      return joinPoint.proceed();
    }

    // Build request context
    RequestContext context = buildRequestContext(request);

    try {
      // Check rate limit
      RateLimitResponse result = handler.tryConsume(context, ruleSetId);

      // Add rate limit headers
      addRateLimitHeaders(response, result);

      if (result.isAllowed()) {
        return joinPoint.proceed();
      }

      // Handle wait for refill if enabled
      if (rateLimit.waitForRefill()) {
        return handleWaitForRefill(joinPoint, rateLimit, context, response, result);
      }

      // Rate limit exceeded
      handleRateLimitExceeded(response, result);
      return null;

    } catch (Exception e) {
      log.error("Error during rate limiting, allowing request", e);
      // Fail open: allow request if rate limiter fails
      return joinPoint.proceed();
    }
  }

  private Object handleWaitForRefill(
      ProceedingJoinPoint joinPoint,
      RateLimit rateLimit,
      RequestContext context,
      HttpServletResponse response,
      RateLimitResponse result)
      throws Throwable {

    long waitTimeMs = result.getRetryAfterMillis();
    long maxWaitTimeMs = rateLimit.maxWaitTimeMs();
    int maxConcurrentWaits = rateLimit.maxConcurrentWaits();

    // Check if wait time exceeds maximum allowed
    if (waitTimeMs > maxWaitTimeMs) {
      log.info("Wait time {} ms exceeds max {} ms, rejecting request", waitTimeMs, maxWaitTimeMs);
      handleRateLimitExceeded(response, result);
      return null;
    }

    // Use semaphore to limit concurrent waits
    Semaphore waitSemaphore = new Semaphore(maxConcurrentWaits);

    if (!waitSemaphore.tryAcquire()) {
      log.info("Too many concurrent waits, rejecting request");
      handleRateLimitExceeded(response, result);
      return null;
    }

    try {
      log.debug("Waiting {} ms for token refill", waitTimeMs);
      TimeUnit.MILLISECONDS.sleep(waitTimeMs);

      // Retry rate limit check after waiting
      RateLimitResponse retryResult = handler.tryConsume(context, rateLimit.ruleSetId());
      addRateLimitHeaders(response, retryResult);

      if (retryResult.isAllowed()) {
        log.debug("Request allowed after wait");
        return joinPoint.proceed();
      }

      // Still rejected after waiting
      log.info("Request still rate limited after wait");
      handleRateLimitExceeded(response, retryResult);
      return null;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Wait interrupted");
      handleRateLimitExceeded(response, result);
      return null;
    } finally {
      waitSemaphore.release();
    }
  }

  private RequestContext buildRequestContext(HttpServletRequest request) {
    RequestContext.Builder builder =
        RequestContext.builder()
            .clientIp(ClientIpExtractor.extract(request))
            .endpoint(request.getRequestURI())
            .method(request.getMethod());

    return contextCustomizer.customize(builder, request).build();
  }

  /** Adds standard rate limit headers to the response. */
  private void addRateLimitHeaders(HttpServletResponse response, RateLimitResponse result) {
    if (result.getRemainingTokens() >= 0) {
      response.setHeader(Headers.RATE_LIMIT_REMAINING, String.valueOf(result.getRemainingTokens()));
    }
  }

  /** Handles a rate limit exceeded response. */
  private void handleRateLimitExceeded(HttpServletResponse response, RateLimitResponse result)
      throws IOException {
    long retryAfterSeconds = (result.getRetryAfterMillis() + 999) / 1000;
    response.setHeader(Headers.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    response.setHeader(
        Headers.RATE_LIMIT_REMAINING, String.valueOf(Math.max(0, result.getRemainingTokens())));
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            String.format(
                "{\"error\":\"Rate limit exceeded\",\"retryAfter\":%d}", retryAfterSeconds));
  }

  /** Gets the current HttpServletRequest from RequestContextHolder. */
  private HttpServletRequest getCurrentRequest() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    return attrs != null ? attrs.getRequest() : null;
  }

  /** Gets the current HttpServletResponse from RequestContextHolder. */
  private HttpServletResponse getCurrentResponse() {
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    return attrs != null ? attrs.getResponse() : null;
  }
}
