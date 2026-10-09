--[[
Multi-Band Rate Limiter — TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW

DESIGN NOTES:
1. Uses Redis TIME (not System.nanoTime()) — solves clock drift across nodes
2. Microsecond time base kept inside double precision — see PRECISION below
3. All bands of one rule are evaluated in two passes: check every band first,
   then either consume from every band or from none. A rejected request never
   drains a band that would have allowed it.
4. Counter state (SLIDING_WINDOW, FIXED_WINDOW) is never modified on rejection;
   TOKEN_BUCKET state is not modified either; only the TTLs of TB/SW buckets
   are refreshed so buckets that see nothing but rejections still expire on schedule.
5. TTL = min(max_bucket_ttl, max(1, ceil(window_seconds * 1.1))) for TOKEN_BUCKET
   and SLIDING_WINDOW. FIXED_WINDOW uses PEXPIREAT at the absolute window end, rounded
   UP to the millisecond, and always sets one: the counter key carries no window index,
   so a counter without a TTL would keep rejecting in every later window.

PRECISION: Redis runs Lua 5.1, which has no integer type — every number is an
IEEE-754 double with an exact integer range of 2^53 (about 9.0e15). Microseconds
since the epoch are about 1.76e15, inside the exact range; nanoseconds (~1.76e18)
were not. Everything written to a hash therefore goes through
string.format('%.0f', v). The intermediate product capacity × window_micros can
exceed 2^53; the Java caller validates this for TOKEN_BUCKET bands (the
IEEE_754_MAX_PRODUCT check in RedisTokenBucketStore).

KEYS[1..n] = bucket keys, one per band of ONE rule, all in the same hash tag
  e.g. "fluxgate:bucket:{api-limits:per-ip:ip:192.168.1.100}:100-per-60s"

ARGV layout:
  ARGV[1]           = permits (number of tokens/requests to consume, usually 1)
  ARGV[2]           = max_bucket_ttl_seconds (upper bound on every TOKEN_BUCKET /
                      SLIDING_WINDOW bucket TTL; FIXED_WINDOW uses PEXPIREAT instead)

  Per band i (1-indexed), at base offset = 2 + 5 * (i - 1):
    ARGV[base + 1]  = capacity  (max tokens / requests allowed per window)
    ARGV[base + 2]  = window_micros  (window duration in microseconds)
    ARGV[base + 3]  = algorithm_code
                        1 = TOKEN_BUCKET   (continuous refill; hash {tokens, last_refill_micros})
                        2 = SLIDING_WINDOW (N sub-buckets; hash {sub_bucket_index: count})
                        3 = FIXED_WINDOW   (tumbling counter; plain string with PEXPIREAT)
    ARGV[base + 4]  = buckets_or_zero
                        SLIDING_WINDOW: number of sub-buckets [2..60]
                        others: 0
    ARGV[base + 5]  = window_end_micros_or_zero
                        FIXED_WINDOW with calendar alignment: absolute end of the current
                          period in microseconds since epoch, computed by Java
                        FIXED_WINDOW without calendar alignment: 0 — Lua derives from now
                        TOKEN_BUCKET / SLIDING_WINDOW: 0

  Total ARGV length: 2 + 5 * band_count

Returns array of 7 integers:
  [1] allowed                 1 if all bands allowed, 0 otherwise
  [2] rejecting_band_index    1-based index of the first band that rejected, 0 when allowed
  [3] min_remaining           binding band's tokens/capacity left after consumption (allow)
                              or rejecting band's tokens/capacity left (reject)
  [4] micros_to_wait          microseconds until the rejecting band can retry, 0 when allowed
  [5] reset_time_millis       epoch millis when the binding band resets:
                                TOKEN_BUCKET:   when the bucket is FULL again (after consumption)
                                SLIDING_WINDOW: end of the current sub-bucket cycle
                                FIXED_WINDOW:   end of the current window
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (= rejecting_band_index on reject)

Errors (redis.error_reply):
  'at least one bucket key is required'
  'expected N arguments for M band(s)'   — ARGV length mismatch
  'permits must be positive'
  'max bucket ttl must be >= 1 second'
  'capacity must be positive'
  'window must be positive'
  'permits exceed capacity'              — band can never serve; Java pre-validates for TOKEN_BUCKET
  'buckets must be >= 2 for SLIDING_WINDOW'
  'unknown algorithm code: N'
]]

