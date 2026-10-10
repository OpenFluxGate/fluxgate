package org.fluxgate.core.key;

import java.util.Objects;

/**
 * Represents a rate limit key used to identify a specific rate limit bucket.
 *
 * <p>The key determines which token bucket to use for rate limiting. Different keys result in
 * separate rate limit tracking.
 *
 * <p>Key values are sanitised on construction by {@link KeyValueSanitizer}: the result contains
 * only {@code [A-Za-z0-9._:@-]} and distinct inputs stay distinct (a value that had to be rewritten
 * is marked with {@code h:} and carries a SHA-256 digest). Custom {@link KeyResolver}
 * implementations therefore cannot inject storage metacharacters or unbounded key values. Pass the
 * raw value: sanitising is not idempotent, so pre-sanitised input is rewritten again.
 *
 * <p>Three factories exist:
 *
 * <ul>
 *   <li>{@link #of(String)} sanitises the <em>whole</em> string, so the result is at most 256
 *       characters long and a scope prefix is rewritten together with the value: {@code
 *       of("user:a+1")} becomes {@code h:user:a_1:<16 hex>}.
 *   <li>{@link #of(String, String)} keeps a fixed prefix and sanitises only the value, exactly as
 *       {@link LimitScopeKeyResolver} does: {@code of("user:", "a+1")} becomes {@code
 *       user:h:a_1:<16 hex>}. The <em>value part</em> is at most 256 characters long; the prefix
 *       comes on top of that. Use it in a custom resolver whose keys must match the built-in ones
 *       or configured allow/deny entries. With a prefix other than {@code ip:}, {@code user:},
 *       {@code key:} or {@code custom:}, an allow/deny entry for a value that gets rewritten must
 *       be written in encoded form ({@code tenant:h:a_1:<16 hex>}): see {@link
 *       LimitScopeKeyResolver#normalizeResolvedKey(String)}.
 *   <li>{@link #withPrefix(String, RateLimitKey)} puts a prefix in front of an existing key without
 *       sanitising its value again.
 * </ul>
 */
public final class RateLimitKey {

  /** Longest prefix accepted by {@link #of(String, String)}. */
  public static final int MAX_PREFIX_LENGTH = 64;

  private final String key;

  /**
   * Creates a new RateLimitKey.
   *
   * @param key the key value, must not be null
   * @throws NullPointerException if key is null
   */
  public RateLimitKey(String key) {
    this(KeyValueSanitizer.sanitize(Objects.requireNonNull(key, "key must not be null")), true);
  }

  private RateLimitKey(String sanitizedKey, boolean alreadySanitized) {
    this.key = sanitizedKey;
  }

  /**
   * Creates a key from a value that is already sanitised, such as a scope prefix followed by a
   * {@link KeyValueSanitizer#sanitize(String) sanitised} value. Used by {@link
   * LimitScopeKeyResolver} so that the length limit applies to the value only and the scope prefix
   * survives hashing.
   */
  static RateLimitKey ofSanitized(String sanitizedKey) {
    return new RateLimitKey(Objects.requireNonNull(sanitizedKey, "key must not be null"), true);
  }

  /**
   * Factory method to create a RateLimitKey. The whole string, including any scope prefix, is
   * sanitised; use {@link #of(String, String)} to keep a prefix outside the sanitised value.
   *
   * @param key the raw key value
   * @return a new RateLimitKey instance
   */
  public static RateLimitKey of(String key) {
    return new RateLimitKey(key);
  }

  /**
   * Creates a key from a fixed prefix and a raw value, sanitising only the value: the result is
   * {@code prefix + KeyValueSanitizer.sanitize(rawValue)}, the same shape {@link
   * LimitScopeKeyResolver} produces, so {@code of("user:", userId)} equals the key the built-in
   * resolver resolves for that user. The value part is at most 256 characters long; the prefix
   * comes on top of that.
   *
   * @param prefix the scope prefix, for example {@code "user:"}; a name followed by {@code :}, at
   *     most {@value #MAX_PREFIX_LENGTH} characters from {@code [A-Za-z0-9._:@-]}, not starting
   *     with {@code h:} (must not be null)
   * @param rawValue the raw, unsanitised value (must not be null)
   * @return a new RateLimitKey instance
   * @throws IllegalArgumentException if the prefix is empty, is just {@code :}, does not end with
   *     {@code :}, is too long, contains a disallowed character or starts with the {@code h:}
   *     marker of a rewritten value
   * @since 0.4.0
   */
  public static RateLimitKey of(String prefix, String rawValue) {
    checkPrefix(prefix);
    Objects.requireNonNull(rawValue, "rawValue must not be null");
    return ofSanitized(prefix + KeyValueSanitizer.sanitize(rawValue));
  }

  private static void checkPrefix(String prefix) {
    Objects.requireNonNull(prefix, "prefix must not be null");
    if (prefix.length() < 2
        || prefix.length() > MAX_PREFIX_LENGTH
        || !prefix.endsWith(":")
        || !KeyValueSanitizer.isClean(prefix)
        || prefix.startsWith("h:")) {
      throw new IllegalArgumentException(
          "prefix must be 2 to "
              + MAX_PREFIX_LENGTH
              + " characters from [A-Za-z0-9._:@-], end with ':' and must not start with h:");
    }
  }

  /**
   * Creates a key that puts a fixed prefix in front of an existing key, for example to report the
   * key a request was denied for as {@code denied:<key>}. The key value is already sanitised, so it
   * is <em>not</em> sanitised again: a key starting with the {@code h:} marker keeps its form
   * ({@code denied:h:a_1:<16 hex>}, not {@code denied:h:h:a_1:...}).
   *
   * <p>The result is always {@code prefix + key.value()}, never hashed or shortened, so it is at
   * most {@value #MAX_PREFIX_LENGTH} characters longer than the key. Prefixing with a fixed string
   * is injective: distinct keys always give distinct results for the same prefix. A result can
   * still equal a key built another way with the same value, as {@code withPrefix("denied:",
   * of("x"))} equals {@code of("denied:x")}, just as {@code of("user:", "a")} equals {@code
   * of("user:a")}. The length is bounded by the code that builds the key, not by request data: each
   * call adds at most {@value #MAX_PREFIX_LENGTH} characters to a key that was bounded when it was
   * created.
   *
   * @param prefix the prefix, with the same rules as for {@link #of(String, String)}
   * @param key the existing key (must not be null)
   * @return a new RateLimitKey instance
   * @throws IllegalArgumentException if the prefix is invalid, see {@link #of(String, String)}
   * @since 0.4.0
   */
  public static RateLimitKey withPrefix(String prefix, RateLimitKey key) {
    checkPrefix(prefix);
    String value = Objects.requireNonNull(key, "key must not be null").value();
    return ofSanitized(prefix + value);
  }

  /**
   * Returns the key value.
   *
   * @return the key string
   * @deprecated since 0.4.0 and scheduled for removal; use {@link #value()} instead
   */
  @Deprecated(since = "0.4.0", forRemoval = true)
  public String key() {
    return key;
  }

  /**
   * Returns the key value.
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
