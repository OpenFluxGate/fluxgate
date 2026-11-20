package org.fluxgate.control.notify;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for rule change notification outcomes.
 *
 * <p>Redis Pub/Sub is at-most-once and a notification is published after the rule has already been
 * stored, so a publish that fails for good leaves the data plane on the old rules. That used to be
 * a single ERROR line; this bean makes it a number an operator can alert on.
 *
 * <p>Expose it however you monitor the control plane, for example as a Micrometer gauge over {@link
 * #getFailedNotifications()}, and pair it with the data plane's polling backstop ({@code
 * fluxgate.reload.pubsub.backstop-polling-interval}) so a lost notification still converges.
 */
public class RuleChangeNotifierMetrics {

  private final AtomicLong publishedNotifications = new AtomicLong();
  private final AtomicLong retriedNotifications = new AtomicLong();
  private final AtomicLong failedNotifications = new AtomicLong();
  private final AtomicLong noReceiverNotifications = new AtomicLong();

  /** Records a notification that was published successfully. */
  public void recordPublished() {
    publishedNotifications.incrementAndGet();
  }

  /** Records one retry attempt of a notification. */
  public void recordRetry() {
    retriedNotifications.incrementAndGet();
  }

  /**
   * Records a notification that Redis accepted but no subscriber received.
   *
   * @since 0.4.0
   */
  public void recordNoReceivers() {
    noReceiverNotifications.incrementAndGet();
  }

  /** Records a notification that was given up on after exhausting all attempts. */
  public void recordFailed() {
    failedNotifications.incrementAndGet();
  }

  /**
   * Returns how many notifications were published and reached at least one subscriber (or were
   * published through a notifier that cannot report receivers).
   *
   * @return the published count
   */
  public long getPublishedNotifications() {
    return publishedNotifications.get();
  }

  /**
   * Returns how many retry attempts were made.
   *
   * @return the retry count
   */
  public long getRetriedNotifications() {
    return retriedNotifications.get();
  }

  /**
   * Returns how many notifications were lost after all attempts failed.
   *
   * <p>Anything above zero means at least one rule change was not broadcast: those instances keep
   * serving the previous rules until their cache expires or a backstop poll picks the change up.
   *
   * @return the failure count
   */
  public long getFailedNotifications() {
    return failedNotifications.get();
  }

  /**
   * Returns how many notifications were published while no subscriber was listening.
   *
   * <p>Anything above zero usually means the control plane's and the data plane's channel names
   * disagree, or that no data plane instance was running.
   *
   * @return the count of notifications with zero receivers
   * @since 0.4.0
   */
  public long getNoReceiverNotifications() {
    return noReceiverNotifications.get();
  }

  @Override
  public String toString() {
    return "RuleChangeNotifierMetrics{published="
        + publishedNotifications.get()
        + ", retried="
        + retriedNotifications.get()
        + ", failed="
        + failedNotifications.get()
        + ", noReceivers="
        + noReceiverNotifications.get()
        + '}';
  }
}
