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
   UP to the millisecond, and always sets one so the counter does not outlive its window.
6. A FIXED_WINDOW counter is a hash {count, window_end_micros}: it records WHICH window it
   counts. The TTL alone cannot separate windows - Redis treats a key as expired only when
   now > expiry, so a call in the exact expiry millisecond (or an app clock that is ahead of
   Redis on a calendar boundary) would read the old count and carry it into the next window.
   The window identity is therefore compared explicitly:
     stored end <  requested end  -> a new window: the count restarts at 0
     stored end == requested end  -> the same window
     stored end >  requested end  -> the caller lags behind (clock skew); the newer stored
                                     window and its count win, so a lagging node cannot
                                     reset a period that has already started
7. A SLIDING_WINDOW field is named "<sub-bucket index>@<sub-bucket duration in micros>". An index
   only means something together with the duration it was computed with: after the window or
   the sub-bucket count changes, the old indices are scaled differently and would look like
   sub-buckets far in the future (or past) of the new geometry. Only fields of the current
   duration whose index lies in [current - buckets + 1, current] are counted; every other field
   (another geometry, a sub-bucket after the current one after a clock step back, the
   bare-index format of earlier 0.4 builds) is ignored, and removed on the next admitted
   request. A changed geometry therefore starts a fresh window instead of rejecting forever.
8. Windows below 1 ms, and sliding sub-buckets below 1 ms, are refused: they would divide by
   zero or produce indices beyond the exact integer range, and no Redis round trip resolves
   them anyway. Every index is written with string.format('%.0f'), never tostring, which would
   switch to exponent notation (and collide) for large values.
9. Check-only mode runs pass 1 and returns: nothing is consumed. It lets the Java caller ask a
   rule it did not charge how long it would make a request wait (see note 10), without the
   charge-and-refund that a real consumption would need. Like a rejection, it only refreshes
   TTLs when it rejects.
10. Pass 1 checks every band even after one rejected (still without writing), and reports the
    rejecting band whose wait is longest. Reporting the first one would hand out a Retry-After
    after which another band still rejects.
11. A rejecting SLIDING_WINDOW band waits until enough counted requests have left the window for
    the request to fit: its sub-buckets are walked oldest to newest, adding up their counts, and
    the first sub-bucket k after whose departure total - freed + permits <= capacity sets the
    wait to (k + buckets) * sub_duration - now. A burst inside one sub-bucket therefore waits
    almost a whole window, not just until the next sub-bucket starts.

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
                        2 = SLIDING_WINDOW (N sub-buckets; hash {"<index>@<sub_dur>": count},
                                            see note 7)
                        3 = FIXED_WINDOW   (tumbling counter; hash {count, window_end_micros}
                                            with PEXPIREAT)
    ARGV[base + 4]  = buckets_or_zero
                        SLIDING_WINDOW: number of sub-buckets [2..60]
                        others: 0
    ARGV[base + 5]  = window_end_micros_or_zero
                        FIXED_WINDOW with calendar alignment: absolute end of the current
                          period in microseconds since epoch, computed by Java
                        FIXED_WINDOW without calendar alignment: 0 — Lua derives from now
                        TOKEN_BUCKET / SLIDING_WINDOW: 0

  Optional, after the last band:
    ARGV[3 + 5 * band_count] = "1" for check-only mode: the decision is taken exactly as for a
                               consumption, but no token, counter or sub-bucket is written on
                               allow either (see note 9). Any other value, or no value, consumes.

  Total ARGV length: 2 + 5 * band_count, or 3 + 5 * band_count with the check-only flag

Returns array of 8 integers:
  [1] allowed                 1 if all bands allowed, 0 otherwise
  [2] rejecting_band_index    1-based index of the rejecting band with the LONGEST wait (the
                              first of them on a tie), 0 when allowed - see note 10
  [3] min_remaining           binding band's tokens/capacity left after consumption (allow;
                              before it in check-only mode) or the rejecting band's (reject)
  [4] micros_to_wait          microseconds until the rejecting band can retry, 0 when allowed;
                              the longest wait of all rejecting bands, so a retry after it is
                              not refused again by another band that also rejected
  [5] reset_time_millis       epoch millis when the binding band resets:
                                TOKEN_BUCKET:   when the bucket is FULL again (after consumption)
                                SLIDING_WINDOW: end of the current sub-bucket cycle, rounded up
                                                to the millisecond
                                FIXED_WINDOW:   end of the current window
  [6] limit                   capacity of the binding band
  [7] binding_band_index      1-based index of the binding band (= rejecting_band_index on reject)
  [8] now_micros              the Redis TIME this decision was taken at; token_bucket_refund.lua
                              uses it to find the sliding sub-bucket / fixed window it charged

