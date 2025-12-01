package org.fluxgate.redis.store;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.redis.script.LuaScriptLoader;
import org.fluxgate.redis.script.LuaScripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Redis-backed implementation of {@link TokenBucketStore}.
 * <p>
 * Uses Lua scripts for atomic refill + consume operations.
 */
public class RedisTokenBucketStore implements TokenBucketStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketStore.class);

    private final RedisCommands<String, String> redisCommands;

    public RedisTokenBucketStore(RedisCommands<String, String> redisCommands) {
        this.redisCommands = Objects.requireNonNull(redisCommands, "redisCommands must not be null");

        // Ensure scripts are loaded
        if (!LuaScriptLoader.isLoaded()) {
            throw new IllegalStateException(
                    "Lua scripts not loaded. Call LuaScriptLoader.loadScripts() first.");
        }
    }

    @Override
    public BucketState tryConsume(String bucketKey, RateLimitBand band, long permits) {
        Objects.requireNonNull(bucketKey, "bucketKey must not be null");
        Objects.requireNonNull(band, "band must not be null");

        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }

        // Extract band parameters
        long capacity = band.getCapacity();
        long windowNanos = band.getWindow().toNanos();

        // Calculate refill rate: tokens per nanosecond
        // refill_rate = capacity / window_nanos
        double refillRate = (double) capacity / windowNanos;

        // Current timestamp in nanoseconds
        long currentTimeNanos = System.nanoTime();

        // Execute Lua script
        String sha = LuaScripts.getTokenBucketConsumeSha();

        // KEYS[1] = bucketKey
        // ARGV[1] = capacity
        // ARGV[2] = refill_rate
        // ARGV[3] = window_nanos
        // ARGV[4] = permits
        // ARGV[5] = current_time_nanos
        String[] keys = {bucketKey};
        String[] args = {
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(windowNanos),
                String.valueOf(permits),
                String.valueOf(currentTimeNanos)
        };

        // Execute script
        List<Long> result = redisCommands.evalsha(
                sha,
                ScriptOutputType.MULTI,
                keys,
                args
        );

        // Parse result: [consumed, remaining_tokens, nanos_to_wait]
        if (result == null || result.size() != 3) {
            log.error("Unexpected Lua script result: {}", result);
            throw new IllegalStateException("Lua script returned invalid result");
        }

        long consumed = result.get(0);
        long remainingTokens = result.get(1);
        long nanosToWait = result.get(2);

        if (consumed == 1) {
            return BucketState.allowed(remainingTokens);
        } else {
            return BucketState.rejected(remainingTokens, nanosToWait);
        }
    }

    @Override
    public void close() {
        // Lettuce connection is managed externally, so nothing to do here
        log.debug("RedisTokenBucketStore closed");
    }
}
