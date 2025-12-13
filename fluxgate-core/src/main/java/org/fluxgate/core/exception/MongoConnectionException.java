package org.fluxgate.core.exception;

/**
 * Exception thrown when a connection to MongoDB fails.
 *
 * <p>This exception is thrown when:
 *
 * <ul>
 *   <li>Initial connection to MongoDB cannot be established
 *   <li>Connection is lost during operation
 *   <li>MongoDB replica set primary is unavailable
 * </ul>
 */
public class MongoConnectionException extends FluxgateConnectionException {

  private final String mongoUri;

  /**
   * Constructs a new MongoConnectionException with the specified message.
   *
   * @param message the detail message
   */
  public MongoConnectionException(String message) {
    super(message);
    this.mongoUri = null;
  }

  /**
   * Constructs a new MongoConnectionException with the specified message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public MongoConnectionException(String message, Throwable cause) {
    super(message, cause);
    this.mongoUri = null;
  }

  /**
   * Constructs a new MongoConnectionException with the specified message, URI, and cause.
   *
   * @param message the detail message
   * @param mongoUri the MongoDB URI that failed to connect (may be masked for security)
   * @param cause the cause of the exception
   */
  public MongoConnectionException(String message, String mongoUri, Throwable cause) {
    super(message + " (uri: " + mongoUri + ")", cause);
    this.mongoUri = mongoUri;
  }

  /**
   * Returns the MongoDB URI that failed to connect, if available.
   *
   * @return the MongoDB URI (may be masked for security), or null if not available
   */
  public String getMongoUri() {
    return mongoUri;
  }
}