Errors (redis.error_reply):
  'at least one bucket key is required'
  'expected N arguments for M band(s)'   — ARGV length mismatch
  'permits must be positive'
  'max bucket ttl must be >= 1 second'
  'capacity must be positive'
  'window must be positive'
  'window must be at least 1 ms'
  'sliding window sub-bucket must be at least 1 ms'
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
-- Fenced wire: n buckets, n metadata hashes, n rule fences; per-band revisions.
-- Legacy raw Lua calls remain readable for compatibility, but Java always uses fences.
local extended = ARGV[#ARGV] == 'FENCED'
local band_count = extended and (#KEYS / 3) or #KEYS
if band_count < 1 then
    return redis.error_reply("at least one bucket key is required")
end
if (extended and (#KEYS % 3 ~= 0 or #ARGV ~= 4 + 6 * band_count))
    or (not extended and #ARGV ~= 2 + 5 * band_count and #ARGV ~= 3 + 5 * band_count) then
    return redis.error_reply(
        "expected " .. (2 + 5 * band_count) .. " arguments for " .. band_count .. " band(s)")
end
local check_only = extended and ARGV[#ARGV - 1] == '1'
    or (not extended and #ARGV == 3 + 5 * band_count and ARGV[#ARGV] == '1')
local revisions, metadata = {}, {}
local function key_type(key)
    return redis.call('TYPE', key).ok
end
local function numeric_field(value)
    if not value then return true end
    local number = tonumber(value)
    return number ~= nil and number == number and number ~= math.huge and number ~= -math.huge
end
-- Redis does not roll scripts back. Validate the type and stored numeric state of EVERY
-- bucket, metadata hash and fence before migration can mutate any earlier band.
for i = 1, band_count do
    local kind = key_type(KEYS[i])
    if kind ~= 'none' and kind ~= 'hash' then
        return redis.error_reply('WRONGTYPE: bucket must be a hash')
    end
    if extended then
        local meta_kind = key_type(KEYS[band_count + i])
        local fence_kind = key_type(KEYS[2 * band_count + i])
        if meta_kind ~= 'none' and meta_kind ~= 'hash' then
            return redis.error_reply('WRONGTYPE: metadata must be a hash')
        end
        if fence_kind ~= 'none' and fence_kind ~= 'string' then
            return redis.error_reply('WRONGTYPE: revision fence must be a string')
        end
        local fence = redis.call('GET', KEYS[2 * band_count + i])
        if fence and (not numeric_field(fence) or tonumber(fence) < 0
            or tonumber(fence) ~= math.floor(tonumber(fence)) or tonumber(fence) > 9007199254740991) then
            return redis.error_reply('INVALID_POLICY_STATE: revision fence')
        end
        local fields = redis.call('HMGET', KEYS[band_count + i], 'revision', 'capacity', 'window_micros', 'algorithm', 'buckets')
        for j = 1, 5 do
            if not numeric_field(fields[j]) then
                return redis.error_reply('INVALID_POLICY_STATE: metadata numeric field')
            end
        end
        if (fields[1] and (tonumber(fields[1]) < 0 or tonumber(fields[1]) ~= math.floor(tonumber(fields[1]))))
            or (fields[2] and tonumber(fields[2]) <= 0) or (fields[3] and tonumber(fields[3]) <= 0)
            or (fields[4] and (tonumber(fields[4]) < 1 or tonumber(fields[4]) > 3)) then
            return redis.error_reply('INVALID_POLICY_STATE: metadata bounds')
        end
    end
    local state = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros', 'count', 'window_end_micros')
    for j = 1, 4 do
        if not numeric_field(state[j]) then
            return redis.error_reply('INVALID_POLICY_STATE: bucket numeric field')
        end
    end
end
-- No write, including TTL refresh, occurs before ALL revision fences have passed.
if extended then
    for i = 1, band_count do
        local revision = tonumber(ARGV[2 + 5 * band_count + i])
        if revision == nil or revision < 0 or revision ~= math.floor(revision) or revision > 9007199254740991 then
            return redis.error_reply('policy revision must be an exact nonnegative integer')
        end
        revisions[i] = revision
        local fence = tonumber(redis.call('GET', KEYS[2 * band_count + i])) or 0
        local raw = redis.call('HMGET', KEYS[band_count + i], 'revision', 'capacity', 'window_micros', 'algorithm', 'buckets')
        if revision < fence or revision < (tonumber(raw[1]) or 0) then
            return redis.error_reply('STALE_POLICY: revision fence')
        end
        metadata[i] = {capacity = tonumber(raw[2]), window = tonumber(raw[3]), algorithm = tonumber(raw[4]), buckets = tonumber(raw[5])}
    end
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
    if win_micros < 1000 then
        return redis.error_reply("window must be at least 1 ms")
    end
    if permits > capacity then
        return redis.error_reply("permits exceed capacity")
    end
    if alg == ALG_SLIDING_WINDOW and buckets < 2 then
        return redis.error_reply("buckets must be >= 2 for SLIDING_WINDOW")
    end
    if alg == ALG_SLIDING_WINDOW and math.floor(win_micros / buckets) < 1000 then
        return redis.error_reply("sliding window sub-bucket must be at least 1 ms")
    end

    if alg ~= ALG_TOKEN_BUCKET and alg ~= ALG_SLIDING_WINDOW and alg ~= ALG_FIXED_WINDOW then
        return redis.error_reply('unknown algorithm code: ' .. tostring(alg))
    end
    if extended and metadata[i].capacity ~= nil and redis.call('EXISTS', KEYS[i]) == 1
        and (metadata[i].window ~= win_micros or metadata[i].algorithm ~= alg
            or (alg == ALG_SLIDING_WINDOW and metadata[i].buckets ~= nil and metadata[i].buckets ~= buckets)) then
        return redis.error_reply('POLICY_RESET_REQUIRED: algorithm/window change')
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

-- Parses a SLIDING_WINDOW field "<index>@<sub_dur>" (note 7); nil for any other format.
local function sw_field(field)
    local idx, dur = string.match(field, '^(%d+)@(%d+)$')
    if idx == nil then
        return nil, nil
    end
    return tonumber(idx), tonumber(dur)
end

-- HINCRBY parses canonical signed 64-bit integer strings. Validate every SW field
-- before ANY migration write, and compare the increment using decimal strings so
-- IEEE-754 rounding near Long.MAX_VALUE cannot hide an overflow.
local MAX_REDIS_INTEGER = '9223372036854775807'
local function exceeds_redis_integer(value)
    return #value > #MAX_REDIS_INTEGER
        or (#value == #MAX_REDIS_INTEGER and value > MAX_REDIS_INTEGER)
end
local function redis_nonnegative_integer(value)
    return string.match(value, '^%d+$') ~= nil
        and (#value == 1 or string.sub(value, 1, 1) ~= '0')
        and not exceeds_redis_integer(value)
end
local function decimal_sum(left, right)
    local result, carry = '', 0
    local l, r = #left, #right
    while l > 0 or r > 0 or carry > 0 do
        local a = l > 0 and tonumber(string.sub(left, l, l)) or 0
        local b = r > 0 and tonumber(string.sub(right, r, r)) or 0
        local sum = a + b + carry
        result = tostring(sum % 10) .. result
        carry = math.floor(sum / 10)
        l, r = l - 1, r - 1
    end
    return result
end
for i = 1, band_count do
    if algorithms[i] == ALG_SLIDING_WINDOW then
        if not redis_nonnegative_integer(ARGV[1]) then
            return redis.error_reply('INVALID_POLICY_STATE: sliding increment must be a Redis integer')
        end
        local duration = math.floor(windows[i] / bucket_counts[i])
        local current = math.floor(now_micros / duration)
        local field = string.format('%.0f', current) .. '@' .. string.format('%.0f', duration)
        local raw = redis.call('HGETALL', KEYS[i])
        for j = 1, #raw, 2 do
            local index, geometry = sw_field(raw[j])
            if index ~= nil and geometry ~= nil then
                if not redis_nonnegative_integer(raw[j + 1]) then
                    return redis.error_reply('INVALID_POLICY_STATE: sliding count must be a Redis integer')
                end
                if raw[j] == field and exceeds_redis_integer(decimal_sum(raw[j + 1], ARGV[1])) then
                    return redis.error_reply('INVALID_POLICY_STATE: sliding count increment overflow')
                end
            end
        end
    end
end

-- Refresh TTLs for TOKEN_BUCKET and SLIDING_WINDOW keys on the reject path.
-- EXPIRE is a no-op on keys that do not exist yet; FIXED_WINDOW keys are not touched
-- because PEXPIREAT (an absolute timestamp) must not be overridden with a relative one.
local debt_ttls = {}
local function band_ttl(i)
    return math.max(ttl_for_window(windows[i]), debt_ttls[i] or 0)
end
local function refresh_ttls()
    for j = 1, band_count do
        if algorithms[j] == ALG_TOKEN_BUCKET or algorithms[j] == ALG_SLIDING_WINDOW then
            redis.call('EXPIRE', KEYS[j], band_ttl(j))
            if extended then redis.call('EXPIRE', KEYS[band_count + j], band_ttl(j)) end
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
local sw_raw     = {}   -- raw HGETALL output, reused in pass 2 to avoid a second round-trip

-- FIXED_WINDOW
local fw_counts  = {}   -- current counter value
local fw_ends    = {}   -- resolved window end (micros)

-- Refill using the STORED rate before a capacity transition, then carry signed usage debt.
-- Separate whole windows from the remainder so elapsed*capacity stays within the Java guard.
local function refill(tokens, last_refill, capacity, window)
    local elapsed = math.max(0, now_micros - last_refill)
    local full_windows = math.floor(elapsed / window)
    local remainder = elapsed - full_windows * window
    local to_add = full_windows * capacity + math.floor(remainder * capacity / window)
    local refilled = math.min(capacity, tokens + to_add)
    local next_refill = last_refill
    if to_add > 0 then next_refill = last_refill + math.floor(to_add / capacity * window) end
    if refilled >= capacity then next_refill = now_micros end
    return refilled, next_refill
end

if extended then
    -- Compute EVERY migration and its debt lifetime without writing. An invalid later
    -- transition must leave earlier bucket state, metadata, fences and TTLs unchanged.
    local changed_bands = {}
    for i = 1, band_count do
        if algorithms[i] == ALG_TOKEN_BUCKET then
            local raw = redis.call('HMGET', KEYS[i], 'tokens', 'last_refill_micros')
            local tokens, timestamp = tonumber(raw[1]), tonumber(raw[2])
            local old_capacity = metadata[i].capacity or capacities[i]
            local old_window = metadata[i].window or windows[i]
            if tokens ~= nil and timestamp ~= nil then
                local refilled, next_refill = refill(tokens, timestamp, old_capacity, old_window)
                local changed = old_capacity ~= capacities[i]
                if changed then
                    refilled = refilled + capacities[i] - old_capacity
                    next_refill = now_micros
                end
                tb_tokens[i], tb_refills[i] = refilled, next_refill
                changed_bands[i] = changed
                local recovery = math.max(0, capacities[i] - refilled) / capacities[i] * windows[i]
                local required_ttl = math.max(1, math.ceil(recovery / 1000000 * 1.1))
                if changed and required_ttl > max_ttl_seconds then
                    return redis.error_reply('POLICY_RESET_REQUIRED: usage debt exceeds maximum bucket TTL')
                end
                debt_ttls[i] = math.min(max_ttl_seconds, required_ttl)
            end
        end
    end
    if not check_only then
        for i = 1, band_count do
            if changed_bands[i] then
                -- Configuration migration persists on quota denial, independently of consumption.
                redis.call('HMSET', KEYS[i], 'tokens', string.format('%.0f', tb_tokens[i]),
                    'last_refill_micros', string.format('%.0f', tb_refills[i]))
            end
            if algorithms[i] == ALG_TOKEN_BUCKET or algorithms[i] == ALG_SLIDING_WINDOW then
                redis.call('EXPIRE', KEYS[i], band_ttl(i))
            end
            redis.call('HMSET', KEYS[band_count + i], 'revision', string.format('%.0f', revisions[i]),
                'capacity', string.format('%.0f', capacities[i]), 'window_micros', string.format('%.0f', windows[i]),
                'algorithm', algorithms[i], 'buckets', bucket_counts[i])
            redis.call('EXPIRE', KEYS[band_count + i], band_ttl(i))
            redis.call('SET', KEYS[2 * band_count + i], string.format('%.0f', revisions[i]), 'EX', max_ttl_seconds)
        end
    end
end

-- The rejecting band with the longest wait so far (note 10): its result array, or nil.
local rejection = nil
local function reject(i, remaining, wait, reset_millis)
    if rejection == nil or wait > rejection[4] then
        rejection = {0, i, remaining, wait, reset_millis, capacities[i], i, now_micros}
    end
end

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

        local refilled, next_refill
        if tb_tokens[i] ~= nil then
            refilled, next_refill = tb_tokens[i], tb_refills[i]
        else
            refilled, next_refill = refill(cur_tokens, last_refill, capacity, win_micros)
        end

        tb_tokens[i]  = refilled
        tb_refills[i] = next_refill

        if refilled < permits then
            local tokens_needed = permits - refilled
            local wait          = math.ceil(tokens_needed * win_micros / capacity)
            local deficit       = capacity - refilled
            local full_micros   = deficit > 0 and math.ceil(deficit * win_micros / capacity) or 0
            local reset_millis  = math.floor((now_micros + full_micros) / 1000)
            reject(i, math.max(0, refilled), wait, reset_millis)
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

        local total   = 0
        local counted = {}   -- {index, count} of every sub-bucket that counts

        for fi = 1, #raw, 2 do
            local idx, dur = sw_field(raw[fi])
            local cnt = tonumber(raw[fi + 1]) or 0
            -- note 7: only this geometry, only sub-buckets inside [oldest_val, cur_sub]
            if idx ~= nil and dur == sub_dur and cnt > 0
                    and idx >= oldest_val and idx <= cur_sub then
                total = total + cnt
                counted[#counted + 1] = {idx, cnt}
            end
        end

        sw_sums[i] = total

        if total + permits > capacity then
            local remaining = math.max(0, capacity - total)
            -- micros_to_wait (note 11): sub-bucket k stops counting at (k + buckets) * sub_dur, and
            -- the sub-buckets leave oldest first. Wait for the first one whose departure frees
            -- enough: total - freed + permits <= capacity. permits <= capacity, so the newest
            -- one (at the latest cur_sub, i.e. a full window) always frees enough.
            table.sort(counted, function(a, b) return a[1] < b[1] end)
            local free_at = cur_sub
            local freed   = 0
            for _, entry in ipairs(counted) do
                freed = freed + entry[2]
                if total - freed + permits <= capacity then
                    free_at = entry[1]
                    break
                end
            end
            local wait = math.max(0, (free_at + buckets) * sub_dur - now_micros)
            -- reset_time_millis = when everything counted now has left the window, rounded UP to
            -- the millisecond (sub_dur need not be a whole millisecond): never before the retry
            -- above is allowed.
            local reset_millis = math.ceil((cur_sub + buckets) * sub_dur / 1000)
            reject(i, remaining, wait, reset_millis)
        end

    -- ---- FIXED_WINDOW ----
    elseif alg == ALG_FIXED_WINDOW then
        local win_end = win_ends_in[i]
        if win_end == nil or win_end == 0 then
            -- Align to window_micros boundaries since epoch.
            local win_idx = math.floor(now_micros / win_micros)
            win_end = (win_idx + 1) * win_micros
        end

        -- A missing key (expired or never written) is an empty window; see note 6 for how the
        -- stored window end decides whether the stored count still applies.
        local stored    = redis.call('HMGET', KEYS[i], 'count', 'window_end_micros')
        local count     = tonumber(stored[1]) or 0
        local stored_end = tonumber(stored[2])
        if stored_end == nil or stored_end < win_end then
            count = 0
        elseif stored_end > win_end then
            win_end = stored_end
        end
        fw_ends[i]   = win_end
        fw_counts[i] = count

        if count + permits > capacity then
            -- A counter must never be left without a TTL, or it would stay resident for good.
            if redis.call('PTTL', KEYS[i]) == -1 then
                redis.call('PEXPIREAT', KEYS[i], string.format('%.0f', fixed_window_expire_millis(win_end)))
            end
            local remaining    = math.max(0, capacity - count)
            local wait         = math.max(0, win_end - now_micros)
            local reset_millis = math.floor(win_end / 1000)
            reject(i, remaining, wait, reset_millis)
        end

    else
        return redis.error_reply("unknown algorithm code: " .. tostring(alg))
    end
end

if rejection ~= nil then
    refresh_ttls()
    return rejection
end

if check_only then
    -- Note 9: every band would serve the request; report the most restrictive one, unconsumed.
    local binding, binding_remaining = 1, nil
    for i = 1, band_count do
        local remaining
        if algorithms[i] == ALG_TOKEN_BUCKET then
            remaining = tb_tokens[i]
        elseif algorithms[i] == ALG_SLIDING_WINDOW then
            remaining = capacities[i] - sw_sums[i]
        else
            remaining = capacities[i] - fw_counts[i]
        end
        if binding_remaining == nil or remaining < binding_remaining then
            binding, binding_remaining = i, remaining
        end
    end
    return {1, 0, binding_remaining, 0, 0, capacities[binding], binding, now_micros}
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
        redis.call('EXPIRE', KEYS[i], band_ttl(i))
        tb_tokens[i] = remaining   -- updated for reset_time_millis calculation below

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end

    -- ---- SLIDING_WINDOW ----
    elseif alg == ALG_SLIDING_WINDOW then
        local cur_sub    = sw_cur_sub[i]
        local sub_dur    = sw_sub_dur[i]
        local oldest_val = cur_sub - bucket_counts[i] + 1
        local raw        = sw_raw[i]

        -- Delete, in one HDEL call, every field pass 1 did not count (note 7): expired
        -- sub-buckets, sub-buckets after the current one, and fields of another geometry.
        local to_del = {}
        for fi = 1, #raw, 2 do
            local idx, dur = sw_field(raw[fi])
            if idx == nil or dur ~= sub_dur or idx < oldest_val or idx > cur_sub then
                to_del[#to_del + 1] = raw[fi]
            end
        end
        if #to_del > 0 then
            redis.call('HDEL', KEYS[i], unpack_f(to_del))
        end

        redis.call('HINCRBY', KEYS[i],
            string.format('%.0f', cur_sub) .. '@' .. string.format('%.0f', sub_dur), ARGV[1])
        redis.call('EXPIRE', KEYS[i], band_ttl(i))

        local remaining = capacity - sw_sums[i] - permits

        if binding_remaining == nil or remaining < binding_remaining then
            binding           = i
            binding_remaining = remaining
        end

    -- ---- FIXED_WINDOW ----
    elseif alg == ALG_FIXED_WINDOW then
        local win_end = fw_ends[i]
        redis.call('HSET', KEYS[i],
            'count',             string.format('%.0f', fw_counts[i] + permits),
            'window_end_micros', string.format('%.0f', win_end))
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
    -- End of the current sub-bucket cycle (when all requests in current sub expire), rounded UP
    -- to the millisecond like the reject path.
    local cur_sub  = sw_cur_sub[binding]
    local buckets  = bucket_counts[binding]
    local sub_dur  = sw_sub_dur[binding]
    reset_millis = math.ceil((cur_sub + buckets) * sub_dur / 1000)

elseif binding_alg == ALG_FIXED_WINDOW then
    reset_millis = math.floor(fw_ends[binding] / 1000)
end

return {1, 0, binding_remaining, 0, reset_millis, binding_capacity, binding, now_micros}
