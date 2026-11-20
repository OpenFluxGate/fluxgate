package org.fluxgate.spring.handler;

import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.core.handler.RateLimitResponse;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stopgap {@link FluxgateRateLimitHandler} for a deployment that has a rate limiter but nothing to
 * read rules from.
 *
 * <p>Enabling Redis without the MongoDB adapter produces a {@code RateLimiter} bean and no {@link
 * RateLimitRuleSetProvider}, so neither the engine nor {@link EngineBackedRateLimitHandler} can be
 * created. The filter then fell through to its own no-handler path, which reported that no {@code
 * RateLimiter} bean was found - the one component that did exist - and silently let every request
 * through or rejected every request, depending on {@code fluxgate.ratelimit.failure-behavior}.
 *
 * <p>This handler exists so that situation is named once, at startup, in terms of the piece that is
 * actually missing, and then behaves exactly as the documented failure behaviour says: {@code
 * ALLOW} lets requests through, {@code DENY} rejects them with HTTP 503 ({@link
 * RateLimiterUnavailableException}). Set {@code fluxgate.ratelimit.fail-on-missing-handler=true} to
 * fail the boot instead of running like this.
 */
public class MissingRuleSetProviderRateLimitHandler implements FluxgateRateLimitHandler {

  private static final Logger log =
      LoggerFactory.getLogger(MissingRuleSetProviderRateLimitHandler.class);

  private final boolean allowRequests;

  /**
   * Creates the stopgap handler and logs the single startup error that explains it.
   *
   * @param allowRequests whether requests are allowed, that is {@code failure-behavior=ALLOW}
   */
  public MissingRuleSetProviderRateLimitHandler(boolean allowRequests) {
    this.allowRequests = allowRequests;
    log.error(
        "A RateLimiter bean exists but no RateLimitRuleSetProvider: enable fluxgate.mongo, define "
            + "a RateLimitRuleSetProvider bean, or supply a FluxgateRateLimitHandler. Requests are "
            + "{} per failure-behavior. Set fluxgate.ratelimit.fail-on-missing-handler=true to "
            + "fail startup instead.",
        allowRequests ? "allowed" : "rejected");
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
    return tryConsume(context, ruleSetId, 1L);
  }

  @Override
  public RateLimitResponse tryConsume(RequestContext context, String ruleSetId, long permits) {
    if (allowRequests) {
      return RateLimitResponse.allowed(-1L, 0L);
    }
    throw new RateLimiterUnavailableException(
        "No RateLimitRuleSetProvider is configured (failure-behavior=DENY)");
  }

  /**
   * Whether this handler allows requests through.
   *
   * @return true when {@code fluxgate.ratelimit.failure-behavior=ALLOW}
   */
  public boolean isAllowRequests() {
    return allowRequests;
  }
}
