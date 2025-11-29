package org.openfluxgate.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Core configuration object describing a rate limit rule.
 * <p>
 * This rule is intentionally storage-agnostic and engine-agnostic.
 * It does not depend on Redis, MongoDB, HTTP frameworks, etc.
 * Later, adapters will translate this rule into Bucket4j configuration.
 */
public final class RateLimitRule {

    private final String id;
    private final String name;
    private final boolean enabled;

    private final LimitScope scope;
    /**
     * Identifier of the key strategy (for example "ip", "userId", "apiKey").
     * The actual implementation will be resolved via SPI.
     */
    private final String keyStrategyId;

    /**
     * How to behave when the limit is exceeded.
     */
    private final OnLimitExceedPolicy onLimitExceedPolicy;

    private final List<RateLimitBand> bands;

    private RateLimitRule(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null");
        this.name = builder.name != null ? builder.name : builder.id;
        this.enabled = builder.enabled;
        this.scope = Objects.requireNonNull(builder.scope, "scope must not be null");
        this.keyStrategyId = Objects.requireNonNull(builder.keyStrategyId, "keyStrategyId must not be null");
        this.onLimitExceedPolicy = Objects.requireNonNull(builder.onLimitExceedPolicy,
                "onLimitExceedPolicy must not be null");

        if (builder.bands.isEmpty()) {
            throw new IllegalArgumentException("At least one RateLimitBand must be configured");
        }
        this.bands = List.copyOf(builder.bands);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LimitScope getScope() {
        return scope;
    }

    public String getKeyStrategyId() {
        return keyStrategyId;
    }

    public OnLimitExceedPolicy getOnLimitExceedPolicy() {
        return onLimitExceedPolicy;
    }

    public List<RateLimitBand> getBands() {
        return Collections.unmodifiableList(bands);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private boolean enabled = true;
        private LimitScope scope = LimitScope.PER_API_KEY;
        private String keyStrategyId = "apiKey"; // sensible default
        private OnLimitExceedPolicy onLimitExceedPolicy = OnLimitExceedPolicy.REJECT_REQUEST;
        private final List<RateLimitBand> bands = new ArrayList<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id must not be null");
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder scope(LimitScope scope) {
            this.scope = scope;
            return this;
        }

        /**
         * References a key strategy defined in SPI, e.g. "ip", "userId", "apiKey".
         */
        public Builder keyStrategyId(String keyStrategyId) {
            this.keyStrategyId = keyStrategyId;
            return this;
        }

        public Builder onLimitExceedPolicy(OnLimitExceedPolicy policy) {
            this.onLimitExceedPolicy = policy;
            return this;
        }

        public Builder addBand(RateLimitBand band) {
            this.bands.add(Objects.requireNonNull(band, "band must not be null"));
            return this;
        }

        public RateLimitRule build() {
            return new RateLimitRule(this);
        }
    }

    @Override
    public String toString() {
        return "RateLimitRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", scope=" + scope +
                ", keyStrategyId='" + keyStrategyId + '\'' +
                ", onLimitExceedPolicy=" + onLimitExceedPolicy +
                ", bands=" + bands +
                '}';
    }
}