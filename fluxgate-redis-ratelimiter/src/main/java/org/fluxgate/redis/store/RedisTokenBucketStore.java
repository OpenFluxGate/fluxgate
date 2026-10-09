package org.fluxgate.redis.store;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisNoScriptException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fluxgate.core.config.QuotaPeriod;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.redis.RedisRateLimiter;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.script.LuaScriptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed implementation of token bucket storage.
 *
 * <p>Properties of the storage layer:
 *
 * <ol>
 *   <li>The Lua script uses Redis TIME, not {@code System.nanoTime()} - no clock drift across nodes
 *   <li>Time is tracked in microseconds, which stay inside Lua's exact double range
 *   <li>All bands of one rule are evaluated in a single script call: either every band is charged
 *       or none is, so a rejected request never drains a band that would have allowed it
 *   <li>Token state is not modified on rejection; only the bucket TTLs are refreshed
 *   <li>Returns the binding band's capacity and reset time, for HTTP rate limit headers
 *   <li>Every bucket TTL is capped at {@link #DEFAULT_MAX_BUCKET_TTL} (or the configured {@code
 *       fluxgate.redis.max-bucket-ttl}), so a long window cannot keep forged identity keys resident
 *       in Redis for weeks
 * </ol>
 *
 * <p>Each store owns its own {@link LuaScriptRegistry}, so two stores pointing at different Redis
 * deployments in one JVM keep independent script SHAs.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * <p>Supports both Standalone and Cluster deployments via {@link RedisConnectionProvider}. In
 * cluster mode every bucket key of one call must hash to the same slot; {@link RedisRateLimiter}
 * guarantees that with a hash tag.
 */
