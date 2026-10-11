package org.fluxgate.redis.store;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.cluster.SlotHash;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fluxgate.core.config.QuotaPeriod;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.exception.FluxgateTimeoutException;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.RedisConnectionException;
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
 *   <li>Token state is not modified on rejection. TOKEN_BUCKET and SLIDING_WINDOW bucket TTLs are
 *       refreshed with {@code EXPIRE}; a FIXED_WINDOW counter keeps its absolute expiry, and only
 *       one left without a TTL gets a {@code PEXPIREAT} at its window end
 *   <li>Returns the binding band's capacity and reset time, for HTTP rate limit headers
 *   <li>Every TOKEN_BUCKET and SLIDING_WINDOW bucket TTL is capped at {@link
 *       #DEFAULT_MAX_BUCKET_TTL} (or the configured {@code fluxgate.redis.max-bucket-ttl}), so a
 *       long window cannot keep forged identity keys resident in Redis for weeks. FIXED_WINDOW
 *       counters are exempt: they expire exactly at the end of their window, which the cap must not
 *       cut short (a monthly quota would otherwise reset weekly)
 *   <li>Each decision reports the Redis time it was taken at, so {@link #refund} can give a
 *       consumption back exactly - the compensation step of cross-rule evaluation
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
  private static final String POLICY_KEY_PREFIX = "fluxgate:policy:";
  private static final String SCRIPT_NAME = "token_bucket_consume.lua";
  private static final String REFUND_SCRIPT_NAME = "token_bucket_refund.lua";
  private static final long BUCKET_SCAN_COUNT = 1000L;
  private static final int DELETE_BATCH_SIZE = 500;
  private static final int RESULT_SIZE = 8;
  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long NANOS_PER_MICRO = 1_000L;

  /** Trailing ARGV value that puts the consume script into check-only mode. */
  private static final String CHECK_ONLY_FLAG = "1";

  /** Shortest window, and shortest sliding sub-bucket, the Lua script accepts: 1 ms. */
  private static final long MIN_WINDOW_MICROS = 1_000L;

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

  /** Shortest pause between two script reloads; doubled after every failed reload. */
  private static final long MIN_RELOAD_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

  /** Longest pause between two script reloads while they keep failing. */
  private static final long MAX_RELOAD_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

  private final AtomicBoolean reloadingLuaScript = new AtomicBoolean(false);

  /** {@code System.nanoTime()} of the last reload attempt; valid once {@link #reloadAttempted}. */
  private volatile long lastReloadAttemptNanos;

  private volatile boolean reloadAttempted;

  /** Pause required after the last reload attempt before the next one may run. */
  private volatile long reloadIntervalNanos = MIN_RELOAD_INTERVAL_NANOS;

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
   * @param maxBucketTtl upper bound on every TOKEN_BUCKET / SLIDING_WINDOW bucket TTL (FIXED_WINDOW
   *     counters are exempt), or null for {@link #DEFAULT_MAX_BUCKET_TTL}
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
   * @param maxBucketTtl upper bound on every TOKEN_BUCKET / SLIDING_WINDOW bucket TTL (FIXED_WINDOW
   *     counters are exempt), or null for {@link #DEFAULT_MAX_BUCKET_TTL}
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
   * Returns the upper bound applied to every TOKEN_BUCKET and SLIDING_WINDOW bucket TTL.
   *
   * @return the TTL cap passed to the Lua script; FIXED_WINDOW counters are not subject to it
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
   *     no amount of waiting could satisfy, or a band's window (or sliding sub-bucket) is shorter
   *     than 1 ms
   * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
   */
  public BucketState tryConsume(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
    return evaluate(
        bucketKeys,
        bands,
        permits,
        false,
        Collections.nCopies(bands.size(), 0L),
        Collections.nCopies(bands.size(), ""));
  }

  /**
   * Tells whether {@link #tryConsume(List, List, long)} would admit the request, without consuming
   * anything.
   *
   * <p>The script takes the same decision as for a consumption but writes no token, counter or
   * sub-bucket (a rejection only refreshes TTLs, as it always does). {@link RedisRateLimiter} uses
   * it to learn how long rules it did not charge would make a rejected request wait, so the
   * reported Retry-After is the longest one.
   *
   * @param bucketKeys one bucket key per band, in the same order as {@code bands}
   * @param bands the bands of a single rule (capacity, window)
   * @param permits number of permits the request would consume
   * @return the decision a consumption would take; on allow nothing has been consumed
   * @throws InvalidRuleConfigException as for {@link #tryConsume(List, List, long)}
   * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
   */
  public BucketState check(List<String> bucketKeys, List<RateLimitBand> bands, long permits) {
    return evaluate(
        bucketKeys,
        bands,
        permits,
        true,
        Collections.nCopies(bands.size(), 0L),
        Collections.nCopies(bands.size(), ""));
  }

  public BucketState tryConsume(
      List<String> keys, List<RateLimitBand> bands, long permits, long revision) {
    return tryConsume(keys, bands, permits, revision, "");
  }

  public BucketState tryConsume(
      List<String> keys, List<RateLimitBand> bands, long permits, long revision, String epoch) {
    return tryConsumeFenced(
        keys,
        bands,
        permits,
        Collections.nCopies(bands.size(), revision),
        Collections.nCopies(bands.size(), epoch));
  }

  /** Per-band revisions preserve single-call atomicity when several rules share a slot. */
  public BucketState tryConsumeFenced(
      List<String> keys,
      List<RateLimitBand> bands,
      long permits,
      List<Long> revisions,
      List<String> epochs) {
    return evaluate(keys, bands, permits, false, revisions, epochs);
  }

  public BucketState checkFenced(
      List<String> keys, List<RateLimitBand> bands, long permits, long revision, String epoch) {
    return evaluate(
        keys,
        bands,
        permits,
        true,
        Collections.nCopies(bands.size(), revision),
        Collections.nCopies(bands.size(), epoch));
  }

  private BucketState evaluate(
      List<String> bucketKeys,
      List<RateLimitBand> bands,
      long permits,
      boolean checkOnly,
      List<Long> revisions,
      List<String> epochs) {
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
      validateWindow(band);
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

    // n buckets + n metadata + n rule/epoch fences. No legacy Java caller bypasses fences.
    int count = bands.size();
    if (revisions.size() != count || epochs.size() != count) {
      throw new IllegalArgumentException("one revision and epoch required per band");
    }
    String[] keys = new String[3 * count];
    String[] args = Arrays.copyOf(scriptArgs(permits, maxBucketTtlSeconds, bands), 4 + 6 * count);
    java.util.Map<String, Long> fenceRevisions = new java.util.HashMap<>();
    for (int i = 0; i < count; i++) {
      long revision = revisions.get(i);
      if (revision < 0 || revision > 9_007_199_254_740_991L) {
        throw new IllegalArgumentException(
            "revision must be in the exact nonnegative Lua integer range");
      }
      keys[i] = bucketKeys.get(i);
      keys[count + i] = metadataKey(keys[i]);
      String epoch = Objects.requireNonNull(epochs.get(i), "epoch");
      // Epoch changes reset buckets, never the monotonic rule fence.
      keys[2 * count + i] = revisionKey(keys[i]);
      Long previous = fenceRevisions.putIfAbsent(keys[2 * count + i], revision);
      if (previous != null && previous.longValue() != revision) {
        throw new IllegalArgumentException("inconsistent revisions for the same rule fence");
      }
      args[2 + 5 * count + i] = String.valueOf(revision);
    }
    args[args.length - 2] = checkOnly ? CHECK_ONLY_FLAG : "0";
    args[args.length - 1] = "FENCED";

    List<Long> result =
        executeScriptWithFallback(
            scripts.getTokenBucketConsumeSha(),
            scripts.getTokenBucketConsumeScript(),
            SCRIPT_NAME,
            keys,
            args);

    // [allowed, rejecting_band_index, min_remaining, micros_to_wait,
    //  reset_time_millis, limit, binding_band_index, redis_time_micros]
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
    long redisTimeMicros = result.get(7);

    if (allowed) {
      if (log.isDebugEnabled() && !checkOnly) {
        log.debug(
            "Token bucket {}: consumed {} permits, {} remaining on band {}, reset at {}",
            keys[0],
            permits,
            remainingTokens,
            bandIndex,
            resetTimeMillis);
      }
      return new BucketState(
          true, remainingTokens, 0L, resetTimeMillis, limit, bandIndex, redisTimeMicros);
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
    return new BucketState(
        false, remainingTokens, nanosToWait, resetTimeMillis, limit, bandIndex, redisTimeMicros);
  }

  /**
   * Gives back the permits that an earlier, successful {@link #tryConsume(List, List, long)} took
   * from the bands of one rule.
   *
   * <p>This is the compensation step of cross-rule evaluation: when rules cannot be evaluated in a
   * single script call (see {@link #canEvaluateAtomically(Collection)}), {@link RedisRateLimiter}
   * charges them one by one and refunds the rules it already charged once a later rule rejects.
   *
   * <p>Each band gets back at most {@code permits}, and only from the state the consumption wrote:
   * a token bucket is capped at its capacity; a sliding window decrements the sub-bucket that was
   * current at {@code consumedAtMicros}; a fixed window decrements its counter only while it still
   * counts the window that was charged. A bucket that has expired, rolled over or never existed is
   * left alone - there is nothing of this request left in it.
   *
   * @param bucketKeys the keys that were passed to the consumption, in the same order
   * @param bands the bands that were passed to the consumption, in the same order
   * @param permits the permits the consumption took
   * @param consumedAtMicros {@link BucketState#redisTimeMicros()} of the consumption
   * @return the permits actually given back to each band, in band order
   * @throws IllegalArgumentException if the arguments are inconsistent or {@code consumedAtMicros}
   *     is unknown
   * @throws ScriptExecutionException if the Lua script fails or returns an unexpected result
   */
  public List<Long> refund(
      List<String> bucketKeys, List<RateLimitBand> bands, long permits, long consumedAtMicros) {
    Objects.requireNonNull(bucketKeys, "bucketKeys must not be null");
    Objects.requireNonNull(bands, "bands must not be null");
    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }
    if (consumedAtMicros <= 0) {
      throw new IllegalArgumentException("consumedAtMicros must be > 0");
    }
    if (bands.isEmpty() || bucketKeys.size() != bands.size()) {
      throw new IllegalArgumentException(
          "bucketKeys (" + bucketKeys.size() + ") and bands (" + bands.size() + ") must match");
    }

    int count = bands.size();
    String[] refundKeys = new String[2 * count];
    for (int i = 0; i < count; i++) {
      refundKeys[i] = bucketKeys.get(i);
      refundKeys[count + i] = metadataKey(refundKeys[i]);
    }
    String[] args = Arrays.copyOf(scriptArgs(permits, consumedAtMicros, bands), 3 + 5 * count);
    args[args.length - 1] = "FENCED";
    List<Long> result =
        executeScriptWithFallback(
            scripts.getTokenBucketRefundSha(),
            scripts.getTokenBucketRefundScript(),
            REFUND_SCRIPT_NAME,
            refundKeys,
            args);
    if (result == null || result.size() != bands.size()) {
      throw new ScriptExecutionException(
          "Lua refund script returned invalid result: " + result, REFUND_SCRIPT_NAME, null);
    }
    if (log.isDebugEnabled()) {
      log.debug("Refunded {} permits to {}: {}", permits, bucketKeys.get(0), result);
    }
    return result;
  }

  /**
   * Tells whether the given bucket keys can all be passed to one script call.
   *
   * <p>A standalone Redis runs any script over any keys. In a cluster a script may only touch keys
   * of one hash slot, so this is true only when every key hashes to the same slot.
   *
   * @param bucketKeys the keys of every band of every rule to evaluate
   * @return true if one {@link #tryConsume(List, List, long)} call may cover all of them
   */
  public boolean canEvaluateAtomically(Collection<String> bucketKeys) {
    Objects.requireNonNull(bucketKeys, "bucketKeys must not be null");
    if (connectionProvider.getMode() != RedisConnectionProvider.RedisMode.CLUSTER) {
      return true;
    }
    int slot = -1;
    for (String key : bucketKeys) {
      int keySlot = SlotHash.getSlot(key);
      if (slot != -1 && keySlot != slot) {
        return false;
      }
      slot = keySlot;
    }
    return true;
  }

  /**
   * Builds the ARGV shared by the consume and refund scripts: two leading values, then 5 per band
   * (capacity, window_micros, algorithm_code, buckets_or_zero, window_end_micros_or_zero).
   */
  private static String[] scriptArgs(long first, long second, List<RateLimitBand> bands) {
    String[] args = new String[2 + 5 * bands.size()];
    args[0] = String.valueOf(first);
    args[1] = String.valueOf(second);
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
    return args;
  }

  /**
   * Refuses windows the Lua script cannot represent: below 1 ms the arithmetic divides by zero or
   * leaves the exact integer range, and a sliding sub-bucket below 1 ms has the same problem.
   */
  private static void validateWindow(RateLimitBand band) {
    long windowMicros = toMicros(band);
    if (windowMicros < MIN_WINDOW_MICROS) {
      throw new InvalidRuleConfigException(
          "band '"
              + band.getKeyLabel()
              + "': window must be at least 1 ms, but was "
              + band.getWindow());
    }
    if (band.getAlgorithm() == RateLimitAlgorithm.SLIDING_WINDOW
        && windowMicros / band.getSlidingWindowBuckets() < MIN_WINDOW_MICROS) {
      throw new InvalidRuleConfigException(
          "SLIDING_WINDOW band '"
              + band.getKeyLabel()
              + "': each of the "
              + band.getSlidingWindowBuckets()
              + " sub-buckets of "
              + band.getWindow()
              + " is shorter than 1 ms; use fewer sub-buckets or a longer window");
    }
  }

  /** Dedicated metadata namespace keeps arbitrary band labels from colliding with bucket state. */
  public static String metadataKey(String bucketKey) {
    String prefix = POLICY_KEY_PREFIX;
    if (hashTagEnd(bucketKey) >= 0) return prefix + bucketKey;
    // A tag must precede the original key: empty/malformed braces in a raw key otherwise
    // prevent Redis from interpreting an appended tag. Preserve the original bucket identity.
    return prefix + "{" + RawKeySlotTags.TAGS[SlotHash.getSlot(bucketKey)] + "}:" + bucketKey;
  }

  /** The legacy fence follows the rule/key hash tag, never the band label. */
  public static String revisionKey(String bucketKey) {
    int close = hashTagEnd(bucketKey);
    String root = close >= 0 ? bucketKey.substring(0, close + 1) : bucketKey;
    return metadataKey(root) + ":revision";
  }

  private static int hashTagEnd(String key) {
    int open = key.indexOf('{');
    if (open < 0) return -1;
    int close = key.indexOf('}', open + 1);
    return close > open + 1 ? close : -1;
  }

  /** Lazily built, bounded table shared by all stores; raw-key calls need no per-call search. */
  private static final class RawKeySlotTags {
    private static final String[] TAGS = build();

    private static String[] build() {
      String[] tags = new String[16384];
      int remaining = tags.length;
      for (int candidate = 0; remaining > 0 && candidate < 1_000_000; candidate++) {
        String tag = "raw" + candidate;
        int slot = SlotHash.getSlot(tag);
        if (tags[slot] == null) {
          tags[slot] = tag;
          remaining--;
        }
      }
      if (remaining != 0) throw new IllegalStateException("Unable to construct Redis slot tags");
      return tags;
    }
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
   *   <li>Reload the script into Redis cache for future calls - at most once at a time, and not
   *       again until a pause has passed (one second, doubled after every failed reload up to a
   *       minute), so a node that keeps answering NOSCRIPT, or a reload that keeps failing, does
   *       not turn every request into extra SCRIPT LOAD round trips
   * </ol>
   *
   * <p>Errors raised by the script itself (Lua {@code redis.error_reply}) arrive as a Lettuce
   * {@link RedisCommandExecutionException} and are translated into {@link
   * ScriptExecutionException}. A command timeout becomes a {@link FluxgateTimeoutException}, and
   * any other driver failure (connection refused or closed, cluster routing) a FluxGate {@link
   * RedisConnectionException}, so that callers only ever see FluxGate exceptions.
   *
   * @param sha the SHA the script is expected to have in Redis
   * @param script the script body, for the EVAL fallback
   * @param scriptName the script name, for error reporting
   * @param keys the keys for the script
   * @param args the arguments for the script
   * @return the script execution result
   */
  private List<Long> executeScriptWithFallback(
      String sha, String script, String scriptName, String[] keys, String[] args) {
    try {
      // Try EVALSHA first (efficient, uses cached script)
      return connectionProvider.evalsha(sha, keys, args);
    } catch (RedisNoScriptException e) {
      // Script not in Redis cache (e.g. Redis was restarted)
      log.warn(
          "Lua script {} not found in Redis cache (NOSCRIPT). "
              + "Falling back to EVAL and reloading scripts. SHA: {}",
          scriptName,
          sha);

      // Fallback 1 - Execute using EVAL (slower but works immediately)
      List<Long> result;
      try {
        result = connectionProvider.eval(script, keys, args);
      } catch (RedisCommandExecutionException scriptError) {
        throw scriptFailed(scriptName, scriptError);
      } catch (RedisException driverError) {
        throw driverFailed(scriptName, driverError);
      }

      // Fallback 2 - Reload script for future calls (single, throttled)
      reloadScript();

      return result;
    } catch (RedisCommandExecutionException e) {
      throw scriptFailed(scriptName, e);
    } catch (RedisException e) {
      throw driverFailed(scriptName, e);
    }
  }

  private static ScriptExecutionException scriptFailed(
      String scriptName, RedisCommandExecutionException e) {
    return new ScriptExecutionException(
        "Lua script execution failed: " + e.getMessage(), scriptName, e);
  }

  /**
   * Translates a Lettuce failure that is not a script error into a FluxGate exception.
   *
   * <p>The connection is established when the store is created, so any failure here happened while
   * a command was in flight - even one Lettuce reports as a connection error, because a command it
   * queued while reconnecting may have reached Redis. It is therefore reported as a {@link
   * RedisConnectionException.Phase#COMMAND} failure, which the retry policy never retries: retrying
   * a consumption that Redis did execute would charge the request twice.
   */
  private static RuntimeException driverFailed(String operation, RedisException e) {
    if (e instanceof RedisCommandTimeoutException) {
      return new FluxgateTimeoutException(
          "Redis command timed out running " + operation + ": " + e.getMessage(), e);
    }
    return new RedisConnectionException(
        "Redis command failed running " + operation + ": " + e.getMessage(),
        e,
        RedisConnectionException.Phase.COMMAND);
  }

  /**
   * Reloads the Lua scripts into the Redis script cache after a NOSCRIPT error.
   *
   * <p>Only one reload runs at a time, and a new one starts only once the pause since the last
   * attempt has passed: one second after a successful reload, doubled after each failed one up to a
   * minute. Until then the EVAL fallback keeps serving requests.
   */
  private void reloadScript() {
    if (reloadAttempted && System.nanoTime() - lastReloadAttemptNanos < reloadIntervalNanos) {
      log.debug("Lua script was reloaded recently, not reloading again yet");
      return;
    }
    if (!reloadingLuaScript.compareAndSet(false, true)) {
      log.debug("Lua script is already being reloaded, skipping...");
      return;
    }

    try {
      lastReloadAttemptNanos = System.nanoTime();
      reloadAttempted = true;
      scripts.loadInto(connectionProvider);
      reloadIntervalNanos = MIN_RELOAD_INTERVAL_NANOS;
    } catch (RuntimeException e) {
      reloadIntervalNanos = Math.min(MAX_RELOAD_INTERVAL_NANOS, reloadIntervalNanos * 2);
      log.error(
          "Failed to reload Lua script, next attempt in {} ms at the earliest: {}",
          reloadIntervalNanos / 1_000_000L,
          e.getMessage(),
          e);
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
   * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, unlinks every
   * SCAN page as it arrives so memory stays bounded by the page size, and uses UNLINK where the
   * server supports it so that freeing the keys happens off the event loop.
   *
   * @param ruleSetId the rule set ID to match
   * @return the number of buckets deleted
   */
  public long deleteBucketsByRuleSetId(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    String pattern = RedisRateLimiter.bucketKeyPattern(ruleSetId);
    log.debug("Deleting token buckets matching pattern: {}", pattern);

    long deleted = scanAndUnlink(pattern);
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
   * <p>Uses SCAN semantics to avoid blocking Redis on production-sized keyspaces, and unlinks every
   * SCAN page as it arrives.
   *
   * @return the number of buckets deleted
   */
  public long deleteAllBuckets() {
    String pattern = RedisRateLimiter.BUCKET_KEY_PREFIX + "*";
    log.debug("Deleting all token buckets matching pattern: {}", pattern);

    long deleted = scanAndUnlink(pattern);
    if (deleted == 0) {
      log.debug("No token buckets found");
      return 0;
    }

    log.info("Deleted {} token buckets (full reset)", deleted);
    return deleted;
  }

  /**
   * Unlinks the keys matching {@code pattern} page by page as SCAN returns them, so the keys of a
   * large rule set are never held in memory all at once. Lettuce failures are translated into
   * FluxGate exceptions as for the scripts (see {@code driverFailed}).
   */
  private long scanAndUnlink(String pattern) {
    long[] deleted = {0L};
    try {
      connectionProvider.scanKeys(
          pattern, BUCKET_SCAN_COUNT, page -> deleted[0] += deleteInBatches(page));
    } catch (RedisException e) {
      throw driverFailed("SCAN/UNLINK " + pattern, e);
    }
    return deleted[0];
  }

  private long deleteInBatches(List<String> keys) {
    long deleted = 0;
    for (int start = 0; start < keys.size(); start += DELETE_BATCH_SIZE) {
      int end = Math.min(start + DELETE_BATCH_SIZE, keys.size());
      deleted += connectionProvider.unlink(keys.subList(start, end).toArray(new String[0]));
    }
    return deleted;
  }
}
