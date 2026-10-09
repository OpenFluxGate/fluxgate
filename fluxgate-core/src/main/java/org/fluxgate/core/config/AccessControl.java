package org.fluxgate.core.config;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.CidrSet;

/**
 * Immutable allow/deny list checked before the rate limiting algorithm runs.
 *
 * <p>The evaluation order is: deny wins over allow; if neither list matches the request is left to
 * normal rate limiting ({@link Decision#NO_OPINION}).
 *
 * <p>IP-based rules use the full CIDR syntax; key-based rules compare against the <em>resolved</em>
 * key value (e.g. {@code "user:alice"}, {@code "key:abc"}, {@code "ip:192.168.1.1"}).
 *
 * <p>The engine uses {@link #evaluate(String, RateLimitKey, Collection)}, which checks the IP lists
 * against the request's actual client IP regardless of the rule scope, so a denied IP is blocked
 * (and an allowed IP bypasses limiting) for {@code PER_USER} / {@code PER_API_KEY} rules too. Only
 * the highest-priority matching rule's key can grant a key-based bypass.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * AccessControl ac = AccessControl.builder()
 *     .deniedIps(CidrSet.of(List.of("10.0.0.0/8")))
 *     .allowedKeys(Set.of("key:admin-key-abc"))
 *     .build();
 *
 * // DENY beats allow: a key in both deny and allow is always denied
 * ac.evaluate(RateLimitKey.of("ip:10.0.0.5"));       // DENY
 * ac.evaluate(RateLimitKey.of("key:admin-key-abc"));  // ALLOW_BYPASS
 * ac.evaluate(RateLimitKey.of("user:alice"));         // NO_OPINION
 * }</pre>
 *
 * @since 0.4.0
 */
public final class AccessControl {

  /** The decision returned by {@link AccessControl#evaluate(RateLimitKey)}. */
  public enum Decision {
    /**
     * The requester is explicitly allowed and rate limiting should be skipped entirely (bypass).
     */
    ALLOW_BYPASS,
    /** The requester is explicitly denied; the request should be rejected immediately. */
    DENY,
    /** No matching entry was found; continue to normal rate limiting. */
    NO_OPINION
  }

  /** An empty instance that always returns {@link Decision#NO_OPINION}. */
  public static final AccessControl EMPTY = new AccessControl(builder());

  private final CidrSet allowedIps;
  private final CidrSet deniedIps;
  private final Set<String> allowedKeys;
  private final Set<String> deniedKeys;

  private AccessControl(Builder builder) {
    this.allowedIps = builder.allowedIps != null ? builder.allowedIps : CidrSet.EMPTY;
    this.deniedIps = builder.deniedIps != null ? builder.deniedIps : CidrSet.EMPTY;
    this.allowedKeys = Collections.unmodifiableSet(new HashSet<>(builder.allowedKeys));
    this.deniedKeys = Collections.unmodifiableSet(new HashSet<>(builder.deniedKeys));
  }

  /**
   * Creates a new builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  // ===== accessors =====

  /**
   * Returns the set of explicitly allowed IP CIDRs.
   *
   * @return the allowed IP set (never null)
   */
  public CidrSet getAllowedIps() {
    return allowedIps;
  }

  /**
   * Returns the set of explicitly denied IP CIDRs.
   *
   * @return the denied IP set (never null)
   */
  public CidrSet getDeniedIps() {
    return deniedIps;
  }

  /**
   * Returns the set of explicitly allowed resolved key values (e.g. {@code "user:alice"}).
   *
   * @return the allowed key set (never null, unmodifiable)
   */
  public Set<String> getAllowedKeys() {
    return allowedKeys;
  }

  /**
   * Returns the set of explicitly denied resolved key values.
   *
   * @return the denied key set (never null, unmodifiable)
   */
  public Set<String> getDeniedKeys() {
    return deniedKeys;
  }

  // ===== evaluation =====

  /**
   * Evaluates the access control rules for the given resolved rate limit key.
   *
   * <p>Deny always wins: if the key matches a deny rule it is denied even if it also matches an
   * allow rule.
   *
   * @param key the resolved rate limit key (must not be null)
   * @return the access control decision
   */
  public Decision evaluate(RateLimitKey key) {
    Objects.requireNonNull(key, "key must not be null");
    String value = key.value();

    boolean isDenied = false;
    boolean isAllowed = false;

    // IP-based checks (key value starts with "ip:")
    if (value.startsWith("ip:")) {
      String ip = value.substring(3); // strip "ip:" prefix
      if (!deniedIps.isEmpty() && deniedIps.contains(ip)) {
        isDenied = true;
      }
      if (!allowedIps.isEmpty() && allowedIps.contains(ip)) {
        isAllowed = true;
      }
    }

    // key-based checks (exact match on the full resolved value)
    if (deniedKeys.contains(value)) {
      isDenied = true;
    }
    if (allowedKeys.contains(value)) {
      isAllowed = true;
    }

    // deny beats allow
    if (isDenied) {
      return Decision.DENY;
    }
    if (isAllowed) {
      return Decision.ALLOW_BYPASS;
    }
    return Decision.NO_OPINION;
  }

