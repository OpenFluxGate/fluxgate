package org.fluxgate.core.exception;

/**
 * Exception thrown when a required configuration property is missing.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>A required connection URI is not provided</li>
 *   <li>A required rule set ID is not configured</li>
 *   <li>A required bean or component is not available</li>
 * </ul>
 *
 */
public class MissingConfigurationException extends FluxgateConfigurationException {

    private final String propertyName;

    /**
     * Constructs a new MissingConfigurationException with the specified message.
     *
     * @param message the detail message
     */
    public MissingConfigurationException(String message) {
        super(message);
        this.propertyName = null;
    }

    /**
     * Constructs a new MissingConfigurationException for a specific property.
     *
     * @param propertyName the name of the missing property
     * @param message the detail message
     */
    public MissingConfigurationException(String propertyName, String message) {
        super("Missing required configuration: " + propertyName + ". " + message);
        this.propertyName = propertyName;
    }

    /**
     * Returns the name of the missing property, if available.
     *
     * @return the property name, or null if not available
     */
    public String getPropertyName() {
        return propertyName;
    }
}
