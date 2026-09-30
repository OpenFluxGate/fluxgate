package org.fluxgate.spring.handler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.RedisUnavailableException;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RateLimiter} that builds its Redis-backed delegate on demand and keeps retrying in the
 * background until Redis answers.
 *
 * <p>Rate limiting is an auxiliary concern, so a Redis outage during a rollout must not turn into
 * an application crash loop. One connection attempt is made when this limiter is created; if it
 * fails the failure is logged and a reconnect is scheduled (starting at the configured interval and
 * backing off to a cap), while calls fail with {@link RedisUnavailableException} so the configured
 * {@code fluxgate.ratelimit.failure-behavior} - or the in-memory fallback of {@link
 * ResilientRateLimiter} - decides what happens to the request. Set {@code
 * fluxgate.redis.fail-fast=true} to restore the eager connect that aborts the boot instead.
 *
 * <p><b>Request threads never connect.</b> Connecting is a blocking socket operation behind a
 * single lock, so letting {@link #tryConsume} do it turned one unreachable Redis into a thread pool
 * exhaustion: every worker queued on the lock for the connect timeout, and a retryable exception on
 * top multiplied that by the retry count. Only the constructor and the background reconnect task
 * ever call {@link #connect()}, which also means request traffic cannot advance the backoff. The
 * reconnect task re-arms itself after every failure and stops only on {@link #close()}.
 *
 * <p>The connection state is published through {@link RedisConnectionState} so the health endpoint
 * can report the limiter as degraded while it is unavailable.
 */
public class LazyRedisRateLimiter implements RateLimiter, RedisConnectionState, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LazyRedisRateLimiter.class);

  /** Matches the {@code user:secret@} credentials of a {@code scheme://user:secret@host} URI. */
  private static final Pattern URI_CREDENTIALS = Pattern.compile("://[^/@\\s]*@");

  /** Matches a {@code password=} or {@code auth=} value carried in a driver failure message. */
  private static final Pattern SECRET_PARAMETER =
      Pattern.compile("(?i)\\b(password|auth)=[^,&;\\s)]*");

  private final Supplier<RedisTokenBucketStore> storeSupplier;
  private final Duration retryInterval;
  private final Duration maxRetryInterval;

  private final AtomicReference<RateLimiter> delegateRef = new AtomicReference<>();
  private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
  private final AtomicInteger failedAttempts = new AtomicInteger();

  private final Object connectLock = new Object();
  private final ScheduledExecutorService reconnectScheduler;
  private ScheduledFuture<?> reconnectTask;
  private volatile boolean closed = false;

  /**
   * Creates a lazily connecting Redis rate limiter and makes a first connection attempt.
   *
   * @param storeSupplier supplier of the token bucket store; invoked again on every retry
   * @param retryInterval delay before the first reconnect attempt
   * @param maxRetryInterval cap for the exponentially growing reconnect delay
   */
  public LazyRedisRateLimiter(
      Supplier<RedisTokenBucketStore> storeSupplier,
      Duration retryInterval,
      Duration maxRetryInterval) {
    this.storeSupplier = Objects.requireNonNull(storeSupplier, "storeSupplier must not be null");
    this.retryInterval =
        retryInterval != null && !retryInterval.isZero() ? retryInterval : Duration.ofSeconds(5);
    this.maxRetryInterval =
        maxRetryInterval != null && !maxRetryInterval.isZero()
            ? maxRetryInterval
            : Duration.ofSeconds(60);
    // Created up front so close() always has something to cancel; the executor does not start its
    // thread until a reconnect is actually scheduled.
    this.reconnectScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fluxgate-redis-reconnect");
              t.setDaemon(true);
              return t;
            });

    connect();
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    RateLimiter delegate = delegateRef.get();
    if (delegate == null) {
      // Deliberately does not connect: see the class Javadoc. The exception is non-retryable so the
      // retry executor hands it straight to the configured degradation.
      throw new RedisUnavailableException(
          "FluxGate Redis rate limiter is not connected yet: " + lastErrorMessage.get());
    }
    return delegate.tryConsume(context, ruleSet, permits);
  }

  @Override
  public boolean isReady() {
    return delegateRef.get() != null;
  }

  @Override
  public String getLastErrorMessage() {
    return lastErrorMessage.get();
  }

  @Override
  public int getFailedAttempts() {
    return failedAttempts.get();
  }

  /**
   * Shuts down the reconnect scheduler and releases the delegate.
   *
   * <p>The Redis connection itself belongs to the configuration bean, which closes it on its own.
   */
  @Override
  public void close() {
    closed = true;
    synchronized (connectLock) {
      cancelReconnect();
      reconnectScheduler.shutdownNow();
      RateLimiter delegate = delegateRef.get();
      if (delegate instanceof AutoCloseable) {
        try {
          ((AutoCloseable) delegate).close();
        } catch (Exception e) {
          log.warn("Error closing the Redis rate limiter delegate", e);
        }
      }
    }
  }

  /**
   * Attempts to build the delegate, returning null when Redis is still unreachable.
   *
   * <p>Called from the constructor and from the reconnect task only, never from a request thread.
   *
   * @return the connected delegate, or null
   */
  private RateLimiter connect() {
    synchronized (connectLock) {
      RateLimiter existing = delegateRef.get();
      if (existing != null || closed) {
        return existing;
      }

      try {
        RedisTokenBucketStore store = storeSupplier.get();
        RateLimiter delegate = new RedisRateLimiter(store);
        delegateRef.set(delegate);
        int attempts = failedAttempts.getAndSet(0);
        lastErrorMessage.set(null);
        cancelReconnect();
        if (attempts > 0) {
          log.info("FluxGate Redis rate limiter connected after {} failed attempt(s)", attempts);
        } else {
          log.info("FluxGate Redis rate limiter connected");
        }
        return delegate;
      } catch (RuntimeException e) {
        int attempts = failedAttempts.incrementAndGet();
        lastErrorMessage.set(rootMessage(e));
        Duration delay = nextDelay(attempts);
        if (attempts == 1) {
          log.error(
              "FluxGate Redis rate limiter could not connect; the application keeps running and "
                  + "the limiter reports DEGRADED. Retrying in {} (set fluxgate.redis.fail-fast"
                  + "=true to fail startup instead). Cause: {}",
              delay,
              lastErrorMessage.get());
        } else {
          log.warn(
              "FluxGate Redis rate limiter still unavailable after {} attempts, retrying in {}: {}",
              attempts,
              delay,
              lastErrorMessage.get());
        }
        scheduleReconnect(delay);
        return null;
      }
    }
  }

  /** Computes the reconnect delay, doubling per failure up to the configured cap. */
  private Duration nextDelay(int attempts) {
    long millis = retryInterval.toMillis();
    for (int i = 1; i < attempts && millis < maxRetryInterval.toMillis(); i++) {
      millis *= 2;
    }
    return Duration.ofMillis(Math.min(millis, maxRetryInterval.toMillis()));
  }

  /** Schedules a single reconnect attempt. Must be called while holding {@link #connectLock}. */
  private void scheduleReconnect(Duration delay) {
    if (closed) {
      return;
    }
    cancelReconnect();
    try {
      reconnectTask =
          reconnectScheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // close() raced with this attempt; there is nothing left to reconnect for.
      log.debug("Reconnect not scheduled because the limiter is shutting down");
    }
  }

  /** Cancels a pending reconnect attempt. Must be called while holding {@link #connectLock}. */
  private void cancelReconnect() {
    if (reconnectTask != null) {
      reconnectTask.cancel(false);
      reconnectTask = null;
    }
  }

  /**
   * Returns the most specific message available for a connection failure, with credentials removed.
   *
   * <p>Driver failures happily echo the URI they were given, which may carry a password. The
   * message ends up in the health endpoint and in every {@link RedisUnavailableException}, so it is
   * masked here rather than at each reader.
   */
  private static String rootMessage(Throwable t) {
    Throwable current = t;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current.getClass().getSimpleName()
        + (current.getMessage() != null ? ": " + mask(current.getMessage()) : "");
  }

  /** Replaces URI credentials and {@code password=} values with a placeholder. */
  private static String mask(String message) {
    String masked = URI_CREDENTIALS.matcher(message).replaceAll("://***@");
    return SECRET_PARAMETER.matcher(masked).replaceAll("$1=***");
  }
}
