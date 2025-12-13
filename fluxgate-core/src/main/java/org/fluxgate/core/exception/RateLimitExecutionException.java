package org.fluxgate.core.exception;

/**
 * Exception thrown when rate limit evaluation fails.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>Rate limit rule evaluation encounters an error</li>
 *   <li>Token bucket operation fails</li>
 *   <li>Rate limit result cannot be determined</li>
 * </ul>
 *
 */
public class RateLimitExecutionException extends FluxgateOperationException {

    private final String ruleSetId;
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
        super(message, cause, true);
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
        super(buildMessage(message, ruleSetId, key), cause, true);
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
