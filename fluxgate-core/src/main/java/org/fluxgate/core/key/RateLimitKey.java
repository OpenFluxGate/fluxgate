package org.fluxgate.core.key;

import java.util.Objects;

public record RateLimitKey(String key) {

  public static RateLimitKey of(String key) {
    return new RateLimitKey(key);
  }

  public String value() {
    return key;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RateLimitKey(String key1))) return false;
    return Objects.equals(key, key1);
  }

  @Override
  public String toString() {
    return "RateLimitKey{" + "key='" + key + '\'' + '}';
  }
}
