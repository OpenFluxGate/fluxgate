package org.fluxgate.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.PathPatternMatcher;
import org.fluxgate.core.match.SimpleAntPathMatcher;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.redis.store.BucketState;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed distributed rate limiter implementation.
 *
 * <p>Properties of this implementation:
 *
 * <ul>
 *   <li>Token buckets live in Redis hashes, one per (rule set, rule, key, band)
 *   <li>The Lua script uses Redis TIME, so all nodes share one clock
 *   <li>Buckets expire through Redis TTL, derived from the band's window and capped by {@code
 *       fluxgate.redis.max-bucket-ttl}
 *   <li>Supports multi-band rules (e.g. 10/sec AND 100/min AND 1000/hour)
 * </ul>
 *
 * <p><strong>Atomicity, honestly.</strong> Every band of <em>one</em> rule is evaluated in a single
 * Lua call, so within a rule the decision is atomic and all-or-nothing: a request rejected by the
 * per-minute band does not drain the per-second band. Across <em>rules</em> there is no atomicity -
 * each rule is a separate round trip, because different rules resolve different keys, which in a
 * cluster live in different hash slots. Evaluation stops at the first rejecting rule, but a rule
 * that already allowed the request keeps the tokens it charged. With N rules the worst case is
 * therefore that a rejected request has consumed a permit from the N-1 rules before it. Order your
 * rules from most to least likely to reject if that matters to you, or use a single rule with
 * several bands.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * @see RedisTokenBucketStore
 */
