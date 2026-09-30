--[[
Multi-Band Token Bucket Rate Limiter

DESIGN NOTES:
1. Uses Redis TIME (not System.nanoTime()) - solves clock drift across nodes
2. Microsecond time base kept inside double precision - see below
3. All bands of one rule are evaluated in two passes: check every band first,
   then either consume from every band or from none. A rejected request never
   drains a band that would have allowed it.
4. State is never modified on rejection, apart from refreshing the TTL of the
   bands that already exist (see TTL below)
5. TTL = min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1))). The cap comes
   from the caller (fluxgate.redis.max-bucket-ttl, 7 days by default): without
   one, every forged identity key leaves a Redis hash resident for the whole
   window, so a 30 day rule keeps 33 days of attacker-controlled keys alive

PRECISION: Redis runs Lua 5.1, which has no integer type - every number is an
IEEE-754 double with an exact integer range of 2^53 (about 9.0e15). Microseconds
since the epoch are about 1.76e15, so both the timestamps we store and the
timestamps we read back are exactly representable; nanoseconds (about 1.76e18)
were not, and `redis.call` serialised them as "1.76e+18" through Lua's default
%.14g. Everything written to a hash therefore goes through
string.format('%.0f', v). The intermediate product `elapsed * capacity` (and
the analogous `deficit * window_micros`) can exceed 2^53 if both operands are
large: keep capacity × window_micros ≤ 2^53 ≈ 9.0e15 to avoid precision loss
(e.g. capacity=10^6 and window ≤ ~104 days is fine; capacity=10^9 limits the
window to ~2.5 hours).

KEYS[1..n] = bucket keys, one per band of ONE rule, all in the same hash tag
  (e.g. "fluxgate:bucket:{api-limits:per-ip:ip:192.168.1.100}:100-per-60s")

ARGV[1]       = permits (number of tokens to consume, usually 1)
ARGV[2 + 3i]  = capacity of band i+1 (max tokens)
ARGV[3 + 3i]  = window_micros of band i+1 (window duration in microseconds)
ARGV[4 + 3i]  = reserved for future use, pass 0
ARGV[#ARGV]   = max_bucket_ttl_seconds, the upper bound on every bucket TTL
                (last argument, so the per-band triplets keep their indices)

Hash fields per bucket: 'tokens', 'last_refill_micros'. A bucket written by
FluxGate 0.3.x carries 'last_refill_nanos' instead; that field is ignored, so
such a bucket is re-initialised once (a one-off quota reset on upgrade).

Returns array of 7 integers:
  [1] allowed                 1 if all bands allowed, 0 otherwise
  [2] rejecting_band_index    1-based index of the first band that rejected, 0 when allowed
  [3] min_remaining           binding band's tokens after consumption (allow) /
                              rejecting band's tokens (reject)
  [4] micros_to_wait          microseconds until the rejecting band can serve the
                              request, 0 when allowed
  [5] reset_time_millis       epoch millis at which the binding band's bucket is
                              full again (computed AFTER consumption on allow)
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (equals
                              rejecting_band_index on reject)

Errors: 'permits exceed capacity' when a band can never serve the request.
]]

-- ========================================================================
-- Parse and validate arguments
-- ========================================================================
local band_count = #KEYS
if band_count < 1 then
    return redis.error_reply("at least one bucket key is required")
end
if #ARGV ~= 2 + 3 * band_count then
    return redis.error_reply("expected " .. (2 + 3 * band_count) .. " arguments for " .. band_count .. " band(s)")
end

local permits = tonumber(ARGV[1])
if permits == nil or permits <= 0 then
    return redis.error_reply("permits must be positive")
end

local capacities = {}
local windows = {}
for i = 1, band_count do
    local capacity = tonumber(ARGV[2 + 3 * (i - 1)])
    local window_micros = tonumber(ARGV[3 + 3 * (i - 1)])

    if capacity == nil or capacity <= 0 then
        return redis.error_reply("capacity must be positive")
    end
    if window_micros == nil or window_micros <= 0 then
        return redis.error_reply("window must be positive")
    end
    if permits > capacity then
        return redis.error_reply("permits exceed capacity")
    end

    capacities[i] = capacity
    windows[i] = window_micros
end

-- ========================================================================
-- Single clock for every node: Redis TIME, in microseconds
-- ========================================================================
local time_info = redis.call('TIME')
-- time_info[1] = seconds since epoch, time_info[2] = microseconds within the second
local now_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])

