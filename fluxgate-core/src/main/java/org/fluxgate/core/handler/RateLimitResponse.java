package org.fluxgate.core.handler;

/**
 * Response from rate limit handler.
 * <p>
 * Contains the result of a rate limit check including:
 * <ul>
 *   <li>Whether the request is allowed</li>
 *   <li>Remaining tokens in the bucket</li>
 *   <li>Time to wait before retry (if rejected)</li>
 * </ul>
 */
public final class RateLimitResponse {

    private final boolean allowed;
    private final long remainingTokens;
    private final long retryAfterMillis;

    private RateLimitResponse(boolean allowed, long remainingTokens, long retryAfterMillis) {
        this.allowed = allowed;
        this.remainingTokens = remainingTokens;
        this.retryAfterMillis = retryAfterMillis;
    }

    /**
     * Creates an allowed response.
     *
     * @param remainingTokens Number of tokens remaining (-1 if unknown)
     * @param retryAfterMillis Milliseconds until next token available
     * @return Allowed response
     */
    public static RateLimitResponse allowed(long remainingTokens, long retryAfterMillis) {
        return new RateLimitResponse(true, remainingTokens, retryAfterMillis);
    }

    /**
     * Creates a rejected response.
     *
     * @param retryAfterMillis Milliseconds to wait before retry
     * @return Rejected response
     */
    public static RateLimitResponse rejected(long retryAfterMillis) {
        return new RateLimitResponse(false, 0, retryAfterMillis);
    }

    /**
     * Whether the request is allowed.
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Number of remaining tokens in the bucket.
     * Returns -1 if unknown.
     */
    public long getRemainingTokens() {
        return remainingTokens;
    }

    /**
     * Milliseconds to wait before retry.
     * Relevant when rejected.
     */
    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    @Override
    public String toString() {
        return "RateLimitResponse{" +
                "allowed=" + allowed +
                ", remainingTokens=" + remainingTokens +
                ", retryAfterMillis=" + retryAfterMillis +
                '}';
    }
}