-- ========================================================================
-- Algorithm constants
-- ========================================================================
local ALG_TOKEN_BUCKET   = 1
local ALG_SLIDING_WINDOW = 2
local ALG_FIXED_WINDOW   = 3

-- ========================================================================
-- Parse and validate arguments
-- ========================================================================
local band_count = #KEYS
if band_count < 1 then
    return redis.error_reply("at least one bucket key is required")
end
if #ARGV ~= 2 + 5 * band_count then
    return redis.error_reply(
        "expected " .. (2 + 5 * band_count) .. " arguments for " .. band_count .. " band(s)")
end

local permits = tonumber(ARGV[1])
if permits == nil or permits <= 0 then
    return redis.error_reply("permits must be positive")
end

local max_ttl_seconds = tonumber(ARGV[2])
if max_ttl_seconds == nil or max_ttl_seconds < 1 then
    return redis.error_reply("max bucket ttl must be >= 1 second")
end

local capacities    = {}
local windows       = {}
local algorithms    = {}
local bucket_counts = {}   -- sub-bucket count for SLIDING_WINDOW
local win_ends_in   = {}   -- supplied window end for FIXED_WINDOW (0 = derive)

for i = 1, band_count do
    local base      = 2 + 5 * (i - 1)
    local capacity  = tonumber(ARGV[base + 1])
    local win_micros = tonumber(ARGV[base + 2])
    local alg       = tonumber(ARGV[base + 3]) or ALG_TOKEN_BUCKET
    local buckets   = tonumber(ARGV[base + 4]) or 0
    local win_end   = tonumber(ARGV[base + 5]) or 0

    if capacity == nil or capacity <= 0 then
        return redis.error_reply("capacity must be positive")
    end
    if win_micros == nil or win_micros <= 0 then
        return redis.error_reply("window must be positive")
    end
    if permits > capacity then
        return redis.error_reply("permits exceed capacity")
    end
    if alg == ALG_SLIDING_WINDOW and buckets < 2 then
        return redis.error_reply("buckets must be >= 2 for SLIDING_WINDOW")
    end

    capacities[i]    = capacity
    windows[i]       = win_micros
    algorithms[i]    = alg
    bucket_counts[i] = buckets
    win_ends_in[i]   = win_end
end

-- ========================================================================
-- Single clock for every node: Redis TIME, in microseconds
-- ========================================================================
local time_info  = redis.call('TIME')
-- time_info[1] = seconds since epoch, time_info[2] = microseconds within the second
local now_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])

-- TTL in whole seconds, capped at max_ttl_seconds (for TOKEN_BUCKET and SLIDING_WINDOW).
-- min=1s so a sub-second window still gets a bucket that expires.
local function ttl_for_window(win_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1)))
end

-- Absolute expiry of a FIXED_WINDOW counter in epoch milliseconds: the window end rounded
-- UP (a sub-second window must not lose its TTL, and the counter must not expire before
-- the window ends), and never earlier than 1 ms from now so PEXPIREAT never deletes the key.
local function fixed_window_expire_millis(win_end_micros)
    return math.max(math.ceil(win_end_micros / 1000), math.floor(now_micros / 1000) + 1)
end

-- Refresh TTLs for TOKEN_BUCKET and SLIDING_WINDOW keys on the reject path.
-- EXPIRE is a no-op on keys that do not exist yet; FIXED_WINDOW keys are not touched
-- because PEXPIREAT (an absolute timestamp) must not be overridden with a relative one.
local function refresh_ttls()
    for j = 1, band_count do
        if algorithms[j] == ALG_TOKEN_BUCKET or algorithms[j] == ALG_SLIDING_WINDOW then
            redis.call('EXPIRE', KEYS[j], ttl_for_window(windows[j]))
        end
    end
end

-- ========================================================================
-- Pass-1 state tables (populated during the check pass; reused in pass 2)
-- ========================================================================
-- TOKEN_BUCKET
local tb_tokens  = {}   -- token count after refill
local tb_refills = {}   -- next refill timestamp (micros)

-- SLIDING_WINDOW
local sw_sub_dur = {}   -- sub-bucket duration (micros)
local sw_cur_sub = {}   -- current sub-bucket index
local sw_sums    = {}   -- sum of active request counts
local sw_oldest  = {}   -- oldest sub-bucket index that has a positive count
local sw_raw     = {}   -- raw HGETALL output, reused in pass 2 to avoid a second round-trip

