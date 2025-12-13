package org.fluxgate.core.exception;

/**
 * Exception thrown when a Lua script execution fails.
 *
 * <p>This exception is thrown when:
 * <ul>
 *   <li>Lua script cannot be loaded</li>
 *   <li>Lua script execution returns an error</li>
 *   <li>Lua script returns an invalid result</li>
 * </ul>
 *
 */
public class ScriptExecutionException extends FluxgateOperationException {

    private final String scriptName;

    /**
     * Constructs a new ScriptExecutionException with the specified message.
     *
     * @param message the detail message
     */
    public ScriptExecutionException(String message) {
        super(message);
        this.scriptName = null;
    }

    /**
     * Constructs a new ScriptExecutionException with the specified message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause, true);
        this.scriptName = null;
    }

    /**
     * Constructs a new ScriptExecutionException with script context.
     *
     * @param message the detail message
     * @param scriptName the name of the script that failed
     * @param cause the cause of the exception
     */
    public ScriptExecutionException(String message, String scriptName, Throwable cause) {
        super(message + " (script: " + scriptName + ")", cause, true);
        this.scriptName = scriptName;
    }

    /**
     * Returns the name of the script that failed, if available.
     *
     * @return the script name, or null if not available
     */
    public String getScriptName() {
        return scriptName;
    }
}
