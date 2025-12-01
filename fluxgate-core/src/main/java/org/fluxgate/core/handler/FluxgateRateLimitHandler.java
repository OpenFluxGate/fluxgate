package org.fluxgate.core.handler;

import org.fluxgate.core.context.RequestContext;

/**
 * Core interface for rate limit handling.
 * <p>
 * Implementations decide how to perform rate limiting:
 * <ul>
 *   <li>API-based: Call external FluxGate API server</li>
 *   <li>Redis direct: Use Redis rate limiter directly</li>
 *   <li>Standalone: In-memory rate limiting (for testing)</li>
 * </ul>
 * <p>
 * Example implementation:
 * <pre>
 * {@code @Component}
 * public class ApiRateLimitHandler implements FluxgateRateLimitHandler {
 *
 *     private final WebClient webClient;
 *
 *     public ApiRateLimitHandler(WebClient.Builder builder) {
 *         this.webClient = builder.baseUrl("http://fluxgate-api:8080").build();
 *     }
 *
 *     {@code @Override}
 *     public RateLimitResponse tryConsume(RequestContext context, String ruleSetId) {
 *         return webClient.post()
 *             .uri("/api/ratelimit/consume")
 *             .bodyValue(new RateLimitRequest(context, ruleSetId))
 *             .retrieve()
 *             .bodyToMono(RateLimitResponse.class)
 *             .block();
 *     }
 * }
 * </pre>
 */
public interface FluxgateRateLimitHandler {

    /**
     * Attempts to consume a token from the rate limit bucket.
     *
     * @param context   Request context containing client info (IP, userId, endpoint, etc.)
     * @param ruleSetId The rule set ID to apply
     * @return Rate limit response containing allowed status and metadata
     */
    RateLimitResponse tryConsume(RequestContext context, String ruleSetId);

    /**
     * Default handler that always allows requests.
     * Used as fallback when no handler is configured.
     */
    FluxgateRateLimitHandler ALLOW_ALL = (context, ruleSetId) ->
            RateLimitResponse.allowed(-1, 0);
}