-- FIXED_WINDOW
local fw_counts  = {}   -- current counter value
local fw_ends    = {}   -- resolved window end (micros)

-- ========================================================================
-- Pass 1: read every band and check whether all of them can serve the request
-- ========================================================================
for i = 1, band_count do
    local capacity   = capacities[i]
    local win_micros = windows[i]
    local alg        = algorithms[i]

    -- ---- TOKEN_BUCKET ----
    if alg == ALG_TOKEN_BUCKET then
        local data       = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros')
        local cur_tokens = tonumber(data[1])
        local last_refill = tonumber(data[2])

        -- Missing bucket (or a 0.3.x bucket whose 'last_refill_micros' is absent):
        -- start full, which allows the initial burst.
        if cur_tokens == nil or last_refill == nil then
            cur_tokens  = capacity
            last_refill = now_micros
        end

        -- math.max handles a clock that moved backwards; math.min caps at one window so
        -- elapsed * capacity stays inside the 2^53 safe range (the Java caller validates this).
        local elapsed = math.min(math.max(0, now_micros - last_refill), win_micros)

        -- Only whole tokens are credited, and the timestamp advances only by the time those
        -- tokens cost, so the sub-token remainder is carried into the next call.
        local to_add      = math.floor(elapsed * capacity / win_micros)
        local next_refill = last_refill
        if to_add > 0 then
            next_refill = last_refill + math.floor(to_add * win_micros / capacity)
        end
        local refilled = math.min(capacity, cur_tokens + to_add)
        if refilled >= capacity then
            -- Bucket is full; no deficit to carry.
            next_refill = now_micros
        end

        tb_tokens[i]  = refilled
        tb_refills[i] = next_refill

        if refilled < permits then
            refresh_ttls()
            local tokens_needed = permits - refilled
            local wait          = math.ceil(tokens_needed * win_micros / capacity)
            local deficit       = capacity - refilled
            local full_micros   = deficit > 0 and math.ceil(deficit * win_micros / capacity) or 0
            local reset_millis  = math.floor((now_micros + full_micros) / 1000)
            return {0, i, refilled, wait, reset_millis, capacity, i}
        end

    -- ---- SLIDING_WINDOW ----
    elseif alg == ALG_SLIDING_WINDOW then
        local buckets    = bucket_counts[i]
        local sub_dur    = math.floor(win_micros / buckets)
        local cur_sub    = math.floor(now_micros / sub_dur)
        local oldest_val = cur_sub - buckets + 1  -- oldest valid sub-bucket index

        sw_sub_dur[i] = sub_dur
        sw_cur_sub[i] = cur_sub

        -- Read all hash fields; reuse in pass 2 to avoid re-reading.
        local raw = redis.call('HGETALL', KEYS[i])
        sw_raw[i] = raw

        local total          = 0
        local oldest_counted = cur_sub  -- tracks oldest sub with a positive count

        for fi = 1, #raw, 2 do
            local idx = tonumber(raw[fi])
            local cnt = tonumber(raw[fi + 1]) or 0
            if idx ~= nil and cnt > 0 and idx >= oldest_val then
                total = total + cnt
                if idx < oldest_counted then
                    oldest_counted = idx
                end
            end
        end

        sw_sums[i]   = total
        sw_oldest[i] = oldest_counted

        if total + permits > capacity then
            refresh_ttls()
            local remaining = capacity - total
            -- micros_to_wait = time until the oldest counted sub-bucket expires (freeing tokens).
            local wait
            if oldest_counted < cur_sub then
                wait = math.max(0, (oldest_counted + buckets) * sub_dur - now_micros)
            else
                -- All counts are in the current sub-bucket; wait until the next sub-bucket starts.
                wait = math.max(0, (cur_sub + 1) * sub_dur - now_micros)
            end
            -- reset_time_millis = end of the current window cycle.
            local reset_millis = math.floor((cur_sub + buckets) * sub_dur / 1000)
            return {0, i, remaining, wait, reset_millis, capacity, i}
        end

    -- ---- FIXED_WINDOW ----
    elseif alg == ALG_FIXED_WINDOW then
        local win_end = win_ends_in[i]
        if win_end == nil or win_end == 0 then
            -- Align to window_micros boundaries since epoch.
            local win_idx = math.floor(now_micros / win_micros)
            win_end = (win_idx + 1) * win_micros
        end
        fw_ends[i] = win_end

        -- GET returns nil when the key has expired or never existed; treat as 0.
        local count = tonumber(redis.call('GET', KEYS[i])) or 0
        fw_counts[i] = count

        if count + permits > capacity then
            refresh_ttls()
            -- A counter left without a TTL (written by an older script) would reject forever.
            if redis.call('PTTL', KEYS[i]) == -1 then
                redis.call('PEXPIREAT', KEYS[i], string.format('%.0f', fixed_window_expire_millis(win_end)))
            end
            local remaining    = capacity - count
            local wait         = math.max(0, win_end - now_micros)
            local reset_millis = math.floor(win_end / 1000)
            return {0, i, remaining, wait, reset_millis, capacity, i}
        end

    else
        return redis.error_reply("unknown algorithm code: " .. tostring(alg))
    end
