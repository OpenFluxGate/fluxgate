package org.fluxgate.adapter.mongo.model;

import java.util.Objects;

public class RateLimitBandDocument {

    /**
     * Window size in seconds
     */
    private long windowSeconds;

    /**
     * Allowed tokens per window
     */
    private long capacity;

    /**
     * Human-readable label (e.g., "per-second", "per-minute")
     */
    private String label;

    protected RateLimitBandDocument() {
    }

    public RateLimitBandDocument(long windowSeconds, long capacity, String label) {
        if (windowSeconds <= 0) throw new IllegalArgumentException("windowSeconds must be > 0");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.windowSeconds = windowSeconds;
        this.capacity = capacity;
        this.label = Objects.requireNonNull(label, "label must not be null");
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
