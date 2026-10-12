package org.fluxgate.spring.filter;

import java.util.concurrent.Semaphore;

/**
 * The permits that bound how many request threads may be parked waiting for a token refill, shared
 * by the filter and the {@code @RateLimit} aspect so {@code
 * fluxgate.ratelimit.wait-for-refill.max-concurrent-waits} bounds both together.
 *
 * <p>A dedicated type rather than a bare {@link Semaphore} bean: an application that injects a
 * {@code Semaphore} by type - or defines one of its own - is neither handed FluxGate's permits nor
 * broken by an ambiguous injection. Define a bean of this type to size or share the permits
 * yourself.
 *
 * @since 0.4.0
 */
public final class FluxgateWaitPermits {

  private final Semaphore semaphore;

  /**
   * Creates the permits.
   *
   * @param maxConcurrentWaits how many threads may wait at the same time; values below 1 mean 1
   */
  public FluxgateWaitPermits(int maxConcurrentWaits) {
    this.semaphore = new Semaphore(Math.max(1, maxConcurrentWaits));
  }

  /**
   * The underlying semaphore, acquired without blocking by the filter and the aspect.
   *
   * @return the semaphore
   */
  public Semaphore semaphore() {
    return semaphore;
  }

  @Override
  public String toString() {
    return "FluxgateWaitPermits{available=" + semaphore.availablePermits() + '}';
  }
}
