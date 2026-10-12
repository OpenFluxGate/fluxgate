package org.fluxgate.core.exception;

import org.fluxgate.core.config.LimitScope;

/**
 * Exception thrown when no rate limit key can be resolved for a rule.
 *
 * <p>This exception is thrown by {@code LimitScopeKeyResolver} when the value required by the
 * rule's {@link LimitScope} is missing from the request context and the configured {@code
 * MissingKeyBehavior} is {@code REJECT}. Rate limiter implementations catch it and return a
 * rejected result instead of silently falling back to a broader key.
 */
public class MissingRateLimitKeyException extends FluxgateConfigurationException {

  /** Id of the rule being evaluated. */
  private final String ruleId;

  /** The limit scope whose key value was missing. */
  private final LimitScope scope;

  /**
   * Constructs a new MissingRateLimitKeyException for the given rule and scope.
   *
   * @param ruleId the ID of the rule being evaluated, may be null
   * @param scope the limit scope whose key value is missing
   */
  public MissingRateLimitKeyException(String ruleId, LimitScope scope) {
    super("No rate limit key available for scope " + scope + " (ruleId: " + ruleId + ")");
    this.ruleId = ruleId;
    this.scope = scope;
  }

  /**
   * Returns the ID of the rule being evaluated, if available.
   *
   * @return the rule ID, or null if not available
   */
  public String getRuleId() {
    return ruleId;
  }

  /**
   * Returns the limit scope whose key value was missing.
   *
   * @return the limit scope
   */
  public LimitScope getScope() {
    return scope;
  }
}
