package org.fluxgate.control.autoconfigure;

import java.time.Duration;
import org.fluxgate.core.constants.FluxgateConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for FluxGate Control Support.
 *
 * <p>Example configuration:
 *
 * <pre>
 * fluxgate:
 *   control:
 *     redis:
 *       uri: redis://localhost:6379
 *       channel: fluxgate:rule-reload
 *       timeout: 5s
 *     source: my-admin-app
 *     secret: ${FLUXGATE_CONTROL_SECRET}
 * </pre>
 */
@ConfigurationProperties(prefix = "fluxgate.control")
public class ControlSupportProperties {

  private final RedisProperties redis = new RedisProperties();

  /** Source identifier for notifications (appears in messages). */
  private String source = "fluxgate-control";

  /**
   * Shared secret used to sign rule change notifications with HMAC-SHA256.
   *
   * <p>Required since 0.4: the auto-configured notifier refuses to start without it unless {@link
   * #isAllowUnsigned()} is set. Use the same value as the data plane's {@code
   * fluxgate.reload.pubsub.secret}; the data plane ignores every notification it cannot verify.
   */
  private String secret;

  /**
   * Publish unsigned notifications when no {@link #getSecret()} is set.
   *
   * <p>Development escape hatch, {@code false} by default. Unsigned notifications are only accepted
   * by a data plane that also sets {@code fluxgate.reload.pubsub.allow-unsigned=true}, and that
   * data plane obeys anyone who can {@code PUBLISH} to the channel. Ignored when a secret is
   * configured.
   */
  private boolean allowUnsigned;

  public RedisProperties getRedis() {
    return redis;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getSecret() {
    return secret;
  }

  public void setSecret(String secret) {
    this.secret = secret;
  }

  public boolean isAllowUnsigned() {
    return allowUnsigned;
  }

  public void setAllowUnsigned(boolean allowUnsigned) {
    this.allowUnsigned = allowUnsigned;
  }

  /** Redis configuration for rule change notifications. */
  public static class RedisProperties {

    /** Redis URI (e.g., "redis://localhost:6379"). For cluster, use comma-separated URIs. */
    private String uri = "redis://localhost:6379";

    /**
     * Pub/Sub channel name for rule change notifications.
     *
     * <p>Must match the data plane's {@code fluxgate.reload.pubsub.channel}. Both default to the
     * same shared constant, so a mismatch can only be a deliberate override - and the notifier logs
     * a warning when a publish reaches no subscriber.
     */
    private String channel = FluxgateConstants.Channels.RULE_RELOAD;

    /** Connection timeout. */
    private Duration timeout = Duration.ofSeconds(5);

    public String getUri() {
      return uri;
    }

    public void setUri(String uri) {
      this.uri = uri;
    }

    public String getChannel() {
      return channel;
    }

    public void setChannel(String channel) {
      this.channel = channel;
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout;
    }
  }
}
