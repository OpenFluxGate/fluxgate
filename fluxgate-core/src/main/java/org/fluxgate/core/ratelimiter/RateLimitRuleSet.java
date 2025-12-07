// org.fluxgate.core.ratelimiter.RateLimitRuleSet

package org.fluxgate.core.ratelimiter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;

public final class RateLimitRuleSet {

  private final String id; // e.g. "auth-api-default"
  private final String description; // optional
  private final List<RateLimitRule> rules;

  // Strategy: how to resolve RateLimitKey from RequestContext
  private final KeyResolver keyResolver;

  // Optional metrics hook
  private final RateLimitMetricsRecorder metricsRecorder;

  private RateLimitRuleSet(Builder builder) {
    this.id = Objects.requireNonNull(builder.id, "id must not be null");
    this.description = builder.description;
    this.rules = List.copyOf(builder.rules);
    this.keyResolver = Objects.requireNonNull(builder.keyResolver, "keyResolver must not be null");
    this.metricsRecorder = builder.metricsRecorder;
  }

  public String getId() {
    return id;
  }

  public String getDescription() {
    return description;
  }

  public List<RateLimitRule> getRules() {
    return Collections.unmodifiableList(rules);
  }

  public KeyResolver getKeyResolver() {
    return keyResolver;
  }

  public RateLimitMetricsRecorder getMetricsRecorder() {
    return metricsRecorder;
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  public static final class Builder {
    private final String id;
    private String description;
    private List<RateLimitRule> rules = List.of();
    private KeyResolver keyResolver;
    private RateLimitMetricsRecorder metricsRecorder;

    public Builder(String id) {
      this.id = id;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder rules(List<RateLimitRule> rules) {
      this.rules = List.copyOf(rules);
      return this;
    }

    public Builder keyResolver(KeyResolver keyResolver) {
      this.keyResolver = keyResolver;
      return this;
    }

    public Builder metricsRecorder(RateLimitMetricsRecorder metricsRecorder) {
      this.metricsRecorder = metricsRecorder;
      return this;
    }

    public RateLimitRuleSet build() {
      if (rules == null || rules.isEmpty()) {
        throw new IllegalArgumentException("rules must contain at least one RateLimitRule");
      }
      if (keyResolver == null) {
        throw new IllegalStateException("keyResolver must not be null");
      }
      return new RateLimitRuleSet(this);
    }
  }
}
