package org.fluxgate.adapter.mongo.converter;

/**
 * Thrown when a stored rule document cannot be turned into a rule without guessing.
 *
 * <p>Typical causes are a field of the wrong BSON type (a capacity stored as a string), a value
 * outside its range, or an enum value this version does not know (an algorithm, zone id or quota
 * period). The repository skips such a document with a WARN instead of failing the whole rule set,
 * and never substitutes a default that would change what the rule limits.
 *
 * @since 0.4.0
 */
public class InvalidRuleDocumentException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong with the document
   */
  public InvalidRuleDocumentException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what is wrong with the document
   * @param cause the underlying failure
   */
  public InvalidRuleDocumentException(String message, Throwable cause) {
    super(message, cause);
  }
}
