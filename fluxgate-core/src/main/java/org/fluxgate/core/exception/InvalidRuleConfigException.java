package org.fluxgate.core.exception;

/**
 * Exception thrown when a rate limit rule configuration is invalid.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>A rule has invalid capacity or window settings</li>
 *   <li>A rule set contains no rules</li>
 *   <li>A rule ID or name is missing or invalid</li>
 * </ul>
 *
 */
public class InvalidRuleConfigException extends FluxgateConfigurationException {

    private final String ruleId;

    /**
     * Constructs a new InvalidRuleConfigException with the specified message.
     *
     * @param message the detail message
     */
    public InvalidRuleConfigException(String message) {
        super(message);
        this.ruleId = null;
    }

    /**
     * Constructs a new InvalidRuleConfigException with the specified message and rule ID.
     *
     * @param message the detail message
     * @param ruleId the ID of the invalid rule
     */
    public InvalidRuleConfigException(String message, String ruleId) {
        super(message + " (ruleId: " + ruleId + ")");
        this.ruleId = ruleId;
    }

    /**
     * Constructs a new InvalidRuleConfigException with the specified message, rule ID, and cause.
     *
     * @param message the detail message
     * @param ruleId the ID of the invalid rule
     * @param cause the cause of the exception
     */
    public InvalidRuleConfigException(String message, String ruleId, Throwable cause) {
        super(message + " (ruleId: " + ruleId + ")", cause);
        this.ruleId = ruleId;
    }

    /**
     * Returns the ID of the invalid rule, if available.
     *
     * @return the rule ID, or null if not available
     */
    public String getRuleId() {
        return ruleId;
    }
}
