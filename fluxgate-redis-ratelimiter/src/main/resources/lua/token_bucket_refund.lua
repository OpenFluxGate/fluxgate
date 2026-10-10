--[[
Refund for a multi-band consumption - the compensation half of cross-rule evaluation.

RedisRateLimiter evaluates every matching rule in ONE token_bucket_consume.lua call when all
their keys share a hash slot. When they do not (Redis Cluster, rules with different hash
tags), it consumes rule by rule, and if a later rule rejects it calls this script once per
rule that had already been charged, so the rejected request ends up costing nothing.

Each band gets back at most what the consumption took, and only from the state that
consumption wrote:

  TOKEN_BUCKET   tokens = min(capacity, tokens + permits). A missing bucket is left alone:
                 it re-initialises full on its next use anyway.
  SLIDING_WINDOW the sub-bucket the consumption incremented, identified from the consume
                 time and the geometry ("<floor(consumed_at / sub_duration)>@<sub_duration>",
                 the field name the consume script writes), is decremented, never below 0.
                 If that sub-bucket has already left the window there is nothing to refund.
  FIXED_WINDOW   the counter is decremented, never below 0, only while it still counts the
                 window the consumption was charged to (window_end_micros unchanged). After
                 a rollover the old count no longer exists, so there is nothing to refund.

Nothing else is touched: no TTL, no last-refill timestamp. The refund never creates a key.
Every band is validated before any is refunded: an invalid band fails the whole call with
nothing written.

KEYS[1..n] = bucket keys, one per band of ONE rule, all in the same hash tag
ARGV[1]    = permits that were consumed
ARGV[2]    = consumed_at_micros: the Redis TIME the consume script reported (result [8])
Per band i (1-indexed), at base offset = 2 + 5 * (i - 1) - the same layout as the consume script:
  ARGV[base + 1] = capacity
  ARGV[base + 2] = window_micros
  ARGV[base + 3] = algorithm_code (1 TOKEN_BUCKET, 2 SLIDING_WINDOW, 3 FIXED_WINDOW)
  ARGV[base + 4] = buckets_or_zero (SLIDING_WINDOW sub-bucket count)
  ARGV[base + 5] = window_end_micros_or_zero (calendar FIXED_WINDOW end, else 0)

Returns an array of n integers: the permits actually given back to each band.

Errors (redis.error_reply) - the same messages as the consume script for the same arguments:
  'at least one bucket key is required'
  'expected N arguments for M band(s)'
  'permits must be positive'
  'consumed_at_micros must be positive'
  'capacity must be positive'
  'window must be positive'
  'window must be at least 1 ms'
  'buckets must be >= 2 for SLIDING_WINDOW'
  'sliding window sub-bucket must be at least 1 ms'
  'unknown algorithm code: N'
]]

local ALG_TOKEN_BUCKET   = 1
local ALG_SLIDING_WINDOW = 2
local ALG_FIXED_WINDOW   = 3

