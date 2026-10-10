package org.fluxgate.core.resilience;

import java.util.Objects;

/**
 * Thrown by a guarded action to report an outcome that is neither a success nor a failure of the
 * protected resource.
 *
 * <p>Typical examples are client and configuration errors: the action rejected the call before it
 * reached the resource, so the call says nothing about the resource's health. Counting it as a
 * success would reset the consecutive failure count and could close a HALF_OPEN circuit; counting
 * it as a failure would let a misbehaving client open the circuit for everyone.
 *
 * <p>{@link DefaultCircuitBreaker} and {@link NoOpCircuitBreaker} record nothing for such a call,
 * give back any HALF_OPEN trial permit it held, and rethrow this exception unchanged from both
 * {@link CircuitBreaker#execute(String, java.util.function.Supplier)} and {@link
 * CircuitBreaker#executeWithFallback(java.util.function.Supplier, java.util.function.Supplier)}:
 * the fallback is not used. {@link RetryConfig#shouldRetry(Exception)} never retries it. The
 * original exception is available as the {@linkplain #getCause() cause}.
 *
 * <p>A custom {@link CircuitBreaker} implementation that does not know this type treats it like any
 * other {@link RuntimeException}.
 */
public class IgnoredCallException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception carrying the outcome that the circuit breaker must ignore.
   *
   * @param cause the original exception; must not be null
   */
  public IgnoredCallException(Throwable cause) {
    super(
        Objects.requireNonNull(cause, "cause must not be null").getMessage(), cause, false, false);
  }
}
