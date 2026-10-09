package org.fluxgate.control.autoconfigure;

import java.time.Duration;
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
 * </pre>
 */
@ConfigurationProperties(prefix = "fluxgate.control")
public class ControlSupportProperties {

  private final RedisProperties redis = new RedisProperties();

  /** Source identifier for notifications (appears in messages). */
  private String source = "fluxgate-control";

  public RedisProperties getRedis() {
    return redis;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  /** Redis configuration for rule change notifications. */
  public static class RedisProperties {

    /** Redis URI (e.g., "redis://localhost:6379"). For cluster, use comma-separated URIs. */
    private String uri = "redis://localhost:6379";

    /** Pub/Sub channel name for rule change notifications. */
    private String channel = "fluxgate:rule-reload";

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
