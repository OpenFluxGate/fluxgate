package org.fluxgate.control.notify;

/** Exception thrown when a rule change notification fails to be published. */
public class RuleChangeNotificationException extends RuntimeException {

  public RuleChangeNotificationException(String message) {
    super(message);
  }

  public RuleChangeNotificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