public class RedisTokenBucketStore {

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStore.class);
  private static final String SCRIPT_NAME = "token_bucket_consume.lua";
  private static final long BUCKET_SCAN_COUNT = 1000L;
  private static final int DELETE_BATCH_SIZE = 500;
  private static final int RESULT_SIZE = 7;
  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;

  /**
   * Maximum value of {@code capacity × window_micros} that Lua can represent exactly.
   *
   * <p>Redis Lua runs on an IEEE-754 double with a 53-bit mantissa, so integers up to 2^53 =
   * 9,007,199,254,740,992 are exact. The token-bucket refill formula multiplies {@code elapsed ×
   * capacity / window_micros}; if {@code capacity × window_micros} exceeds this bound the
   * intermediate product loses precision and the refill amount is wrong. This constant is used by
   * the IEEE-754 guard in {@link #tryConsume(List, List, long)} to reject offending configurations
   * before they reach Redis.
   */
  static final long IEEE_754_MAX_PRODUCT = 9_007_199_254_740_992L; // 2^53

  /**
   * Default upper bound on a bucket TTL.
   *
   * <p>A bucket normally lives one window plus 10%. Without a cap, a rule with a 30 day window
   * keeps one Redis hash per distinct key alive for 33 days - and identity keys are cheap to forge,
   * so the keyspace an attacker can pin down is bounded only by the window. Seven days keeps long
   * windows working while bounding that; raise it deliberately if you rate limit over longer
   * periods with a scope whose cardinality you control.
   */
  public static final Duration DEFAULT_MAX_BUCKET_TTL = Duration.ofDays(7);

  private final AtomicBoolean reloadingLuaScript = new AtomicBoolean(false);
  private final RedisConnectionProvider connectionProvider;
  private final LuaScriptRegistry scripts;
  private final long maxBucketTtlSeconds;

  /**
   * Creates a new RedisTokenBucketStore with the given connection provider.
   *
   * <p>The Lua scripts are read from the classpath and uploaded to the given Redis right away.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   */
  public RedisTokenBucketStore(RedisConnectionProvider connectionProvider) {
    this(connectionProvider, new LuaScriptRegistry());
  }

  /**
   * Creates a new RedisTokenBucketStore with a custom bucket TTL cap.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   * @param maxBucketTtl upper bound on every bucket TTL, or null for {@link
   *     #DEFAULT_MAX_BUCKET_TTL}
   */
  public RedisTokenBucketStore(RedisConnectionProvider connectionProvider, Duration maxBucketTtl) {
    this(connectionProvider, new LuaScriptRegistry(), maxBucketTtl);
  }

  /**
   * Creates a new RedisTokenBucketStore with a pre-built script registry.
   *
   * <p>The registry is uploaded to the given Redis if it does not already hold a SHA.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   * @param scripts the script registry this store should own
   */
  public RedisTokenBucketStore(
      RedisConnectionProvider connectionProvider, LuaScriptRegistry scripts) {
    this(connectionProvider, scripts, DEFAULT_MAX_BUCKET_TTL);
  }

  /**
   * Creates a new RedisTokenBucketStore with a pre-built script registry and a TTL cap.
   *
   * @param connectionProvider the Redis connection provider (standalone or cluster)
   * @param scripts the script registry this store should own
   * @param maxBucketTtl upper bound on every bucket TTL, or null for {@link
   *     #DEFAULT_MAX_BUCKET_TTL}
   * @throws IllegalArgumentException if {@code maxBucketTtl} is below one second
   */
  public RedisTokenBucketStore(
      RedisConnectionProvider connectionProvider,
      LuaScriptRegistry scripts,
      Duration maxBucketTtl) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
    this.scripts = Objects.requireNonNull(scripts, "scripts must not be null");

    Duration effectiveTtl = maxBucketTtl != null ? maxBucketTtl : DEFAULT_MAX_BUCKET_TTL;
    if (effectiveTtl.getSeconds() < 1) {
      throw new IllegalArgumentException("maxBucketTtl must be at least 1 second");
    }
    this.maxBucketTtlSeconds = effectiveTtl.getSeconds();

    if (!scripts.isLoaded()) {
      scripts.loadInto(connectionProvider);
    }
  }

  /**
   * Returns the upper bound applied to every bucket TTL.
   *
   * @return the TTL cap passed to the Lua script
   */
  public Duration getMaxBucketTtl() {
    return Duration.ofSeconds(maxBucketTtlSeconds);
  }

  /**
   * Try to consume permits from a single band's token bucket.
   *
   * @param bucketKey Unique key for the bucket
   * @param band Rate limit band configuration (capacity, window)
   * @param permits Number of permits to consume
   * @return BucketState with consumption result, remaining tokens, wait time, and reset time
   */
  public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits) {
    Objects.requireNonNull(bucketKey, "bucketKey must not be null");
    Objects.requireNonNull(band, "band must not be null");

    return tryConsume(
        Collections.singletonList(bucketKey), Collections.singletonList(band), permits);
  }

  /**
   * Try to consume permits from every band of one rule, atomically.
   *
   * <p>The bands are evaluated in two passes inside a single Lua call: every band is refilled and
   * checked first, and the tokens are taken only when all of them can serve the request. A rejected
   * request therefore leaves all buckets untouched, which is what makes a multi-band rule enforce
   * its nominal limits rather than a systematically lower one.
   *
   * <p>In cluster mode all keys must live in the same hash slot; {@link RedisRateLimiter} pins them
   * with a hash tag over {@code ruleSetId:ruleId:keyValue}.
   *
   * @param bucketKeys one bucket key per band, in the same order as {@code bands}
   * @param bands the bands of a single rule (capacity, window)
   * @param permits Number of permits to consume
   * @return BucketState describing the binding band: the one that rejected, or the one left with
   *     the fewest tokens
   * @throws InvalidRuleConfigException if {@code permits} exceeds the capacity of any band, which
   *     no amount of waiting could satisfy
   * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
   */
  public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
    Objects.requireNonNull(bucketKeys, "bucketKeys must not be null");
    Objects.requireNonNull(bands, "bands must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }
    if (bands.isEmpty()) {
      throw new IllegalArgumentException("bands must not be empty");
    }
    if (bucketKeys.size() != bands.size()) {
      throw new IllegalArgumentException(
          "bucketKeys ("
              + bucketKeys.size()
              + ") and bands ("
              + bands.size()
              + ") must have the same size");
    }

    // Fail before touching Redis: a band whose capacity is below the requested permits can
    // never serve the request, so returning a wait time would send the caller into a retry loop.
    // For TOKEN_BUCKET bands also guard against the IEEE-754 double precision limit: the Lua
    // refill formula computes elapsed × capacity / window_micros; if capacity × window_micros
    // exceeds 2^53 the intermediate product loses precision and the refill amount is wrong.
    for (RateLimitBand band : bands) {
      if (permits > band.getCapacity()) {
        throw new InvalidRuleConfigException(
            "permits ("
                + permits
                + ") exceed the capacity of band '"
                + band.getKeyLabel()
                + "' ("
                + band.getCapacity()
                + ")");
      }
      if (band.getAlgorithm() == RateLimitAlgorithm.TOKEN_BUCKET) {
        long windowMicros = toMicros(band);
        if (band.getCapacity() > 0 && windowMicros > IEEE_754_MAX_PRODUCT / band.getCapacity()) {
          throw new InvalidRuleConfigException(
              "TOKEN_BUCKET band '"
                  + band.getKeyLabel()
                  + "': capacity ("
                  + band.getCapacity()
                  + ") × window_micros ("
                  + windowMicros
                  + ") exceeds 2^53 = "
                  + IEEE_754_MAX_PRODUCT
                  + "; the Lua token-bucket arithmetic would lose precision. "
                  + "Reduce capacity or window, or use SLIDING_WINDOW / FIXED_WINDOW.");
        }
      }
    }

    // KEYS[1..n] = bucketKeys
    // ARGV[1] = permits, ARGV[2] = max_bucket_ttl_seconds, then 5 values per band:
    //   capacity, window_micros, algorithm_code, buckets_or_zero, window_end_micros_or_zero
    String[] keys = bucketKeys.toArray(new String[0]);
    String[] args = new String[2 + 5 * bands.size()];
    args[0] = String.valueOf(permits);
    args[1] = String.valueOf(maxBucketTtlSeconds);
    for (int i = 0; i < bands.size(); i++) {
      RateLimitBand band = bands.get(i);
      int base = 2 + 5 * i;
      args[base] = String.valueOf(band.getCapacity());
      args[base + 1] = String.valueOf(toMicros(band));
      args[base + 2] = String.valueOf(algorithmCode(band.getAlgorithm()));
      args[base + 3] =
          band.getAlgorithm() == RateLimitAlgorithm.SLIDING_WINDOW
              ? String.valueOf(band.getSlidingWindowBuckets())
              : "0";
      args[base + 4] = String.valueOf(computeWindowEndMicros(band));
    }

    List<Long> result = executeScriptWithFallback(keys, args);

    // [allowed, rejecting_band_index, min_remaining, micros_to_wait,
    //  reset_time_millis, limit, binding_band_index]
    if (result == null || result.size() != RESULT_SIZE) {
      throw new ScriptExecutionException(
          "Lua script returned invalid result: " + result, SCRIPT_NAME, null);
    }

    boolean allowed = result.get(0) == 1L;
    long remainingTokens = result.get(2);
    long nanosToWait = result.get(3) * NANOS_PER_MICRO;
    long resetTimeMillis = result.get(4);
    long limit = result.get(5);
    int bandIndex = (int) (result.get(6) - 1L);

    if (allowed) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Token bucket {}: consumed {} permits, {} remaining on band {}, reset at {}",
            keys[0],
            permits,
            remainingTokens,
            bandIndex,
            resetTimeMillis);
      }
      return BucketState.allowed(remainingTokens, resetTimeMillis, limit, bandIndex);
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "Token bucket {}: rejected by band {}, {} remaining, wait {} ns, reset at {}",
          keys[0],
          bandIndex,
          remainingTokens,
          nanosToWait,
          resetTimeMillis);
    }
    return BucketState.rejected(remainingTokens, nanosToWait, resetTimeMillis, limit, bandIndex);
  }

  private static long toMicros(RateLimitBand band) {
    return Math.addExact(
        Math.multiplyExact(band.getWindow().getSeconds(), MICROS_PER_SECOND),
        band.getWindow().getNano() / 1_000L);
  }

  /**
   * Returns the Lua algorithm code for the given {@link RateLimitAlgorithm}.
   *
   * <p>Codes must match the constants defined in {@code token_bucket_consume.lua}: {@code
   * ALG_TOKEN_BUCKET=1}, {@code ALG_SLIDING_WINDOW=2}, {@code ALG_FIXED_WINDOW=3}.
   */
  private static int algorithmCode(RateLimitAlgorithm algorithm) {
    switch (algorithm) {
      case TOKEN_BUCKET:
        return 1;
      case SLIDING_WINDOW:
        return 2;
      case FIXED_WINDOW:
        return 3;
      default:
        throw new InvalidRuleConfigException("Unknown algorithm: " + algorithm);
    }
  }

  /**
   * Returns the absolute window-end timestamp in microseconds for a {@link
   * org.fluxgate.core.config.RateLimitAlgorithm#FIXED_WINDOW} band with a calendar-aligned {@link
   * QuotaPeriod}, or {@code 0} for all other cases.
   *
   * <p>When {@code 0} is passed to the Lua script, the script derives the window end from the
   * current Redis time using {@code floor(now_micros / window_micros) + 1} — suitable for
   * non-calendar-aligned fixed windows. Calendar-aligned windows must be computed in Java because
   * the alignment depends on the configured {@link java.time.ZoneId} (e.g. midnight in {@code
   * America/New_York} is not midnight UTC).
   *
   * @param band the rate limit band
   * @return microseconds since epoch for the end of the current calendar period, or {@code 0}
   */
  private static long computeWindowEndMicros(RateLimitBand band) {
    if (band.getAlgorithm() != RateLimitAlgorithm.FIXED_WINDOW) {
      return 0L;
    }
    QuotaPeriod period = band.getQuotaPeriod();
    if (period == null) {
      return 0L; // non-calendar aligned: Lua derives from now
    }
    ZonedDateTime now = ZonedDateTime.now(band.getZoneId());
    ZonedDateTime periodEnd;
    switch (period) {
      case DAILY:
        periodEnd = now.toLocalDate().plusDays(1).atStartOfDay(band.getZoneId());
        break;
      case WEEKLY:
        // Start of the next ISO week (Monday midnight).
        periodEnd =
            now.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(band.getZoneId());
        break;
      case MONTHLY:
        periodEnd =
            now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(band.getZoneId());
        break;
      default:
        return 0L;
    }
    return periodEnd.toInstant().toEpochMilli() * 1000L;
  }

  /**
   * Executes the Lua script with NOSCRIPT error fallback.
   *
   * <p>This method handles the case where Redis has been restarted and the script cache is lost.
   * When EVALSHA fails with NOSCRIPT error, it falls back to:
   *
   * <ol>
   *   <li>Execute using EVAL (slower but works)
   *   <li>Reload the script into Redis cache for future calls
   * </ol>
   *
   * <p>Errors raised by the script itself (Lua {@code redis.error_reply}) arrive as a Lettuce
   * {@link RedisCommandExecutionException} and are translated into {@link ScriptExecutionException}
   * so that callers see a FluxGate exception rather than a driver one.
   *
   * @param keys the keys for the script
   * @param args the arguments for the script
   * @return the script execution result
   */
  private List<Long> executeScriptWithFallback(String[] keys, String[] args) {
    String sha = scripts.getTokenBucketConsumeSha();
    String script = scripts.getTokenBucketConsumeScript();

    try {
      // Try EVALSHA first (efficient, uses cached script)
      return connectionProvider.evalsha(sha, keys, args);
    } catch (RedisNoScriptException e) {
      // Script not in Redis cache (e.g. Redis was restarted)
      log.warn(
          "Lua script not found in Redis cache (NOSCRIPT). "
              + "Falling back to EVAL and reloading script. SHA: {}",
          sha);

      // Fallback 1 - Execute using EVAL (slower but works immediately)
      List<Long> result;
      try {
        result = connectionProvider.eval(script, keys, args);
      } catch (RedisCommandExecutionException scriptError) {
        throw scriptFailed(scriptError);
      }

      // Fallback 2 - Reload script for future calls (thread-safe with AtomicBoolean)
      reloadScript();

      return result;
    } catch (RedisCommandExecutionException e) {
      throw scriptFailed(e);
    }
  }

  private ScriptExecutionException scriptFailed(RedisCommandExecutionException e) {
    return new ScriptExecutionException(
        "Lua script execution failed: " + e.getMessage(), SCRIPT_NAME, e);
  }

  /**
   * Reloads the Lua script into Redis cache.
   *
   * <p>This is called after a NOSCRIPT error to restore the script cache. Uses AtomicBoolean to
   * prevent multiple concurrent reload attempts.
   */
  private void reloadScript() {
    if (!reloadingLuaScript.compareAndSet(false, true)) {
      log.debug("Lua script is already being reloaded, skipping...");
      return;
    }

    try {
      scripts.loadInto(connectionProvider);
    } catch (RuntimeException e) {
      log.error("Failed to reload Lua script: {}", e.getMessage(), e);
    } finally {
      reloadingLuaScript.set(false);
    }
  }

  /**
   * Gets the Redis connection mode.
   *
   * @return STANDALONE or CLUSTER
   */
  public RedisConnectionProvider.RedisMode getMode() {
    return connectionProvider.getMode();
  }

  /**
   * Deletes all token buckets belonging to the given rule set.
   *
   * <p>This is used when rules are changed to reset rate limit state. The pattern comes from {@link
   * RedisRateLimiter#bucketKeyPattern(String)}, so it can only ever match bucket keys - never the
   * rule set definitions under {@code fluxgate:ruleset:*}.
   *
   * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, and UNLINK where
   * the server supports it so that freeing the keys happens off the event loop.
   *
   * @param ruleSetId the rule set ID to match
   * @return the number of buckets deleted
   */
  public long deleteBucketsByRuleSetId(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String pattern = RedisRateLimiter.bucketKeyPattern(ruleSetId);
    log.debug("Deleting token buckets matching pattern: {}", pattern);

    long deleted = deleteInBatches(connectionProvider.scanKeys(pattern, BUCKET_SCAN_COUNT));
    if (deleted == 0) {
      log.debug("No token buckets found for ruleSetId: {}", ruleSetId);
      return 0;
    }

    log.info("Deleted {} token buckets for ruleSetId: {}", deleted, ruleSetId);
    return deleted;
  }

  /**
   * Deletes all token buckets (full reset).
   *
   * <p>This is used when a full reload is triggered to reset all rate limit state. The pattern is
   * {@code fluxgate:bucket:*}, which is disjoint from {@code fluxgate:ruleset:*} and {@code
   * fluxgate:rulesets} - a full reset can no longer destroy rule set definitions stored in Redis.
   *
   * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces.
   *
   * @return the number of buckets deleted
   */
  public long deleteAllBuckets() {
    String pattern = RedisRateLimiter.BUCKET_KEY_PREFIX + "*";
    log.debug("Deleting all token buckets matching pattern: {}", pattern);

    long deleted = deleteInBatches(connectionProvider.scanKeys(pattern, BUCKET_SCAN_COUNT));
    if (deleted == 0) {
      log.debug("No token buckets found");
      return 0;
    }

    log.info("Deleted {} token buckets (full reset)", deleted);
    return deleted;
  }

  private long deleteInBatches(List<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return 0;
    }

    List<String> all = new ArrayList<>(keys);
    long deleted = 0;
    for (int start = 0; start < all.size(); start += DELETE_BATCH_SIZE) {
      int end = Math.min(start + DELETE_BATCH_SIZE, all.size());
      List<String> batch = all.subList(start, end);
      deleted += connectionProvider.unlink(batch.toArray(new String[0]));
    }
    return deleted;
  }
}