-- TTL in whole seconds: the window plus a 10% margin for clock skew, never below 1s
-- and never above the cap the caller passed. An uncapped TTL meets a forgeable
-- identity scope badly: each forged key then occupies memory for a whole window.
local max_ttl_seconds = tonumber(ARGV[#ARGV])
if max_ttl_seconds == nil or max_ttl_seconds < 1 then
    return redis.error_reply("max bucket ttl must be >= 1 second")
end

local function ttl_seconds(window_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(window_micros / 1000000 * 1.1)))
end

-- ========================================================================
-- Pass 1: refill every band and check whether all of them can serve the request
-- ========================================================================
local tokens = {}
local refill_micros = {}

for i = 1, band_count do
    local capacity = capacities[i]
    local window_micros = windows[i]

    local bucket_data = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros')
    local current_tokens = tonumber(bucket_data[1])
    local last_refill_micros = tonumber(bucket_data[2])

    -- Missing bucket (or a 0.3.x bucket, whose 'last_refill_micros' is absent):
    -- start full, which allows the initial burst.
    if current_tokens == nil or last_refill_micros == nil then
        current_tokens = capacity
        last_refill_micros = now_micros
    end

    -- math.max handles a clock that moved backwards (e.g. after a Redis restart);
    -- math.min caps the elapsed time at one window, beyond which the bucket is full
    -- anyway, and bounds the elapsed * capacity product (see the PRECISION note in
    -- the header for the limits).
    local elapsed_micros = math.min(math.max(0, now_micros - last_refill_micros), window_micros)

    -- Only whole tokens are credited, and the timestamp advances only by the time
    -- those tokens cost, so the sub-token remainder is carried into the next call
    -- instead of being dropped (which would systematically under-allow).
    local tokens_to_add = math.floor(elapsed_micros * capacity / window_micros)
    local next_refill_micros = last_refill_micros
    if tokens_to_add > 0 then
        next_refill_micros = last_refill_micros + math.floor(tokens_to_add * window_micros / capacity)
    end

    local refilled = math.min(capacity, current_tokens + tokens_to_add)
    if refilled >= capacity then
        -- Bucket is full: there is no deficit left to carry.
        next_refill_micros = now_micros
    end

    tokens[i] = refilled
    refill_micros[i] = next_refill_micros

    if refilled < permits then
        -- ================================================================
        -- REJECTED: no band is written, so the bands that would have allowed
        -- the request keep their tokens. Only the TTLs are refreshed, so a
        -- bucket that sees nothing but rejections still expires on schedule
        -- instead of living on with the TTL of its last allowed request.
        -- EXPIRE is a no-op on a bucket that does not exist yet.
        -- ================================================================
        for j = 1, band_count do
            redis.call('EXPIRE', KEYS[j], ttl_seconds(windows[j]))
        end

        local tokens_needed = permits - refilled
        local micros_to_wait = math.ceil(tokens_needed * window_micros / capacity)

        -- reset_time_millis = epoch millis when the bucket is FULL again, matching the allow-path
        -- semantics. micros_to_wait is only the retry delay; the bucket is full only after the
        -- entire deficit (capacity - refilled) has been refilled.
        local deficit = capacity - refilled
        local micros_until_full = 0
        if deficit > 0 then
            micros_until_full = math.ceil(deficit * window_micros / capacity)
        end
        local reset_time_millis = math.floor((now_micros + micros_until_full) / 1000)

        return {0, i, refilled, micros_to_wait, reset_time_millis, capacity, i}
    end
end

-- ========================================================================
-- Pass 2: every band can serve the request, so consume from all of them
-- ========================================================================
local binding = 1

for i = 1, band_count do
    local remaining = tokens[i] - permits

    redis.call('HMSET', KEYS[i],
        'tokens', string.format('%.0f', remaining),
        'last_refill_micros', string.format('%.0f', refill_micros[i])
    )
    redis.call('EXPIRE', KEYS[i], ttl_seconds(windows[i]))

    tokens[i] = remaining
    if remaining < tokens[binding] then
        binding = i
    end
end

-- Reset time is computed AFTER consumption, so the caller is told when the bucket
-- is really full again rather than when it would have been without this request.
local binding_capacity = capacities[binding]
local deficit = binding_capacity - tokens[binding]
local micros_until_full = 0
if deficit > 0 then
    micros_until_full = math.ceil(deficit * windows[binding] / binding_capacity)
end
local reset_time_millis = math.floor((now_micros + micros_until_full) / 1000)

return {1, 0, tokens[binding], 0, reset_time_millis, binding_capacity, binding}
