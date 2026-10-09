package org.fluxgate.core.resilience;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link RetryExecutor}.
 *
 * <p>This implementation provides exponential backoff retry with configurable parameters. It logs
 * retry attempts and respects the configured retry policy.
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

        if (attempt >= maxAttempts) {
          log.error(
              "Operation '{}' failed after {} attempts. Last error: {}",
              operationName,
              maxAttempts,
              e.getMessage());
          break;
        }

        if (!config.shouldRetry(e)) {
          log.debug(
              "Operation '{}' failed with non-retryable exception: {}",
              operationName,
              e.getClass().getSimpleName());
          throw e;
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
