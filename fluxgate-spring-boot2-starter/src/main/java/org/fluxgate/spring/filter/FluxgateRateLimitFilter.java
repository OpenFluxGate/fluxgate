package org.fluxgate.spring.filter;

import static org.fluxgate.core.constants.FluxgateConstants.Headers;
import static org.fluxgate.core.constants.FluxgateConstants.MdcKeys;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.spring.util.LogSanitizer;
import org.fluxgate.spring.util.RequestPathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP Filter that applies rate limiting to incoming requests.
 *
 * <p>This filter delegates rate limiting logic to a {@link FluxgateRateLimitHandler}, which allows
 * flexible implementation strategies:
 *
 * <ul>
 *   <li>API-based: Call external FluxGate API server
 *   <li>Redis direct: Use Redis rate limiter directly
 *   <li>Standalone: In-memory rate limiting (for testing)
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Extracts client IP from request, honouring forwarding headers only behind trusted proxies
 *   <li>Configurable include/exclude URL patterns, matched against the normalized request path
 *   <li>Sets legacy and IETF rate limit HTTP headers on allowed and rejected responses
 *   <li>Returns 429 Too Many Requests via a pluggable {@link RateLimitResponseWriter}
 *   <li>Supports WAIT_FOR_REFILL policy with semaphore-based concurrency control
 * </ul>
 *
 * <p>The filter chain is invoked <b>exactly once</b> and always outside the rate limiter's
 * try/catch, so an exception thrown by the application is never mistaken for a rate limiter failure
 * and never causes the request to be replayed.
 */
