package org.fluxgate.core.exception;

/**
 * Exception thrown when a Lua script execution fails.
 *
 * <p>This exception is thrown when:
 *
 * <ul>
 *   <li>Lua script cannot be loaded
 *   <li>Lua script execution returns an error
 *   <li>Lua script returns an invalid result
 * </ul>
 *
 * <p>Not retryable by default: a script that failed part-way may already have consumed tokens, so
 * running it again could charge the same request twice. Failures known to happen before the script
 * ran at all (for example {@code NOSCRIPT}) can opt in through {@link
 * #ScriptExecutionException(String, String, Throwable, boolean)}.
 */
public class ScriptExecutionException extends FluxgateOperationException {

  /** Name of the script that failed to execute. */
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
    super(message, cause, false);
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
    this(message, scriptName, cause, false);
  }

  /**
   * Constructs a new ScriptExecutionException with script context and an explicit retry verdict.
   *
   * <p>Pass {@code retryable = true} only when the script is known not to have run, so a retry
   * cannot consume tokens twice.
   *
   * @param message the detail message
   * @param scriptName the name of the script that failed
   * @param cause the cause of the exception
   * @param retryable whether the operation can safely be retried
   */
  public ScriptExecutionException(
      String message, String scriptName, Throwable cause, boolean retryable) {
    super(message + " (script: " + scriptName + ")", cause, retryable);
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
