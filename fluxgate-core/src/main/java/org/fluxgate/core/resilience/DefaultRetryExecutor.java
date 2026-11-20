package org.fluxgate.core.resilience;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link RetryExecutor}.
 *
 * <p>This implementation provides jittered exponential backoff retry with configurable parameters.
 * It logs retry attempts and respects the configured retry policy - in particular {@link
 * RetryConfig#shouldRetry(Exception)}, which by default refuses to retry timeouts because a
 * timed-out consume may already have been applied by the server.
 */
public class DefaultRetryExecutor implements RetryExecutor {

  private static final Logger log = LoggerFactory.getLogger(DefaultRetryExecutor.class);

  private final RetryConfig config;

  /**
   * Creates a new DefaultRetryExecutor with the given configuration.
   *
   * @param config the retry configuration
   */
  public DefaultRetryExecutor(RetryConfig config) {
    this.config = config;
  }

  /**
   * Creates a new DefaultRetryExecutor with default configuration.
   *
   * @return a new DefaultRetryExecutor with default settings
   */
  public static DefaultRetryExecutor withDefaults() {
    return new DefaultRetryExecutor(RetryConfig.defaults());
  }

  @Override
  public <T> T execute(Supplier<T> action) throws Exception {
    return execute("operation", action);
  }

  @Override
  public <T> T execute(String operationName, Supplier<T> action) throws Exception {
    if (!config.isEnabled()) {
      return action.get();
    }

    Exception lastException = null;
    int maxAttempts = config.getMaxAttempts();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return action.get();
      } catch (Exception e) {
        lastException = e;

        // checked first: a non-retryable failure (such as an IgnoredCallException) is rethrown
        // as is, even on the last attempt, and is not reported as an exhausted retry chain
        if (!config.shouldRetry(e)) {
          log.debug(
              "Operation '{}' failed with non-retryable exception: {}",
              operationName,
              e.getClass().getSimpleName());
          throw e;
        }

        if (attempt >= maxAttempts) {
          log.error(
              "Operation '{}' failed after {} attempts. Last error: {}",
              operationName,
              maxAttempts,
              e.getMessage());
          break;
        }

        Duration backoff = config.calculateBackoff(attempt);
        log.warn(
            "Operation '{}' failed (attempt {}/{}). Retrying in {}ms. Error: {}",
            operationName,
            attempt,
            maxAttempts,
            backoff.toMillis(),
            e.getMessage());

        try {
          Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }

    throw lastException;
  }

  @Override
  public void executeVoid(Runnable action) throws Exception {
    executeVoid("operation", action);
  }

  @Override
  public void executeVoid(String operationName, Runnable action) throws Exception {
    execute(
        operationName,
        () -> {
          action.run();
          return null;
        });
  }

  @Override
  public RetryConfig getConfig() {
    return config;
  }
}
