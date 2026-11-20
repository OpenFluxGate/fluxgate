package org.fluxgate.control.notify;

import org.fluxgate.core.exception.FluxgateOperationException;

/**
 * Exception thrown when a rule change notification fails to be published.
 *
 * <p>Part of the core exception hierarchy ({@link FluxgateOperationException}); still unchecked, so
 * existing {@code catch (RuntimeException)} handlers keep working.
 */
public class RuleChangeNotificationException extends FluxgateOperationException {

  public RuleChangeNotificationException(String message) {
    super(message);
  }

  public RuleChangeNotificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
