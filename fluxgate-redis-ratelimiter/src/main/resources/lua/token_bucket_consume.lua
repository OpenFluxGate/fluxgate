--[[
Token Bucket Refill + Consume (Atomic)

KEYS[1] = bucket key (e.g., "fluxgate:rule-1:192.168.1.1:per-second")
ARGV[1] = capacity (max tokens)
ARGV[2] = refill_rate (tokens per nanosecond)
ARGV[3] = window_nanos (window duration in nanoseconds)
ARGV[4] = permits (number of tokens to consume)
ARGV[5] = current_time_nanos (current timestamp in nanoseconds)

Returns:
  [consumed, remaining_tokens, nanos_to_wait]
  - consumed: 1 if allowed, 0 if rejected
  - remaining_tokens: tokens left after consumption (or before if rejected)
  - nanos_to_wait: nanoseconds until next refill (0 if consumed)
]]

local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local window_nanos = tonumber(ARGV[3])
local permits = tonumber(ARGV[4])
local current_time_nanos = tonumber(ARGV[5])

-- Get current bucket state
local bucket_data = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_nanos')
local current_tokens = tonumber(bucket_data[1])
local last_refill_nanos = tonumber(bucket_data[2])

-- Initialize if bucket doesn't exist
if current_tokens == nil or last_refill_nanos == nil then
    current_tokens = capacity
    last_refill_nanos = current_time_nanos
end

-- Calculate elapsed time since last refill
local elapsed_nanos = current_time_nanos - last_refill_nanos

-- Calculate tokens to add based on elapsed time
local tokens_to_add = 0
if elapsed_nanos > 0 and refill_rate > 0 then
    tokens_to_add = math.floor(elapsed_nanos * refill_rate)
end

-- Refill tokens (but don't exceed capacity)
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Try to consume permits
if new_tokens >= permits then
    -- Consumption successful
    new_tokens = new_tokens - permits

    -- Update bucket state
    redis.call('HMSET', bucket_key,
        'tokens', new_tokens,
        'last_refill_nanos', current_time_nanos
    )

    -- Set TTL (convert nanos to seconds, round up)
    local ttl_seconds = math.ceil(window_nanos / 1000000000)
    redis.call('EXPIRE', bucket_key, ttl_seconds)

    -- Return: consumed=1, remaining_tokens, nanos_to_wait=0
    return {1, new_tokens, 0}
else
    -- Consumption failed (not enough tokens)
    -- Calculate how long to wait for enough tokens
    local tokens_needed = permits - new_tokens
    local nanos_to_wait = 0

    if refill_rate > 0 then
        nanos_to_wait = math.ceil(tokens_needed / refill_rate)
    else
        -- If no refill rate, wait for full window
        nanos_to_wait = window_nanos
    end

    -- Update last_refill time even on rejection to track state
    redis.call('HMSET', bucket_key,
        'tokens', new_tokens,
        'last_refill_nanos', current_time_nanos
    )

    -- Set TTL
    local ttl_seconds = math.ceil(window_nanos / 1000000000)
    redis.call('EXPIRE', bucket_key, ttl_seconds)

    -- Return: consumed=0, remaining_tokens, nanos_to_wait
    return {0, new_tokens, nanos_to_wait}
end
