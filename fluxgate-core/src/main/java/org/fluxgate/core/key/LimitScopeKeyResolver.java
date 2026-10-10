package org.fluxgate.core.key;

import java.util.Objects;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link KeyResolver} implementation that resolves keys based on the rule's {@link
 * LimitScope}.
 *
 * <p>Every resolved key carries a scope prefix so that two scopes can never share a bucket (for
 * example a {@code userId} that happens to be {@code 10.0.0.5} and a real client IP):
 *
 * <table border="1">
 *   <caption>LimitScope to Key Mapping</caption>
 *   <tr><th>LimitScope</th><th>Key Source</th><th>Example Key</th></tr>
 *   <tr><td>GLOBAL</td><td>constant</td><td>"global"</td></tr>
 *   <tr><td>PER_IP</td><td>RequestContext.clientIp</td><td>"ip:192.168.1.100"</td></tr>
 *   <tr><td>PER_USER</td><td>RequestContext.userId</td><td>"user:user-123"</td></tr>
 *   <tr><td>PER_API_KEY</td><td>RequestContext.apiKey</td><td>"key:api-key-abc"</td></tr>
 *   <tr><td>CUSTOM</td><td>attributes.get(keyStrategyId)</td><td>"custom:custom-value"</td></tr>
 * </table>
 *
 * <p>For CUSTOM scope, the resolver looks up the value from RequestContext attributes using the
 * rule's keyStrategyId as the attribute key. Composite values keep their separator, so a value
 * built as {@code ip:10.0.0.1:user:u-1} resolves to {@code custom:ip:10.0.0.1:user:u-1}; prefix
 * each component when you compose a key yourself so the components stay unambiguous.
 *
 * <p>Only the value after the prefix is sanitised and length-limited (see {@link
 * KeyValueSanitizer}), so the prefix is kept even when the value is hashed: a 300-character user id
 * resolves to {@code user:h:<sha256 hex>}.
 *
 * <p><b>Missing key behavior:</b> when the value required by the scope is absent, {@link
 * MissingKeyBehavior#FALLBACK_TO_IP} (the default) falls back to the client IP and the key carries
 * the {@code ip:} prefix of its actual source, while {@link MissingKeyBehavior#REJECT} throws
 * {@link MissingRateLimitKeyException}. Starters expose this as {@code
 * fluxgate.ratelimit.missing-key-behavior}.
 *
 * <p><b>Usage example:</b>
 *
 * <pre>{@code
 * // Create resolver
 * KeyResolver resolver = new LimitScopeKeyResolver();
 *
 * // Use in RuleSetProvider
 * RateLimitRuleSet.builder(ruleSetId)
 *     .keyResolver(resolver)
 *     .rules(rules)
 *     .build();
 * }</pre>
 *
 * @see KeyResolver
 * @see LimitScope
 * @see MissingKeyBehavior
 */
public class LimitScopeKeyResolver implements KeyResolver {

  private static final Logger log = LoggerFactory.getLogger(LimitScopeKeyResolver.class);

  /** Default key used for {@link LimitScope#GLOBAL}; it needs no discriminator. */
  private static final String GLOBAL_KEY = "global";

  /** Key value used when {@link LimitScope#PER_IP} has no client IP to work with. */
  private static final String UNKNOWN_IP = "unknown";

  private static final String PREFIX_IP = "ip:";
  private static final String PREFIX_USER = "user:";
  private static final String PREFIX_API_KEY = "key:";
  private static final String PREFIX_CUSTOM = "custom:";

  private final MissingKeyBehavior missingKeyBehavior;

  /** Creates a resolver that falls back to the client IP when a scoped value is missing. */
  public LimitScopeKeyResolver() {
    this(MissingKeyBehavior.FALLBACK_TO_IP);
  }

  /**
   * Creates a resolver with the given missing key behavior.
   *
   * @param missingKeyBehavior what to do when the value required by the scope is missing (must not
   *     be null)
   */
  public LimitScopeKeyResolver(MissingKeyBehavior missingKeyBehavior) {
    this.missingKeyBehavior =
        Objects.requireNonNull(missingKeyBehavior, "missingKeyBehavior must not be null");
  }

  /**
   * Returns the behavior applied when the value required by the scope is missing.
   *
   * @return the missing key behavior
   */
  public MissingKeyBehavior getMissingKeyBehavior() {
    return missingKeyBehavior;
  }

  @Override
  public RateLimitKey resolve(RequestContext context, RateLimitRule rule) {
    LimitScope scope = rule.getScope();
    if (scope == null) {
      scope = LimitScope.PER_IP; // default
    }

    String keyValue;
    switch (scope) {
      case GLOBAL:
        keyValue = GLOBAL_KEY;
        break;
      case PER_IP:
        keyValue = resolveClientIp(context, rule, scope);
        break;
      case PER_USER:
        keyValue = resolveUserId(context, rule, scope);
        break;
      case PER_API_KEY:
        keyValue = resolveApiKey(context, rule, scope);
        break;
      case CUSTOM:
        keyValue = resolveCustom(context, rule, scope);
        break;
      default:
        keyValue = resolveClientIp(context, rule, scope);
        break;
    }

    log.debug("Resolved key for rule {} with scope {}: {}", rule.getId(), scope, mask(keyValue));

    return RateLimitKey.ofSanitized(keyValue);
  }

  /**
   * Normalises a configured resolved key (for example an allow or deny list entry such as {@code
   * user:alice}) the same way {@link #resolve} builds keys: a known scope prefix ({@code ip:},
   * {@code user:}, {@code key:}, {@code custom:}) is kept and only the value after it is sanitised;
   * any other key is sanitised as a whole, like {@link RateLimitKey#of(String)} does for custom
   * resolvers.
   *
   * <p>Only the four built-in prefixes are recognised. A key built by a custom resolver with {@link
   * RateLimitKey#of(String, String)} and another prefix, such as {@code tenant:}, is therefore
   * matched by its raw form only when the value is clean ({@code tenant:acme}); otherwise configure
   * it in encoded form ({@code tenant:h:a_1:<16 hex>}). A raw {@code tenant:a+1} is sanitised as a
   * whole to {@code h:tenant:a_1:<16 hex>}, the key {@code RateLimitKey.of("tenant:a+1")} produces:
   * the normaliser cannot tell where an unknown prefix ends.
   *
   * <p>A value that is already in encoded form ({@code h:<restricted>:<16 hex>} or {@code h:<64
   * hex>}, with or without a scope prefix, for example {@code user:h:a_1:<16 hex>} copied from a
   * log or metric) is kept as is instead of being encoded again, which makes this method
   * idempotent. That is safe: a raw value that merely looks encoded is itself re-encoded by the
   * resolver, so such an entry only ever matches the identity whose encoding it is.
   *
   * @param resolvedKey the configured key (must not be null)
   * @return the key exactly as a resolver would produce it for the same identity
   * @since 0.4.0
   */
  public static String normalizeResolvedKey(String resolvedKey) {
    Objects.requireNonNull(resolvedKey, "resolvedKey must not be null");
    for (String prefix : new String[] {PREFIX_IP, PREFIX_USER, PREFIX_API_KEY, PREFIX_CUSTOM}) {
      if (resolvedKey.startsWith(prefix) && resolvedKey.length() > prefix.length()) {
        return prefix + normalizeValue(resolvedKey.substring(prefix.length()));
      }
    }
    return normalizeValue(resolvedKey);
  }

  private static String normalizeValue(String value) {
    return KeyValueSanitizer.isEncoded(value) ? value : KeyValueSanitizer.sanitize(value);
  }

  private String resolveClientIp(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String clientIp = context != null ? context.getClientIp() : null;
    if (clientIp == null || clientIp.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("clientIp is null/empty, using '{}' as fallback", UNKNOWN_IP);
      return PREFIX_IP + UNKNOWN_IP;
    }
    return PREFIX_IP + KeyValueSanitizer.sanitize(clientIp);
  }

  private String resolveUserId(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String userId = context != null ? context.getUserId() : null;
    if (userId == null || userId.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("userId is null/empty for PER_USER scope, falling back to clientIp");
      return resolveClientIp(context, rule, scope);
    }
    return PREFIX_USER + KeyValueSanitizer.sanitize(userId);
  }

  private String resolveApiKey(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String apiKey = context != null ? context.getApiKey() : null;
    if (apiKey == null || apiKey.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("apiKey is null/empty for PER_API_KEY scope, falling back to clientIp");
      return resolveClientIp(context, rule, scope);
    }
    return PREFIX_API_KEY + KeyValueSanitizer.sanitize(apiKey);
  }

  private String resolveCustom(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String keyStrategyId = rule.getKeyStrategyId();
    if (keyStrategyId == null || keyStrategyId.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("keyStrategyId is null/empty for CUSTOM scope, falling back to clientIp");
      return resolveClientIp(context, rule, scope);
    }

    Object value = context != null ? context.getAttributes().get(keyStrategyId) : null;
    String stringValue = value != null ? value.toString() : null;
    if (stringValue == null || stringValue.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug(
          "Attribute '{}' is missing or empty for CUSTOM scope, falling back to clientIp",
          keyStrategyId);
      return resolveClientIp(context, rule, scope);
    }

    return PREFIX_CUSTOM + KeyValueSanitizer.sanitize(stringValue);
  }

  /**
   * Masks a key value for logging: user ids and API keys must never reach the logs in clear text.
   *
   * @param keyValue the resolved key value
   * @return the first four characters followed by {@code ***}
   */
  private static String mask(String keyValue) {
    if (keyValue == null || keyValue.length() <= 4) {
      return "***";
    }
    return keyValue.substring(0, 4) + "***";
  }
}
