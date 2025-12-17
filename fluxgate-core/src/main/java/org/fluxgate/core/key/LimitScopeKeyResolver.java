package org.fluxgate.core.key;

import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link KeyResolver} implementation that resolves keys based on the rule's {@link
 * LimitScope}.
 *
 * <p>This resolver uses the following mapping:
 *
 * <table border="1">
 *   <caption>LimitScope to Key Mapping</caption>
 *   <tr><th>LimitScope</th><th>Key Source</th><th>Example Key</th></tr>
 *   <tr><td>GLOBAL</td><td>ruleSetId</td><td>"global"</td></tr>
 *   <tr><td>PER_IP</td><td>RequestContext.clientIp</td><td>"192.168.1.100"</td></tr>
 *   <tr><td>PER_USER</td><td>RequestContext.userId</td><td>"user-123"</td></tr>
 *   <tr><td>PER_API_KEY</td><td>RequestContext.apiKey</td><td>"api-key-abc"</td></tr>
 *   <tr><td>CUSTOM</td><td>attributes.get(keyStrategyId)</td><td>"custom-value"</td></tr>
 * </table>
 *
 * <p>For CUSTOM scope, the resolver looks up the value from RequestContext attributes using the
 * rule's keyStrategyId as the attribute key.
 *
 * <p><b>Fallback behavior:</b> If the expected value is null or empty, the resolver falls back to
 * clientIp to prevent null keys.
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
 */
public class LimitScopeKeyResolver implements KeyResolver {

  private static final Logger log = LoggerFactory.getLogger(LimitScopeKeyResolver.class);

  /** Default key used when no specific key can be resolved. */
  private static final String GLOBAL_KEY = "global";

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
        keyValue = resolveClientIp(context);
        break;
      case PER_USER:
        keyValue = resolveUserId(context);
        break;
      case PER_API_KEY:
        keyValue = resolveApiKey(context);
        break;
      case CUSTOM:
        keyValue = resolveCustom(context, rule);
        break;
      default:
        keyValue = resolveClientIp(context);
        break;
    }

    log.debug("Resolved key for rule {} with scope {}: {}", rule.getId(), scope, keyValue);

    return new RateLimitKey(keyValue);
  }

  // TODO: Consider alternative strategies when clientIp is null/empty:
  //  - Option 1: Reject immediately (strict, may block valid requests)
  //  - Option 2: Bypass rate limiting (permissive, potential security risk)
  //  - Option 3: Generate unique key per request (effectively no limit)
  //  - Option 4: Use Resilience4j fallback mechanism
  //  Current behavior: All requests with unknown IP share the same bucket,
  //  which will quickly hit the rate limit. This is a conservative approach.
  private String resolveClientIp(RequestContext context) {
    String clientIp = context.getClientIp();
    if (clientIp == null || clientIp.isEmpty()) {
      log.warn("clientIp is null/empty, using 'unknown' as fallback");
      return "unknown";
    }
    return clientIp;
  }

  private String resolveUserId(RequestContext context) {
    String userId = context.getUserId();
    if (userId == null || userId.isEmpty()) {
      log.warn(
          "userId is null/empty for PER_USER scope, falling back to clientIp: {}",
          context.getClientIp());
      return resolveClientIp(context);
    }
    return userId;
  }

  private String resolveApiKey(RequestContext context) {
    String apiKey = context.getApiKey();
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn(
          "apiKey is null/empty for PER_API_KEY scope, falling back to clientIp: {}",
          context.getClientIp());
      return resolveClientIp(context);
    }
    return apiKey;
  }

  private String resolveCustom(RequestContext context, RateLimitRule rule) {
    String keyStrategyId = rule.getKeyStrategyId();
    if (keyStrategyId == null || keyStrategyId.isEmpty()) {
      log.warn("keyStrategyId is null/empty for CUSTOM scope, falling back to clientIp");
      return resolveClientIp(context);
    }

    Object value = context.getAttributes().get(keyStrategyId);
    if (value == null) {
      log.warn(
          "Attribute '{}' not found in context for CUSTOM scope, falling back to clientIp",
          keyStrategyId);
      return resolveClientIp(context);
    }

    String stringValue = value.toString();
    if (stringValue.isEmpty()) {
      log.warn("Attribute '{}' is empty for CUSTOM scope, falling back to clientIp", keyStrategyId);
      return resolveClientIp(context);
    }

    return stringValue;
  }
}
