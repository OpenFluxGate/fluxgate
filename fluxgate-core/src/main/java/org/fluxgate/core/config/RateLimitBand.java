package org.fluxgate.core.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Represents a single rate limit band, such as:
 * - 1 minute, 100 requests
 * - 10 minutes, 500 requests
 */
public final class RateLimitBand {

    private final Duration window;
    private final long capacity;
    private final String label; // optional, for metrics / admin UI

    private RateLimitBand(Builder builder) {
        this.window = Objects.requireNonNull(builder.window, "window must not be null");
        if (builder.capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = builder.capacity;
        this.label = builder.label;
    }

    public Duration getWindow() {
        return window;
    }

    public long getCapacity() {
        return capacity;
    }

    public String getLabel() {
        return label;
    }

    public static Builder builder(Duration window, long capacity) {
        return new Builder(window, capacity);
    }

    public static final class Builder {
        private final Duration window;
        private final long capacity;
        private String label;

        private Builder(Duration window, long capacity) {
            this.window = window;
            this.capacity = capacity;
        }

        /**
         * Optional human-readable label for metrics or admin UI.
         */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public RateLimitBand build() {
            return new RateLimitBand(this);
        }
    }

    @Override
    public String toString() {
        return "RateLimitBand{" +
                "window=" + window +
                ", capacity=" + capacity +
                ", label='" + label + '\'' +
                '}';
    }
}