end

-- ========================================================================
-- Pass 2: every band can serve the request — write state for all of them
-- ========================================================================
-- Lua 5.1 has global unpack(); Lua 5.4 moved it to table.unpack(); support both.
local unpack_f = table.unpack or unpack

local binding           = 1
local binding_remaining = nil

for i = 1, band_count do
    local capacity   = capacities[i]
    local win_micros = windows[i]
    local alg        = algorithms[i]

    -- ---- TOKEN_BUCKET ----
    if alg == ALG_TOKEN_BUCKET then
        local remaining = tb_tokens[i] - permits
        redis.call('HMSET', KEYS[i],
            'tokens',             string.format('%.0f', remaining),
            'last_refill_micros', string.format('%.0f', tb_refills[i]))
        redis.call('EXPIRE', KEYS[i], ttl_for_window(win_micros))
        tb_tokens[i] = remaining   -- updated for reset_time_millis calculation below

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end

    -- ---- SLIDING_WINDOW ----
    elseif alg == ALG_SLIDING_WINDOW then
        local cur_sub    = sw_cur_sub[i]
        local oldest_val = cur_sub - bucket_counts[i] + 1
        local raw        = sw_raw[i]

        -- Delete expired sub-buckets in one HDEL call.
        local to_del = {}
        for fi = 1, #raw, 2 do
            local idx = tonumber(raw[fi])
            if idx ~= nil and idx < oldest_val then
                to_del[#to_del + 1] = raw[fi]
            end
        end
        if #to_del > 0 then
            redis.call('HDEL', KEYS[i], unpack_f(to_del))
        end

        redis.call('HINCRBY', KEYS[i], tostring(cur_sub), permits)
        redis.call('EXPIRE', KEYS[i], ttl_for_window(win_micros))

        local remaining = capacity - sw_sums[i] - permits

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end

    -- ---- FIXED_WINDOW ----
    elseif alg == ALG_FIXED_WINDOW then
        local win_end = fw_ends[i]
        redis.call('INCRBY', KEYS[i], permits)
        redis.call('PEXPIREAT', KEYS[i], string.format('%.0f', fixed_window_expire_millis(win_end)))

        local remaining = capacity - fw_counts[i] - permits

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end
    end
end

-- ========================================================================
-- Compute reset_time_millis for the binding band after consumption
-- ========================================================================
local binding_capacity = capacities[binding]
local binding_alg      = algorithms[binding]
local reset_millis

if binding_alg == ALG_TOKEN_BUCKET then
    -- Time until the binding bucket is completely full again (computed AFTER consumption).
    local remaining   = tb_tokens[binding]   -- already updated in pass 2
    local deficit     = binding_capacity - remaining
    local full_micros = deficit > 0 and math.ceil(deficit * windows[binding] / binding_capacity) or 0
    reset_millis = math.floor((now_micros + full_micros) / 1000)

elseif binding_alg == ALG_SLIDING_WINDOW then
    -- End of the current sub-bucket cycle (when all requests in current sub expire).
    local cur_sub  = sw_cur_sub[binding]
    local buckets  = bucket_counts[binding]
    local sub_dur  = sw_sub_dur[binding]
    reset_millis = math.floor((cur_sub + buckets) * sub_dur / 1000)

elseif binding_alg == ALG_FIXED_WINDOW then
    reset_millis = math.floor(fw_ends[binding] / 1000)
end

return {1, 0, binding_remaining, 0, reset_millis, binding_capacity, binding}
