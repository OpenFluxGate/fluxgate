package org.fluxgate.redis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.fluxgate.core.config.RateLimitAlgorithm;
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
 * per-minute band does not drain the per-second band. Across <em>rules</em> a rejected request
 * costs nothing either, by one of two strategies:
 *
 * <ol>
 *   <li><strong>One call.</strong> When every key of every matching rule may go into one script
 *       call - always on a standalone Redis, and in a cluster when all keys hash to one slot - all
 *       rules are evaluated together, all-or-nothing, exactly like the bands of one rule.
 *   <li><strong>Compensation.</strong> Otherwise (a cluster, with rules whose hash tags land in
 *       different slots) the rules are charged one by one; when one rejects, or Redis fails, the
 *       rules already charged are refunded by {@link RedisTokenBucketStore#refund}.
 * </ol>
 *
 * <p>Compensation is not atomic, and does not pretend to be. Between a rule's charge and its refund
 * - one round trip per rule - concurrent requests see that rule a permit lower and may be rejected
 * by it; that errs on the strict side and heals itself with the refund. If the refund itself cannot
 * run (the process dies, or Redis fails between charge and refund) the permit stays spent, which is
 * the pre-0.4 behaviour. A refund also only returns what is still there: a fixed window that rolled
 * over, or a sliding sub-bucket that left the window, between charge and refund has nothing left to
 * give back.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * @see RedisTokenBucketStore
 */
public class RedisRateLimiter implements RateLimiter, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

  /** Prefix shared by every token bucket key. Disjoint from {@code fluxgate:ruleset:}. */
  public static final String BUCKET_KEY_PREFIX = "fluxgate:bucket:";

  /** Appended to the key of a FIXED_WINDOW band, whose counter is a {count, window end} hash. */
  static final String FIXED_WINDOW_KEY_SUFFIX = ":fw";

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
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, ...), so the
    // KeyResolver is asked once per rule - for every rule before anything is charged, so a key
    // that cannot be resolved rejects the request without having drained an earlier rule.
    // ========================================================================

    List<RuleCall> calls = new ArrayList<>(rules.size());
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
      calls.add(new RuleCall(rule, logicalKey, bands, bucketKeys));
    }

    if (calls.isEmpty()) {
      // All matching rules have empty band lists: nothing to enforce.
      log.debug("No rule with bands in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    if (calls.size() > 1) {
      List<String> allKeys = new ArrayList<>();
      for (RuleCall call : calls) {
        allKeys.addAll(call.bucketKeys);
      }
      if (new HashSet<>(allKeys).size() == allKeys.size()
          && tokenBucketStore.canEvaluateAtomically(allKeys)) {
        return record(context, ruleSet, consumeInOneCall(calls, allKeys, permits));
      }
    }
    return record(context, ruleSet, consumeWithCompensation(calls, permits));
  }

  /**
   * Evaluates every rule in a single script call: the bands of all rules are concatenated, so the
   * script's two passes make the whole decision all-or-nothing.
   *
   * <p>The script reports the first rejecting band, or the band with the fewest tokens left, in the
   * concatenated order - the same band rule-by-rule evaluation would have reported, since that
   * stops at the first rejecting rule and keeps the first, strictly most restrictive one.
   */
  private RateLimitResult consumeInOneCall(
      List<RuleCall> calls, List<String> allKeys, long permits) {
    List<RateLimitBand> allBands = new ArrayList<>(allKeys.size());
    for (RuleCall call : calls) {
      allBands.addAll(call.bands);
    }

    BucketState state = tokenBucketStore.tryConsume(allKeys, allBands, permits);

    RuleCall call = calls.get(0);
    int localIndex = state.bandIndex();
    for (RuleCall candidate : calls) {
      call = candidate;
      if (localIndex < candidate.bands.size()) {
        break;
      }
      localIndex -= candidate.bands.size();
    }
    RateLimitBand band = bandAt(call.bands, localIndex);

    if (!state.consumed()) {
      logRejected(call, band, state);
      return rejectedResult(call.key, call.rule, band, state);
    }
    Binding binding = new Binding(call.key, call.rule, band, state);
    logAllowed(binding);
    return allowedResult(binding);
  }

  /**
   * Charges the rules one by one and, when one rejects or Redis fails, refunds the rules already
   * charged. See the class Javadoc for the window in which this is not atomic.
   */
  private RateLimitResult consumeWithCompensation(List<RuleCall> calls, long permits) {
    List<RuleCall> charged = new ArrayList<>(calls.size());
    List<BucketState> chargedStates = new ArrayList<>(calls.size());
    Binding binding = null;

    for (RuleCall call : calls) {
      BucketState state;
      try {
        state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
      } catch (RuntimeException e) {
        refund(charged, chargedStates, permits);
        throw e;
      }
      RateLimitBand bindingBand = bandAt(call.bands, state.bandIndex());

      if (!state.consumed()) {
        // No further rule is charged, and the rules already charged get their permits back.
        refund(charged, chargedStates, permits);
        logRejected(call, bindingBand, state);
        return rejectedResult(call.key, call.rule, bindingBand, state);
      }

      charged.add(call);
      chargedStates.add(state);
      if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
        binding = new Binding(call.key, call.rule, bindingBand, state);
      }
    }

    logAllowed(binding);
    return allowedResult(binding);
  }

  /**
   * Gives back what the already-charged rules took. A failed refund is logged and swallowed: the
   * request's own outcome must not change because compensation could not run, and the permit then
   * simply stays spent.
   */
  private void refund(List<RuleCall> charged, List<BucketState> states, long permits) {
    for (int i = charged.size() - 1; i >= 0; i--) {
      RuleCall call = charged.get(i);
      long consumedAt = states.get(i).redisTimeMicros();
      if (consumedAt <= 0) {
        log.warn(
            "Cannot refund rule {}: the consumption did not report its Redis time",
            call.rule.getId());
        continue;
      }
      try {
        tokenBucketStore.refund(call.bucketKeys, call.bands, permits, consumedAt);
      } catch (RuntimeException e) {
        log.warn(
            "Could not refund {} permit(s) to rule {} after a later rule rejected; they stay"
                + " spent: {}",
            permits,
            call.rule.getId(),
            e.getMessage());
      }
    }
  }

  private static void logRejected(RuleCall call, RateLimitBand band, BucketState state) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Rate limit REJECTED for key {}: rule {}, band {}, wait {} ns",
          mask(call.key),
          call.rule.getId(),
          band != null ? band.getKeyLabel() : "unknown",
          state.nanosToWaitForRefill());
    }
  }

  private static void logAllowed(Binding binding) {
    if (log.isDebugEnabled()) {
      log.debug(
          "Rate limit ALLOWED for key {}: binding rule {}, band {}, {} tokens remaining",
          mask(binding.key),
          binding.rule.getId(),
          binding.band != null ? binding.band.getKeyLabel() : "unknown",
          binding.state.remainingTokens());
    }
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
   * <p>Format: {@code fluxgate:bucket:&#123;ruleSetId:ruleId:keyValue&#125;:bandKeyLabel}, plus
   * {@value #FIXED_WINDOW_KEY_SUFFIX} for a {@link RateLimitAlgorithm#FIXED_WINDOW} band, whose
   * counter is a hash that records its window. The suffix keeps those counters apart from the plain
   * string counters earlier 0.4 builds wrote under the bare name, which then age out on their own
   * expiry instead of failing with {@code WRONGTYPE}.
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
        + band.getKeyLabel()
        + (band.getAlgorithm() == RateLimitAlgorithm.FIXED_WINDOW ? FIXED_WINDOW_KEY_SUFFIX : "");
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

  /** One matching rule with its resolved key and the bucket key of each of its bands. */
  private static final class RuleCall {
    private final RateLimitRule rule;
    private final RateLimitKey key;
    private final List<RateLimitBand> bands;
    private final List<String> bucketKeys;

    private RuleCall(
        RateLimitRule rule, RateLimitKey key, List<RateLimitBand> bands, List<String> bucketKeys) {
      this.rule = rule;
      this.key = key;
      this.bands = bands;
      this.bucketKeys = bucketKeys;
    }
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