public class RedisRateLimiter implements RateLimiter, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

  /** Prefix shared by every token bucket key. Disjoint from {@code fluxgate:ruleset:}. */
  public static final String BUCKET_KEY_PREFIX = "fluxgate:bucket:";

  /** Characters Redis treats as glob metacharacters in {@code SCAN MATCH} patterns. */
  private static final String GLOB_METACHARACTERS = "*?[]\\";

  /** Number of leading characters of a key value that survive masking in logs. */
  private static final int MASK_VISIBLE_CHARS = 4;

  private final RedisTokenBucketStore tokenBucketStore;

  /** Rule ids already warned about a window that the bucket TTL cap shortens. */
  private final Set<String> ttlClampWarnedRules = ConcurrentHashMap.newKeySet();

  /**
   * Path pattern matcher used to filter applicable rules via {@link
   * RateLimitRuleSet#getMatchingRules}.
   */
  private final PathPatternMatcher pathMatcher;

  /**
   * Create a new RedisRateLimiter with the given token bucket store.
   *
   * <p>Uses {@link SimpleAntPathMatcher#INSTANCE} as the path pattern matcher.
   *
   * @param tokenBucketStore Redis-backed token bucket store
   */
  public RedisRateLimiter(RedisTokenBucketStore tokenBucketStore) {
    this(tokenBucketStore, SimpleAntPathMatcher.INSTANCE);
  }

  /**
   * Create a new RedisRateLimiter with the given token bucket store and path matcher.
   *
   * <p>The matcher is used to evaluate {@link RateLimitRuleSet#getMatchingRules} on every call, so
   * only rules whose {@link org.fluxgate.core.config.RuleMatcher} matches the incoming request are
   * enforced.
   *
   * @param tokenBucketStore Redis-backed token bucket store
   * @param pathMatcher path pattern matcher for rule filtering
   */
  public RedisRateLimiter(RedisTokenBucketStore tokenBucketStore, PathPatternMatcher pathMatcher) {
    this.tokenBucketStore =
        Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
    this.pathMatcher = Objects.requireNonNull(pathMatcher, "pathMatcher must not be null");
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    return tryConsume(context, ruleSet, permits, pathMatcher);
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits, PathPatternMatcher matcher) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    Objects.requireNonNull(matcher, "matcher must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    List<RateLimitRule> rules = ruleSet.getMatchingRules(context, matcher);
    if (rules.isEmpty()) {
      log.debug("No matching rules in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    // ========================================================================
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution (Fail-Fast)
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, ...),
    // so the KeyResolver is asked once per rule. All bands of one rule then go into a
    // single Lua call, which is atomic for that rule. Evaluation stops at the first
    // rejecting rule; see the class Javadoc for what that does and does not guarantee.
    // ========================================================================

    Binding binding = null;

    for (RateLimitRule rule : rules) {
      // rules from getMatchingRules are already enabled and match the request path/method
      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        continue;
      }

      RateLimitKey logicalKey;
      try {
        logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      } catch (MissingRateLimitKeyException e) {
        return record(context, ruleSet, missingKeyResult(e, rule));
      }
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      warnOnceIfBucketTtlClampsWindow(rule, bands);

      List<String> bucketKeys = new ArrayList<>(bands.size());
      for (RateLimitBand band : bands) {
        bucketKeys.add(buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band));
      }

      BucketState state = tokenBucketStore.tryConsume(bucketKeys, bands, permits);
      RateLimitBand bindingBand = bandAt(bands, state.bandIndex());

      if (!state.consumed()) {
        // Fail fast: no further rule is charged.
        if (log.isDebugEnabled()) {
          log.debug(
              "Rate limit REJECTED for key {}: rule {}, band {}, wait {} ns",
              mask(logicalKey),
              rule.getId(),
              bindingBand != null ? bindingBand.getKeyLabel() : "unknown",
              state.nanosToWaitForRefill());
        }
        return record(context, ruleSet, rejectedResult(logicalKey, rule, bindingBand, state));
      }

      if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
        binding = new Binding(logicalKey, rule, bindingBand, state);
      }
    }

    if (binding == null) {
      // All matching rules have empty band lists: nothing to enforce.
      log.debug("No rule with bands in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Rate limit ALLOWED for key {}: binding rule {}, band {}, {} tokens remaining",
          mask(binding.key),
          binding.rule.getId(),
          binding.band != null ? binding.band.getKeyLabel() : "unknown",
          binding.state.remainingTokens());
    }

    return record(context, ruleSet, allowedResult(binding));
  }

  private static RateLimitResult allowedResult(Binding binding) {
    return RateLimitResult.builder(binding.key)
        .allowed(true)
        .matchedRule(binding.rule)
        .remainingTokens(binding.state.remainingTokens())
        .nanosToWaitForRefill(0L)
        .limit(binding.state.limit())
        .resetTimeMillis(binding.state.resetTimeMillis())
        .policy(binding.rule.getOnLimitExceedPolicy())
        .bandLabel(binding.band != null ? binding.band.getKeyLabel() : null)
        .build();
  }

  private static RateLimitResult rejectedResult(
      RateLimitKey key, RateLimitRule rule, RateLimitBand band, BucketState state) {

    return RateLimitResult.builder(key)
        .allowed(false)
        .matchedRule(rule)
        .remainingTokens(state.remainingTokens())
        .nanosToWaitForRefill(state.nanosToWaitForRefill())
        .limit(state.limit())
        .resetTimeMillis(state.resetTimeMillis())
        .policy(rule.getOnLimitExceedPolicy())
        .bandLabel(band != null ? band.getKeyLabel() : null)
        .build();
  }

  /**
   * Builds the rejected result for a request whose key could not be resolved.
   *
   * <p>A synthetic key is used because a rejected {@link RateLimitResult} requires one, and no real
   * key exists by definition. There is nothing to wait for: the request is missing a header, not
   * tokens.
   */
  private static RateLimitResult missingKeyResult(
      MissingRateLimitKeyException e, RateLimitRule rule) {

    log.debug("Rejecting request because no rate limit key could be resolved: {}", e.getMessage());
    return RateLimitResult.builder(RateLimitKey.of("missing-key:" + rule.getId()))
        .allowed(false)
        .matchedRule(rule)
        .policy(rule.getOnLimitExceedPolicy())
        .remainingTokens(0L)
        .nanosToWaitForRefill(0L)
        .limit(-1L)
        .resetTimeMillis(-1L)
        .build();
  }

  private static RateLimitBand bandAt(List<RateLimitBand> bands, int index) {
    return index >= 0 && index < bands.size() ? bands.get(index) : null;
  }

  /**
   * Warns once per rule when the bucket TTL cap is shorter than one of its windows.
   *
   * <p>The Lua script clamps every TTL to {@code fluxgate.redis.max-bucket-ttl}, which means a
   * bucket of a longer window can expire - and be re-initialised full - before its window is over.
   * That is the deliberate trade against letting forgeable identity keys occupy Redis for weeks,
   * but it changes what the rule enforces, so an operator must be told rather than left to discover
   * it.
   */
  private void warnOnceIfBucketTtlClampsWindow(RateLimitRule rule, List<RateLimitBand> bands) {
    long capSeconds = tokenBucketStore.getMaxBucketTtl().getSeconds();

    for (RateLimitBand band : bands) {
      long ttlSeconds = (long) Math.ceil(band.getWindow().getSeconds() * 1.1);
      if (ttlSeconds <= capSeconds) {
        continue;
      }

      String ruleId = rule.getId() != null ? rule.getId() : "unknown";
      if (ttlClampWarnedRules.add(ruleId)) {
        log.warn(
            "Rule '{}' band '{}' has a {}s window, whose bucket would need a {}s TTL, but "
                + "fluxgate.redis.max-bucket-ttl is {}s: the bucket expires early and the window is "
                + "effectively shortened. Raise max-bucket-ttl for this deployment, or use a scope "
                + "with bounded cardinality for long windows.",
            ruleId,
            band.getKeyLabel(),
            band.getWindow().getSeconds(),
            ttlSeconds,
            capSeconds);
      }
      return;
    }
  }

  private static RateLimitResult record(
      RequestContext context, RateLimitRuleSet ruleSet, RateLimitResult result) {

    RateLimitMetricsRecorder recorder = ruleSet.getMetricsRecorder();
    if (recorder != null) {
      recorder.record(context, result);
    }
    return result;
  }

  /**
   * Build the Redis key for a token bucket.
   *
   * <p>Format: {@code fluxgate:bucket:&#123;ruleSetId:ruleId:keyValue&#125;:bandKeyLabel}
   *
   * <p>Example: {@code fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s}
   *
   * <p>The braces are a Redis Cluster hash tag: only what is inside them is hashed, so every band
   * of one rule and key lands in the same slot and the multi-band Lua script can be atomic. The
   * band segment uses {@link RateLimitBand#getKeyLabel()}, which is derived from the band
   * configuration when no label was set - two unlabelled bands of one rule can no longer collide.
   *
   * @param ruleSetId ID of the rule set
   * @param ruleId ID of the rule
   * @param key Rate limit key (e.g. IP address, API key), already sanitised by the core
   * @param band Rate limit band
   * @return Redis key string
   */
  private static String buildBucketKey(
      String ruleSetId, String ruleId, RateLimitKey key, RateLimitBand band) {

    // ruleSetId and ruleId are sanitised so that Redis hash-tag delimiters (:, {, }) and SCAN glob
    // metacharacters (*, ?, [, ], \) embedded in operator-controlled strings cannot alter the key
    // namespace or the bucket key pattern. key.value() is already sanitised by the core.
    return BUCKET_KEY_PREFIX
        + "{"
        + sanitizeSegment(ruleSetId)
        + ":"
        + sanitizeSegment(ruleId)
        + ":"
        + key.value()
        + "}:"
        + band.getKeyLabel();
  }

  /**
   * Returns the {@code SCAN MATCH} pattern that selects every token bucket of one rule set.
   *
   * <p>{@code ruleSetId} is first sanitized so that embedded glob metacharacters and hash-tag
   * delimiters cannot corrupt the pattern, then any residual metacharacters are backslash-escaped
   * as a second line of defense. The pattern is anchored on {@link #BUCKET_KEY_PREFIX} and can
   * therefore never match {@code fluxgate:ruleset:*} or {@code fluxgate:rulesets}.
   *
   * @param ruleSetId the rule set whose buckets should be matched
   * @return a glob pattern such as {@code fluxgate:bucket:&#123;api-limits:*}
   */
  public static String bucketKeyPattern(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    return BUCKET_KEY_PREFIX + "{" + escapeGlob(sanitizeSegment(ruleSetId)) + ":*";
  }

  /**
   * Replaces characters that are unsafe in a bucket-key segment with underscores.
   *
   * <p>Unsafe characters are: {@code :}, {@code {}, {@code }}, {@code *}, {@code ?}, {@code [},
   * {@code ]}, {@code \}, and any whitespace character. They are replaced rather than rejected so
   * that rule-set / rule IDs created with earlier versions continue to resolve instead of silently
   * routing every request to a different bucket.
   */
  static String sanitizeSegment(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ':'
          || c == '{'
          || c == '}'
          || c == '*'
          || c == '?'
          || c == '['
          || c == ']'
          || c == '\\'
          || Character.isWhitespace(c)) {
        sb.append('_');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String escapeGlob(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (GLOB_METACHARACTERS.indexOf(c) >= 0) {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    return escaped.toString();
  }

  /** Masks a resolved key for logging: the first few characters, then {@code ***}. */
  private static String mask(RateLimitKey key) {
    String value = key.value();
    if (value.length() <= MASK_VISIBLE_CHARS) {
      return "***";
    }
    return value.substring(0, MASK_VISIBLE_CHARS) + "***";
  }

  /**
   * No-op, kept so that the limiter can be used in try-with-resources and as a Spring bean without
   * surprises.
   *
   * <p>This limiter owns neither the {@link RedisTokenBucketStore} nor the Redis connection behind
   * it: both are supplied by the caller and are closed by whoever created them (typically {@code
   * RedisRateLimiterConfig} or the Spring context). Closing them here would shut down a connection
   * other beans still use.
   */
  @Override
  public void close() {
    log.debug("RedisRateLimiter closed; the token bucket store and connection are not owned by it");
  }

  /** The (rule, key, band, state) tuple currently considered the most restrictive. */
  private static final class Binding {
    private final RateLimitKey key;
    private final RateLimitRule rule;
    private final RateLimitBand band;
    private final BucketState state;

    private Binding(RateLimitKey key, RateLimitRule rule, RateLimitBand band, BucketState state) {
      this.key = key;
      this.rule = rule;
      this.band = band;
      this.state = state;
    }
  }
}