public class FluxgateRateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(FluxgateRateLimitFilter.class);

  /** Permit count of an ordinary, unweighted request. */
  private static final long SINGLE_PERMIT = 1L;

  /**
   * Request headers recorded in the MDC. They are not part of {@code FluxgateConstants.Headers}
   * because FluxGate only reads them for diagnostics, it never acts on them.
   */
  private static final String USER_AGENT_HEADER = "User-Agent";

  private static final String REFERER_HEADER = "Referer";

  private final FluxgateRateLimitHandler handler;
  private final String ruleSetId;
  private final String[] includePatterns;
  private final String[] excludePatterns;
  private final boolean failOpenOnError;
  private final boolean denyWhenRuleMissing;
  private final AntPathMatcher pathMatcher;
  private final boolean logQueryString;
  private final String costHeader;
  private final long maxCost;

  // WAIT_FOR_REFILL configuration
  private final boolean waitForRefillEnabled;
  private final long maxWaitTimeMs;
  private final Semaphore waitSemaphore;

  // Collaborators shared with the aspect
  private final RequestContextFactory contextFactory;
  private final RateLimitHeaderWriter headerWriter;
  private final RateLimitResponseWriter responseWriter;
  private final RateLimitDurationRecorder durationRecorder;

  /**
   * Creates a new FluxgateRateLimitFilter with default settings (WAIT_FOR_REFILL disabled).
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns) {
    this(handler, ruleSetId, includePatterns, excludePatterns, false, 5000, 50, null);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with WAIT_FOR_REFILL configuration.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits) {
    this(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitForRefillEnabled,
        maxWaitTimeMs,
        maxConcurrentWaits,
        null);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with full configuration including RequestContext
   * customization.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   * @param contextCustomizer Customizer for RequestContext (nullable)
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits,
      RequestContextCustomizer contextCustomizer) {
    this(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitForRefillEnabled,
        maxWaitTimeMs,
        maxConcurrentWaits,
        contextCustomizer,
        Headers.X_FORWARDED_FOR,
        true,
        true,
        false);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with full security-sensitive configuration.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   * @param contextCustomizer Customizer for RequestContext (nullable)
   * @param clientIpHeader Header used for client IP extraction when trusted
   * @param trustClientIpHeader Trust forwarding headers for client IP extraction
   * @param failOpenOnError Allow requests when the rate limiter fails
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits,
      RequestContextCustomizer contextCustomizer,
      String clientIpHeader,
      boolean trustClientIpHeader,
      boolean failOpenOnError) {
    this(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitForRefillEnabled,
        maxWaitTimeMs,
        maxConcurrentWaits,
        contextCustomizer,
        clientIpHeader,
        trustClientIpHeader,
        failOpenOnError,
        false);
  }

  /**
   * Creates a new FluxgateRateLimitFilter with full security-sensitive configuration.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use
   * @param includePatterns URL patterns to include
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   * @param contextCustomizer Customizer for RequestContext (nullable)
   * @param clientIpHeader Header used for client IP extraction when trusted
   * @param trustClientIpHeader Trust forwarding headers for client IP extraction
   * @param failOpenOnError Allow requests when the rate limiter fails
   * @param denyWhenRuleMissing Deny requests when no rule set ID is configured
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits,
      RequestContextCustomizer contextCustomizer,
      String clientIpHeader,
      boolean trustClientIpHeader,
      boolean failOpenOnError,
      boolean denyWhenRuleMissing) {
    this(
        handler,
        ruleSetId,
        includePatterns,
        excludePatterns,
        waitForRefillEnabled,
        maxWaitTimeMs,
        maxConcurrentWaits,
        failOpenOnError,
        denyWhenRuleMissing,
        true,
        false,
        null,
        0L,
        new RequestContextFactory(
            clientIpHeader, trustClientIpHeader, null, false, null, contextCustomizer),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  /**
   * Creates a fully configured filter. This is the constructor the auto-configuration uses; the
   * others delegate to it with legacy defaults.
   *
   * @param handler The rate limit handler (required)
   * @param ruleSetId Default rule set ID to use (required, may be empty)
   * @param includePatterns URL patterns to include (empty means all)
   * @param excludePatterns URL patterns to exclude
   * @param waitForRefillEnabled Enable WAIT_FOR_REFILL behavior
   * @param maxWaitTimeMs Maximum time to wait for token refill
   * @param maxConcurrentWaits Maximum concurrent waiting requests (semaphore permits)
   * @param failOpenOnError Allow requests when the rate limiter fails
   * @param denyWhenRuleMissing Deny requests when no rule set ID is configured
   * @param caseSensitivePatterns Match include/exclude patterns case sensitively
   * @param logQueryString Put the raw query string into the MDC
   * @param costHeader Optional header carrying the request cost in permits (nullable)
   * @param maxCost Upper bound applied to the cost header value
   * @param contextFactory Factory building the {@link RequestContext} (required)
   * @param headerWriter Writer for rate limit headers (required)
   * @param responseWriter Writer for the 429 body (required)
   * @param durationRecorder Optional recorder for the request duration timer (nullable)
   */
  public FluxgateRateLimitFilter(
      FluxgateRateLimitHandler handler,
      String ruleSetId,
      String[] includePatterns,
      String[] excludePatterns,
      boolean waitForRefillEnabled,
      long maxWaitTimeMs,
      int maxConcurrentWaits,
      boolean failOpenOnError,
      boolean denyWhenRuleMissing,
      boolean caseSensitivePatterns,
      boolean logQueryString,
      String costHeader,
      long maxCost,
      RequestContextFactory contextFactory,
      RateLimitHeaderWriter headerWriter,
      RateLimitResponseWriter responseWriter,
      RateLimitDurationRecorder durationRecorder) {
    this.handler = Objects.requireNonNull(handler, "handler must not be null");
    this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    this.includePatterns = includePatterns != null ? includePatterns : new String[0];
    this.excludePatterns = excludePatterns != null ? excludePatterns : new String[0];
    this.failOpenOnError = failOpenOnError;
    this.denyWhenRuleMissing = denyWhenRuleMissing;
    this.waitForRefillEnabled = waitForRefillEnabled;
    this.maxWaitTimeMs = maxWaitTimeMs;
    this.waitSemaphore = new Semaphore(Math.max(1, maxConcurrentWaits));
    this.logQueryString = logQueryString;
    this.costHeader = costHeader;
    // N-7: a non-positive max-cost used to mean "unlimited", so a "max-cost: 0" typo let a client
    // empty any bucket with a single request. Refuse to start instead.
    if (StringUtils.hasText(costHeader) && maxCost <= 0) {
      throw new IllegalArgumentException(
          "fluxgate.ratelimit.max-cost must be > 0 when fluxgate.ratelimit.cost-header is set"
              + " (got "
              + maxCost
              + "); 0 or a negative value would let a client spend an unbounded number of"
              + " permits");
    }
    this.maxCost = maxCost;
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory must not be null");
    this.headerWriter = Objects.requireNonNull(headerWriter, "headerWriter must not be null");
    this.responseWriter = Objects.requireNonNull(responseWriter, "responseWriter must not be null");
    this.durationRecorder = durationRecorder;

    this.pathMatcher = new AntPathMatcher();
    this.pathMatcher.setCaseSensitive(caseSensitivePatterns);

    if (log.isDebugEnabled()) {
      log.debug("FluxgateRateLimitFilter initialized");
      log.debug("  Handler: {}", handler.getClass().getSimpleName());
      log.debug("  Rule set ID: {}", ruleSetId);
      log.debug("  Include patterns: {}", Arrays.toString(this.includePatterns));
      log.debug("  Exclude patterns: {}", Arrays.toString(this.excludePatterns));
      log.debug("  Case sensitive patterns: {}", caseSensitivePatterns);
      log.debug("  Fail open on error: {}", failOpenOnError);
      log.debug("  Deny when rule missing: {}", denyWhenRuleMissing);
      log.debug("  Cost header: {}", StringUtils.hasText(costHeader) ? costHeader : "(not set)");
      log.debug("  WAIT_FOR_REFILL enabled: {}", waitForRefillEnabled);
      if (waitForRefillEnabled) {
        log.debug("  Max wait time: {} ms", maxWaitTimeMs);
        log.debug("  Max concurrent waits: {}", maxConcurrentWaits);
      }
    }
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    // M28/H-4: preserve MDC entries put in place by filters that ran before this one.
    Map<String, String> previousMdc = MDC.getCopyOfContextMap();
    long startTimeMs = System.currentTimeMillis();

    try {
      String path = RequestPathResolver.resolve(request);
      populateRequestMdc(request, path);

      if (shouldExclude(path)) {
        log.debug("Path excluded from rate limiting: {}", path);
        filterChain.doFilter(request, response);
        return;
      }

      if (!shouldInclude(path)) {
        log.debug("Path not included in rate limiting: {}", path);
        filterChain.doFilter(request, response);
        return;
      }

      if (!StringUtils.hasText(ruleSetId)) {
        if (denyWhenRuleMissing) {
          log.warn("No rule set ID configured, rejecting request");
          reject(request, response, RateLimitResponse.rejected(0), startTimeMs);
        } else {
          log.warn("No rule set ID configured, skipping rate limiting");
          filterChain.doFilter(request, response);
        }
        return;
      }

      RequestContext context = contextFactory.create(request, path);
      putIdentityMdc(context.getUserId(), context.getApiKey());
      Decision decision = decide(context, resolvePermits(request));

      // C2/C-1: the chain runs exactly once, outside the rate limiter's try/catch, so an exception
      // thrown by the application propagates instead of triggering a replay.
      if (decision.allowed) {
        headerWriter.write(response, decision.result);
        filterChain.doFilter(request, response);
        MDC.put(MdcKeys.STATUS_CODE, String.valueOf(response.getStatus()));
        MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
        log.debug("Request completed");
        recordDuration(path, request.getMethod(), startTimeMs);
      } else {
        reject(request, response, decision.result, startTimeMs);
        recordDuration(path, request.getMethod(), startTimeMs);
      }
    } finally {
      restoreMdc(previousMdc);
    }
  }

  /**
   * Runs the rate limiter and decides whether the request may proceed.
   *
   * <p>Only the handler call and the bookkeeping around it are guarded, so a limiter failure is
   * distinguishable from an application failure.
   */
  private Decision decide(RequestContext context, long permits) {
    try {
      RateLimitResponse result = tryConsume(context, permits);

      MDC.put(MdcKeys.RATE_LIMIT_ALLOWED, String.valueOf(result.isAllowed()));
      MDC.put(MdcKeys.REMAINING_TOKENS, String.valueOf(result.getRemainingTokens()));

      if (!result.isAllowed() && waitForRefillEnabled && result.shouldWaitForRefill()) {
        result = waitForRefill(context, permits, result);
      }

      if (result.isAllowed()) {
        return Decision.allowed(result);
      }

      MDC.put(MdcKeys.RETRY_AFTER_MS, String.valueOf(result.getRetryAfterMillis()));
      return Decision.rejected(result);
    } catch (Exception e) {
      MDC.put(MdcKeys.ERROR, e.getClass().getSimpleName());
      MDC.put(MdcKeys.ERROR_MESSAGE, LogSanitizer.sanitize(e.getMessage()));
      if (failOpenOnError) {
        log.error("Error during rate limiting, allowing request", e);
        return Decision.allowed(null);
      }
      log.error("Error during rate limiting, rejecting request", e);
      return Decision.rejected(RateLimitResponse.rejected(0));
    }
  }

  /**
   * Applies the WAIT_FOR_REFILL policy: wait for the advertised delay, then retry once.
   *
   * <p>A non-blocking semaphore bounds how many worker threads may be parked at the same time; the
   * rest are rejected immediately, because parking every thread turns the rate limiter into a
   * denial of service amplifier.
   *
   * <p>The wait deliberately blocks the request thread. Servlet async ({@code startAsync} plus
   * {@code dispatch}) was considered and rejected: the re-dispatch runs as {@code ASYNC}, which
   * skips every later filter registered only for {@code REQUEST} and every {@code
   * OncePerRequestFilter} that does not filter async dispatches, so authentication or tenant
   * filters ordered after FluxGate would never run for a waited request.
   *
   * @return the final decision, either the retry result or the original rejection
   */
  private RateLimitResponse waitForRefill(
      RequestContext context, long permits, RateLimitResponse result) {

    long waitTimeMs = result.getRetryAfterMillis();
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

      RateLimitResponse retryResult = tryConsume(context, permits);
      if (!retryResult.isAllowed()) {
        log.debug("Request still rate limited after wait");
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

  private RateLimitResponse tryConsume(RequestContext context, long permits) {
    // The 3-arg form is optional for handlers, so only use it for a weighted request.
    return permits == SINGLE_PERMIT
        ? handler.tryConsume(context, ruleSetId)
        : handler.tryConsume(context, ruleSetId, permits);
  }

  /**
   * Resolves how many permits this request costs.
   *
   * @return the permit count, always at least 1 and never above the configured maximum
   */
  private long resolvePermits(HttpServletRequest request) {
    if (!StringUtils.hasText(costHeader)) {
      return SINGLE_PERMIT;
    }
    String raw = request.getHeader(costHeader);
    if (!StringUtils.hasText(raw)) {
      return SINGLE_PERMIT;
    }
    try {
      long cost = Long.parseLong(raw.trim());
      return cost < SINGLE_PERMIT ? SINGLE_PERMIT : Math.min(cost, maxCost);
    } catch (NumberFormatException e) {
      log.debug("Ignoring unparseable {} header", costHeader);
      return SINGLE_PERMIT;
    }
  }

  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      RateLimitResponse result,
      long startTimeMs)
      throws IOException {
    headerWriter.write(response, result);
    MDC.put(MdcKeys.STATUS_CODE, "429");
    MDC.put(MdcKeys.DURATION_MS, String.valueOf(System.currentTimeMillis() - startTimeMs));
    log.debug("Request rate limited");
    responseWriter.write(request, response, result);
  }

  private void recordDuration(String path, String method, long startTimeMs) {
    if (durationRecorder == null) {
      return;
    }
    durationRecorder.recordDuration(
        ruleSetId, path, method, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
  }

  /**
   * Populates the MDC with request information.
   *
   * <p>Every value that originates from the request is sanitized first: a header containing CRLF
   * would otherwise forge log lines, and an oversized one would flood the log.
   */
  private void populateRequestMdc(HttpServletRequest request, String path) {
    String traceId = LogSanitizer.sanitize(request.getHeader(Headers.TRACE_ID), 128);
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString();
    }

    MDC.put(MdcKeys.TRACE_ID, traceId);
    MDC.put(MdcKeys.RULE_SET_ID, ruleSetId);

    MDC.put(MdcKeys.METHOD, LogSanitizer.sanitize(request.getMethod(), 16));
    MDC.put(MdcKeys.ENDPOINT, LogSanitizer.sanitize(path));
    MDC.put(MdcKeys.CLIENT_IP, LogSanitizer.sanitize(contextFactory.extractClientIp(request), 45));
    MDC.put(MdcKeys.PROTOCOL, LogSanitizer.sanitize(request.getProtocol(), 16));
    MDC.put(MdcKeys.SERVER_PORT, String.valueOf(request.getServerPort()));

    if (logQueryString) {
      Optional.ofNullable(request.getQueryString())
          .ifPresent(v -> MDC.put(MdcKeys.QUERY_STRING, LogSanitizer.sanitize(v)));
    }
    Optional.ofNullable(request.getHeader(USER_AGENT_HEADER))
        .ifPresent(v -> MDC.put(MdcKeys.USER_AGENT, LogSanitizer.sanitize(v)));
    Optional.ofNullable(request.getHeader(REFERER_HEADER))
        .ifPresent(v -> MDC.put(MdcKeys.REFERER, LogSanitizer.sanitize(v)));

    // C-4: identity headers are client input. Log them as the caller's identity only when the
    // identity source actually reads them; otherwise the resolved identity is added once the
    // context exists (putResolvedIdentityMdc).
    if (contextFactory.usesIdentityHeaders()) {
      putIdentityMdc(
          request.getHeader(contextFactory.getUserIdHeader()),
          request.getHeader(contextFactory.getApiKeyHeader()));
    }
  }

  /** Records the identity the limiter actually used, such as the authenticated principal. */
  private static void putIdentityMdc(String userId, String apiKey) {
    Optional.ofNullable(userId)
        .ifPresent(v -> MDC.put(MdcKeys.USER_ID, LogSanitizer.sanitize(v, 128)));
    Optional.ofNullable(apiKey)
        .ifPresent(v -> MDC.put(MdcKeys.API_KEY, maskSensitive(LogSanitizer.sanitize(v, 128))));
  }

  /** Restores the MDC to the state the request arrived with. */
  private static void restoreMdc(Map<String, String> previousMdc) {
    MDC.clear();
    if (previousMdc != null && !previousMdc.isEmpty()) {
      MDC.setContextMap(previousMdc);
    }
  }

  /**
   * Checks if a path should be excluded from rate limiting.
   *
   * <p>N-2: a path that could not be normalized is never excluded. Matching it against operator
   * patterns would decide the question with a string the operator never wrote.
   */
  private boolean shouldExclude(String path) {
    if (RequestPathResolver.UNRESOLVABLE_PATH.equals(path)) {
      return false;
    }
    for (String pattern : excludePatterns) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if a path should be included in rate limiting.
   *
   * <p>N-2: a path that could not be normalized is always included, so an undecodable request
   * cannot switch the rate limiter off for itself.
   */
  private boolean shouldInclude(String path) {
    if (RequestPathResolver.UNRESOLVABLE_PATH.equals(path)) {
      return true;
    }
    if (includePatterns.length == 0) {
      return true; // Include all by default
    }
    for (String pattern : includePatterns) {
      if (pathMatcher.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  /** Masks sensitive data for logging (shows the first 4 characters only). */
  private static String maskSensitive(String value) {
    if (value == null || value.length() <= 4) {
      return "****";
    }
    return value.substring(0, 4) + "****";
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
