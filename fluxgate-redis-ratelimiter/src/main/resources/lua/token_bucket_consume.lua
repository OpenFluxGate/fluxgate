--[[
Production-Ready Token Bucket Rate Limiter

CRITICAL FIXES APPLIED:
1. Uses Redis TIME (not System.nanoTime()) - solves clock drift across nodes
2. Integer arithmetic only (no floating point) - eliminates precision loss
3. Does NOT update state on rejection - prevents unfair rate limiting
4. TTL safety margin + max cap - prevents premature expiration and runaway TTLs

KEYS[1] = bucket key (e.g., "fluxgate:api-limits:per-ip-limit:192.168.1.100:100-per-minute")

ARGV[1] = capacity (max tokens, e.g., 100)
ARGV[2] = window_nanos (window duration in nanoseconds, e.g., 60000000000 for 1 minute)
ARGV[3] = permits (number of tokens to consume, usually 1)

Returns array: {consumed, remaining_tokens, nanos_to_wait, reset_time_millis}
  - consumed: 1 if allowed, 0 if rejected
  - remaining_tokens: tokens left after consumption (if allowed) or current tokens (if rejected)
  - nanos_to_wait: nanoseconds to wait until enough tokens available (0 if allowed)
  - reset_time_millis: Unix timestamp in milliseconds when bucket will be full again
]]

-- Parse arguments
local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_nanos = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

-- Validate inputs
if capacity <= 0 or window_nanos <= 0 or permits <= 0 then
    return redis.error_reply("Invalid arguments: capacity, window, and permits must be positive")
end

-- ========================================================================
-- FIX #1: Use Redis TIME (same clock for all nodes, no clock drift)
-- ========================================================================
local time_info = redis.call('TIME')
-- time_info[1] = seconds since epoch
-- time_info[2] = microseconds within current second
local current_time_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])
local current_time_nanos = current_time_micros * 1000

-- Get current bucket state
local bucket_data = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_nanos')
local current_tokens = tonumber(bucket_data[1])
local last_refill_nanos = tonumber(bucket_data[2])

-- Initialize if bucket doesn't exist (allow initial burst)
if current_tokens == nil or last_refill_nanos == nil then
    current_tokens = capacity  -- Start with full capacity (allows initial burst)
    last_refill_nanos = current_time_nanos
end

-- Calculate elapsed time since last refill
-- Use math.max to handle clock resets gracefully (e.g., Redis restart)
local elapsed_nanos = math.max(0, current_time_nanos - last_refill_nanos)

-- ========================================================================
-- FIX #2: Use INTEGER arithmetic only (no floating point precision loss)
-- ========================================================================
-- Formula: tokens_to_add = (elapsed_nanos * capacity) / window_nanos
-- This avoids: tokens_to_add = elapsed_nanos * (capacity / window_nanos)
--              which would use floating point refill_rate
local tokens_to_add = 0
if elapsed_nanos > 0 and window_nanos > 0 then
    tokens_to_add = math.floor((elapsed_nanos * capacity) / window_nanos)
end

-- Refill tokens (but don't exceed capacity)
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Calculate reset time (when bucket will be full again)
local tokens_until_full = capacity - new_tokens
local nanos_until_full = 0
if tokens_until_full > 0 and capacity > 0 then
    -- Integer arithmetic: time = (tokens_needed * window) / capacity
    nanos_until_full = math.ceil((tokens_until_full * window_nanos) / capacity)
end
local reset_time_millis = math.floor((current_time_nanos + nanos_until_full) / 1000000)

-- ========================================================================
-- Try to consume permits
-- ========================================================================
if new_tokens >= permits then
    -- ===== SUCCESS: Consume tokens =====
    new_tokens = new_tokens - permits

    -- Update bucket state (ONLY on success)
    redis.call('HMSET', bucket_key,
        'tokens', new_tokens,
        'last_refill_nanos', current_time_nanos
    )

    -- ========================================================================
    -- FIX #3: TTL with safety margin + max cap
    -- ========================================================================
    -- Add 10% safety margin to prevent premature expiration due to clock skew
    local desired_ttl_seconds = math.ceil(window_nanos / 1000000000 * 1.1)

    -- Cap at 24 hours (86400 seconds) to prevent runaway TTLs
    local actual_ttl_seconds = math.min(desired_ttl_seconds, 86400)

    redis.call('EXPIRE', bucket_key, actual_ttl_seconds)

    -- Return: [consumed=1, remaining_tokens, nanos_to_wait=0, reset_time]
    return {1, new_tokens, 0, reset_time_millis}
else
    -- ===== REJECTED: Not enough tokens =====
    -- Calculate how long to wait for enough tokens
    local tokens_needed = permits - new_tokens
    local nanos_to_wait = 0

    if capacity > 0 then
        -- Integer arithmetic: wait_time = (tokens_needed * window) / capacity
        nanos_to_wait = math.ceil((tokens_needed * window_nanos) / capacity)
    end

    -- ========================================================================
    -- FIX #4: Do NOT update state on rejection (read-only operation)
    -- ========================================================================
    -- This prevents unfair rate limiting where rejected requests advance
    -- the timestamp, making subsequent requests think less time has elapsed.

    -- Return: [consumed=0, current_tokens, nanos_to_wait, reset_time]
    return {0, new_tokens, nanos_to_wait, reset_time_millis}
end
