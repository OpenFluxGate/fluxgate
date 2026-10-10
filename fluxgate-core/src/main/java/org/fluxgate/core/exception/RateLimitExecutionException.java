package org.fluxgate.core.exception;

/**
 * Exception thrown when rate limit evaluation fails.
 *
 * <p>This exception is thrown when:
 *
 * <ul>
 *   <li>Rate limit rule evaluation encounters an error
 *   <li>Token bucket operation fails
 *   <li>Rate limit result cannot be determined
 * </ul>
 *
 * <p>Not retryable by default: the evaluation may already have consumed tokens, so retrying it
 * could charge the same request twice. Callers that know nothing was consumed can opt in through
 * the constructors taking a {@code retryable} flag.
 */
public class RateLimitExecutionException extends FluxgateOperationException {

  /** Id of the rule set being evaluated. */
  private final String ruleSetId;

  /** The rate limit key being evaluated. */
  private final String key;

  /**
   * Constructs a new RateLimitExecutionException with the specified message.
   *
   * @param message the detail message
   */
  public RateLimitExecutionException(String message) {
    super(message);
    this.ruleSetId = null;
    this.key = null;
  }

  /**
   * Constructs a new RateLimitExecutionException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public RateLimitExecutionException(String message, Throwable cause) {
    this(message, cause, false);
  }

  /**
   * Constructs a new RateLimitExecutionException with an explicit retry verdict.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   * @param retryable whether the operation can safely be retried
   */
  public RateLimitExecutionException(String message, Throwable cause, boolean retryable) {
    super(message, cause, retryable);
    this.ruleSetId = null;
    this.key = null;
  }

  /**
   * Constructs a new RateLimitExecutionException with context information.
   *
   * @param message the detail message
   * @param ruleSetId the ID of the rule set being evaluated
   * @param key the rate limit key being checked
   * @param cause the cause of the exception
   */
  public RateLimitExecutionException(
      String message, String ruleSetId, String key, Throwable cause) {
    this(message, ruleSetId, key, cause, false);
  }

  /**
   * Constructs a new RateLimitExecutionException with context information and an explicit retry
   * verdict.
   *
   * @param message the detail message
   * @param ruleSetId the ID of the rule set being evaluated
   * @param key the rate limit key being checked
   * @param cause the cause of the exception
   * @param retryable whether the operation can safely be retried
   */
  public RateLimitExecutionException(
      String message, String ruleSetId, String key, Throwable cause, boolean retryable) {
    super(buildMessage(message, ruleSetId, key), cause, retryable);
    this.ruleSetId = ruleSetId;
    this.key = key;
  }

  private static String buildMessage(String message, String ruleSetId, String key) {
    StringBuilder sb = new StringBuilder(message);
    if (ruleSetId != null) {
      sb.append(" (ruleSetId: ").append(ruleSetId);
      if (key != null) {
        sb.append(", key: ").append(key);
      }
      sb.append(")");
    }
    return sb.toString();
  }

  /**
   * Returns the rule set ID that was being evaluated, if available.
   *
   * @return the rule set ID, or null if not available
   */
  public String getRuleSetId() {
    return ruleSetId;
  }

  /**
   * Returns the rate limit key that was being checked, if available.
   *
   * @return the key, or null if not available
   */
  public String getKey() {
    return key;
  }
}
