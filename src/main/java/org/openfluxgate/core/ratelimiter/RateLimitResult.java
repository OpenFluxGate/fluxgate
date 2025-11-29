package org.openfluxgate.core.ratelimiter;

import org.openfluxgate.core.config.RateLimitRule;

import java.util.Objects;

import org.openfluxgate.core.context.RequestContext;
import org.openfluxgate.core.key.RateLimitKey;

/**
 * Result of a single rate limit evaluation.
 */
public final class RateLimitResult {

    private final boolean allowed;
    private final long remainingTokens;
    private final long nanosToWaitForRefill;
    private final RateLimitKey key;
    private final RateLimitRule matchedRule;

    private RateLimitResult(Builder builder) {
        this.allowed = builder.allowed;
        this.remainingTokens = builder.remainingTokens;
        this.nanosToWaitForRefill = builder.nanosToWaitForRefill;
        this.key = builder.key;
        this.matchedRule = builder.matchedRule;
    }

    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Remaining tokens in the bucket after this call.
     * May be {@code -1} if unknown or not tracked.
     */
    public long getRemainingTokens() {
        return remainingTokens;
    }

    /**
     * Estimated nanoseconds to wait until enough tokens are available.
     * {@code 0} when allowed and immediately reusable,
     * {@code -1} if unknown.
     */
    public long getNanosToWaitForRefill() {
        return nanosToWaitForRefill;
    }

    public RateLimitKey getKey() {
        return key;
    }

    /**
     * The rule that was used to evaluate this request.
     * May be {@code null} if not applicable.
     */
    public RateLimitRule getMatchedRule() {
        return matchedRule;
    }

    public static Builder builder(RateLimitKey key) {
        return new Builder(key);
    }

    public static RateLimitResult allowed(RateLimitKey key,
                                          RateLimitRule rule,
                                          long remainingTokens,
                                          long nanosToWaitForRefill) {

        return builder(key)
                .allowed(true)
                .matchedRule(rule)
                .remainingTokens(remainingTokens)
                .nanosToWaitForRefill(nanosToWaitForRefill)
                .build();
    }

    public static RateLimitResult rejected(RateLimitKey key,
                                           RateLimitRule rule,
                                           long nanosToWaitForRefill) {

        return builder(key)
                .allowed(false)
                .matchedRule(rule)
                .remainingTokens(0L)
                .nanosToWaitForRefill(nanosToWaitForRefill)
                .build();
    }

    public static RateLimitResult allowedWithoutRule() {
        return RateLimitResult.builder(null)
                .allowed(true)
                .remainingTokens(Long.MAX_VALUE) // effectively unlimited
                .nanosToWaitForRefill(0)
                .matchedRule(null)
                .build();
    }

    public static final class Builder {
        private final RateLimitKey key;
        private boolean allowed;
        private long remainingTokens = -1L;
        private long nanosToWaitForRefill = -1L;
        private RateLimitRule matchedRule;

        private Builder(RateLimitKey key) {
            this.key = key;
        }

        public Builder allowed(boolean allowed) {
            this.allowed = allowed;
            return this;
        }

        public Builder remainingTokens(long remainingTokens) {
            this.remainingTokens = remainingTokens;
            return this;
        }

        public Builder nanosToWaitForRefill(long nanosToWaitForRefill) {
            this.nanosToWaitForRefill = nanosToWaitForRefill;
            return this;
        }

        public Builder matchedRule(RateLimitRule matchedRule) {
            this.matchedRule = matchedRule;
            return this;
        }

        public RateLimitResult build() {
            if (!allowed && key == null) {
                throw new IllegalStateException("key must not be null for rejected result");
            }
            return new RateLimitResult(this);
        }
    }

    @Override
    public String toString() {
        return "RateLimitResult{" +
                "allowed=" + allowed +
                ", remainingTokens=" + remainingTokens +
                ", nanosToWaitForRefill=" + nanosToWaitForRefill +
                ", key=" + key +
                ", matchedRule=" + matchedRule +
                '}';
    }
}