local extended = ARGV[#ARGV] == 'FENCED'
local band_count = extended and (#KEYS / 2) or #KEYS
if band_count < 1 then
    return redis.error_reply("at least one bucket key is required")
end
if (extended and (#KEYS % 2 ~= 0 or #ARGV ~= 3 + 5 * band_count))
    or (not extended and #ARGV ~= 2 + 5 * band_count) then
    return redis.error_reply(
        "expected " .. (2 + 5 * band_count) .. " arguments for " .. band_count .. " band(s)")
end

local permits = tonumber(ARGV[1])
if permits == nil or permits <= 0 then
    return redis.error_reply("permits must be positive")
end
local consumed_at = tonumber(ARGV[2])
if consumed_at == nil or consumed_at <= 0 then
    return redis.error_reply("consumed_at_micros must be positive")
end

-- Pass 1: validate every band before anything is written. Redis does not roll a script back
-- when it returns an error, so a bad band found half-way would leave the bands before it
-- refunded and the rest charged.
local capacities, windows, algorithms, sub_durs, win_ends = {}, {}, {}, {}, {}

for i = 1, band_count do
    local base       = 2 + 5 * (i - 1)
    local capacity   = tonumber(ARGV[base + 1])
    local win_micros = tonumber(ARGV[base + 2])
    local alg        = tonumber(ARGV[base + 3])
    local buckets    = tonumber(ARGV[base + 4]) or 0

    if capacity == nil or capacity <= 0 then
        return redis.error_reply("capacity must be positive")
    end
    if win_micros == nil or win_micros <= 0 then
        return redis.error_reply("window must be positive")
    end
    if win_micros < 1000 then
        return redis.error_reply("window must be at least 1 ms")
    end
    if alg == ALG_SLIDING_WINDOW then
        if buckets < 2 then
            return redis.error_reply("buckets must be >= 2 for SLIDING_WINDOW")
        end
        sub_durs[i] = math.floor(win_micros / buckets)
        if sub_durs[i] < 1000 then
            return redis.error_reply("sliding window sub-bucket must be at least 1 ms")
        end
    elseif alg ~= ALG_TOKEN_BUCKET and alg ~= ALG_FIXED_WINDOW then
        return redis.error_reply("unknown algorithm code: " .. tostring(alg))
    end

    if extended then
        local current = redis.call('HMGET', KEYS[band_count + i], 'capacity', 'window_micros', 'algorithm', 'buckets')
        if current[1] then
            if tonumber(current[2]) ~= win_micros or tonumber(current[3]) ~= alg
                or (alg == ALG_SLIDING_WINDOW and tonumber(current[4]) ~= buckets) then
                return redis.error_reply('POLICY_RESET_REQUIRED: refund geometry changed')
            end
            capacity = tonumber(current[1])
        end
    end
    local kind = redis.call('TYPE', KEYS[i]).ok
    if kind ~= 'none' and kind ~= 'hash' then
        return redis.error_reply('WRONGTYPE: refund bucket must be a hash')
    end
    local values = redis.call('HMGET', KEYS[i], 'tokens', 'count', 'window_end_micros')
    for j = 1, 3 do
        if values[j] and tonumber(values[j]) == nil then
            return redis.error_reply('INVALID_POLICY_STATE: refund bucket numeric field')
        end
    end
    capacities[i] = capacity
    windows[i]    = win_micros
    algorithms[i] = alg
    win_ends[i]   = tonumber(ARGV[base + 5]) or 0
end

-- Pass 2: every band is valid - give back what each one was charged.
local refunded = {}

for i = 1, band_count do
    local capacity   = capacities[i]
    local win_micros = windows[i]
    local alg        = algorithms[i]
    local key        = KEYS[i]
    local given      = 0

    if alg == ALG_TOKEN_BUCKET then
        local tokens = tonumber(redis.call('HGET', key, 'tokens'))
        if tokens ~= nil then
            local restored = math.min(capacity, tokens + permits)
            if restored > tokens then
                redis.call('HSET', key, 'tokens', string.format('%.0f', restored))
                given = restored - tokens
            end
        end

    elseif alg == ALG_SLIDING_WINDOW then
        local sub_dur = sub_durs[i]
        -- exactly as the consume script names the field it HINCRBYs
        local field   = string.format('%.0f', math.floor(consumed_at / sub_dur))
            .. '@' .. string.format('%.0f', sub_dur)
        local count   = tonumber(redis.call('HGET', key, field))
        if count ~= nil and count > 0 then
            local left = math.max(0, count - permits)
            if left == 0 then
                redis.call('HDEL', key, field)
            else
                redis.call('HSET', key, field, string.format('%.0f', left))
            end
            given = count - left
        end

    else -- ALG_FIXED_WINDOW
        local win_end = win_ends[i]
        if win_end == 0 then
            win_end = (math.floor(consumed_at / win_micros) + 1) * win_micros
        end
        local stored     = redis.call('HMGET', key, 'count', 'window_end_micros')
        local count      = tonumber(stored[1])
        local stored_end = tonumber(stored[2])
        if count ~= nil and count > 0 and stored_end == win_end then
            local left = math.max(0, count - permits)
            redis.call('HSET', key, 'count', string.format('%.0f', left))
            given = count - left
        end
    end

    refunded[i] = given
end

return refunded
