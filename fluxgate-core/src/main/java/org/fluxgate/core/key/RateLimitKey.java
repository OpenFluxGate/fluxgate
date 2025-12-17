package org.fluxgate.core.key;

import java.util.Objects;

/**
 * Represents a rate limit key used to identify a specific rate limit bucket.
 *
 * <p>The key determines which token bucket to use for rate limiting. Different keys result in
 * separate rate limit tracking.
 */
public final class RateLimitKey {

  private final String key;

  /**
   * Creates a new RateLimitKey.
   *
   * @param key the key value, must not be null
   * @throws NullPointerException if key is null
   */
  public RateLimitKey(String key) {
    this.key = Objects.requireNonNull(key, "key must not be null");
  }

  /**
   * Factory method to create a RateLimitKey.
   *
   * @param key the key value
   * @return a new RateLimitKey instance
   */
  public static RateLimitKey of(String key) {
    return new RateLimitKey(key);
  }

  /**
   * Returns the key value.
   *
   * @return the key string
   */
  public String key() {
    return key;
  }

  /**
   * Returns the key value. Alias for {@link #key()}.
   *
   * @return the key string
   */
  public String value() {
    return key;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitKey)) return false;
    RateLimitKey that = (RateLimitKey) o;
    return Objects.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(key);
  }

  @Override
  public String toString() {
    return "RateLimitKey{" + "key='" + key + '\'' + '}';
  }
}