  /**
   * Evaluates the access control rules for a request.
   *
   * <p>The IP lists are checked against {@code clientIp} (the request's actual client address),
   * independent of how the rules resolve their keys.
   *
   * <ul>
   *   <li><b>Deny</b> if the client IP is in the denied IPs, or <em>any</em> key in {@code keys}
   *       (one per matching rule) is a denied key.
   *   <li><b>Allow-bypass</b> if the client IP is in the allowed IPs, or {@code primaryKey} — the
   *       key of the highest-priority matching rule — is an allowed key. A lower-priority rule's
   *       key never grants a bypass: that key may come from caller-controlled input (e.g. an {@code
   *       X-User-Id} header) and would otherwise skip a higher-priority rule such as a {@code
   *       PER_IP} limit.
   * </ul>
   *
   * <p>Deny always wins over allow.
   *
   * @param clientIp the request's client IP (may be null or empty when unknown)
   * @param primaryKey the resolved key of the highest-priority matching rule, or {@code null} when
   *     it could not be resolved (then no key-based bypass is granted)
   * @param keys the resolved keys of all matching rules, checked against the denied keys (must not
   *     be null)
   * @return the access control decision
   * @since 0.4.0
   */
  public Decision evaluate(
      String clientIp, RateLimitKey primaryKey, Collection<RateLimitKey> keys) {
    Objects.requireNonNull(keys, "keys must not be null");

    boolean hasClientIp = clientIp != null && !clientIp.isEmpty();

    boolean isDenied = hasClientIp && !deniedIps.isEmpty() && deniedIps.contains(clientIp);
    if (primaryKey != null && deniedKeys.contains(primaryKey.value())) {
      isDenied = true;
    }
    for (RateLimitKey key : keys) {
      if (deniedKeys.contains(key.value())) {
        isDenied = true;
      }
    }
    if (isDenied) {
      return Decision.DENY;
    }

    boolean isAllowed = hasClientIp && !allowedIps.isEmpty() && allowedIps.contains(clientIp);
    if (primaryKey != null && allowedKeys.contains(primaryKey.value())) {
      isAllowed = true;
    }
    return isAllowed ? Decision.ALLOW_BYPASS : Decision.NO_OPINION;
  }

  /** Returns {@code true} if this instance has no rules configured. */
  public boolean isEmpty() {
    return allowedIps.isEmpty()
        && deniedIps.isEmpty()
        && allowedKeys.isEmpty()
        && deniedKeys.isEmpty();
  }

  // ===== equals / hashCode / toString =====

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AccessControl)) return false;
    AccessControl that = (AccessControl) o;
    return Objects.equals(allowedKeys, that.allowedKeys)
        && Objects.equals(deniedKeys, that.deniedKeys);
    // CidrSet does not override equals; structural equality via key sets is sufficient for reload
  }

  @Override
  public int hashCode() {
    return Objects.hash(allowedKeys, deniedKeys);
  }

  @Override
  public String toString() {
    return "AccessControl{"
        + "allowedIps="
        + allowedIps
        + ", deniedIps="
        + deniedIps
        + ", allowedKeys="
        + allowedKeys
        + ", deniedKeys="
        + deniedKeys
        + '}';
  }

  // ===== builder =====

  /** Builder for {@link AccessControl}. */
  public static final class Builder {
    private CidrSet allowedIps;
    private CidrSet deniedIps;
    private final Set<String> allowedKeys = new HashSet<>();
    private final Set<String> deniedKeys = new HashSet<>();

    private Builder() {}

    /**
     * Sets the allowed IP CIDR set.
     *
     * @param allowedIps the allowed IPs (may be null to mean "none")
     * @return this builder
     */
    public Builder allowedIps(CidrSet allowedIps) {
      this.allowedIps = allowedIps;
      return this;
    }

    /**
     * Sets the denied IP CIDR set.
     *
     * @param deniedIps the denied IPs (may be null to mean "none")
     * @return this builder
     */
    public Builder deniedIps(CidrSet deniedIps) {
      this.deniedIps = deniedIps;
      return this;
    }

    /**
     * Adds an allowed resolved key value (e.g. {@code "user:alice"}).
     *
     * @param key the key value (must not be null)
     * @return this builder
     */
    public Builder addAllowedKey(String key) {
      this.allowedKeys.add(Objects.requireNonNull(key, "key must not be null"));
      return this;
    }

    /**
     * Sets all allowed key values at once.
     *
     * @param keys the key values (may be null)
     * @return this builder
     */
    public Builder allowedKeys(Set<String> keys) {
      this.allowedKeys.clear();
      if (keys != null) {
        this.allowedKeys.addAll(keys);
      }
      return this;
    }

    /**
     * Adds a denied resolved key value (e.g. {@code "key:bad-actor"}).
     *
     * @param key the key value (must not be null)
     * @return this builder
     */
    public Builder addDeniedKey(String key) {
      this.deniedKeys.add(Objects.requireNonNull(key, "key must not be null"));
      return this;
    }

    /**
     * Sets all denied key values at once.
     *
     * @param keys the key values (may be null)
     * @return this builder
     */
    public Builder deniedKeys(Set<String> keys) {
      this.deniedKeys.clear();
      if (keys != null) {
        this.deniedKeys.addAll(keys);
      }
      return this;
    }

    /**
     * Builds the access control instance.
     *
     * @return the access control
     */
    public AccessControl build() {
      return new AccessControl(this);
    }
  }
}
