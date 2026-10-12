# FluxGate Base Knowledge

This document explains the foundational knowledge you need to understand FluxGate.

[< Back to Architecture Overview](README.md) | [한국어](../../ko/architecture/base-knowledge.ko.md)

---

## Table of Contents

1. [Rate Limiting Algorithms](#1-rate-limiting-algorithms)
2. [Redis Lua Scripts](#2-redis-lua-scripts)
3. [Redis Pub/Sub](#3-redis-pubsub)
4. [Caffeine Cache](#4-caffeine-cache)
5. [Spring Filter Architecture](#5-spring-filter-architecture)
6. [Token Bucket vs Leaky Bucket](#6-token-bucket-vs-leaky-bucket)

---

## 1. Rate Limiting Algorithms

### 1.1 Why Rate Limiting?

```
The problem:
─────────────────────────────────────────────────
Malicious user or buggy client
    │
    ▼ 10,000 requests per second
┌─────────────────┐
│   API Server    │ ← overloaded and down!
└─────────────────┘
    │
    ▼ legitimate users lose service too

The solution:
─────────────────────────────────────────────────
Every request
    │
    ▼
┌─────────────────┐
│  Rate Limiter   │ ← allow only 100 per second
└─────────────────┘
    │
    ├─ allowed → API Server
    └─ rejected → 429 Too Many Requests
```

### 1.2 Comparison of the Major Algorithms

#### Fixed Window

```
Time: 00:00 ──────────────────────────────────► 02:00
      │      Window 1      │      Window 2      │
      │    00:00-01:00     │    01:00-02:00     │
      │                    │                    │
      │  100 requests OK   │  100 requests OK   │
      │  101st → rejected  │  101st → rejected  │
```

**Pros:** simple to implement, memory efficient

**Cons:** boundary problem
```
      100 requests at 00:59 + 100 requests at 01:00
           │              │
           ▼              ▼
      ┌─────────────────────────┐
      │  200 requests in 1 min! │ ← twice the intended limit
      └─────────────────────────┘
```

---

#### Sliding Window Log

```
Current time: 01:30
Window size: 1 hour
──────────────────────────────────────────────────►
    │                                        │
  00:30                                    01:30
    └───── count only requests in this range ─┘

Stored timestamps:
[00:25, 00:45, 01:00, 01:15, 01:25]
    │
    ▼ drop everything before 00:30
[01:00, 01:15, 01:25] → current count: 3
```

**Pros:** exact rate limiting

**Cons:** high memory usage (stores every request timestamp)

---

#### Sliding Window Counter

```
Now: 01:15 (25% into the current window)

┌─────────────────┬─────────────────┐
│  Previous window│  Current window │
│  00:00-01:00    │  01:00-02:00    │
│                 │                 │
│  80 requests    │  20 requests    │
│  × 75% = 60     │  × 100% = 20    │
└─────────────────┴─────────────────┘
                  ▲
               01:15

Estimated count = 60 + 20 = 80
```

**Pros:** balances accuracy and efficiency

**Cons:** not perfectly exact (an approximation)

---

#### Token Bucket ⭐ chosen by FluxGate

```
┌─────────────────────────────────────────────────────┐
│                   Token Bucket                       │
│                                                      │
│   Capacity: 100 tokens                               │
│   Refill rate: 10 tokens/sec                         │
│                                                      │
│   ┌───────────────────────────────────────────┐     │
│   │ ● ● ● ● ● ● ● ○ ○ ○   (70 right now)     │     │
│   └───────────────────────────────────────────┘     │
│            │                      ▲                  │
│            ▼                      │                  │
│   consume 1 per request      refills over time       │
│                                                      │
│   tokens > 0  → allowed ✓                            │
│   tokens = 0  → rejected ✗                           │
└─────────────────────────────────────────────────────┘
```

**Characteristics:**
- **Allows bursts**: up to capacity can be spent at once
- **Limits the average rate**: converges to the refill rate over time
- **Memory efficient**: stores only the token count and the last refill time

---

#### Leaky Bucket

```
┌─────────────────────────────────────────────────────┐
│                   Leaky Bucket                       │
│                                                      │
│   Request arrives                                    │
│       ↓                                              │
│   ┌───────────────────────────────────────────┐     │
│   │ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣   (queued)                │     │
│   └───────────────────────────────────────────┘     │
│                       │                              │
│                       ↓ processed at a steady rate   │
│                   ─────────                          │
│                    processed                         │
│                                                      │
│   queue full → new requests rejected                 │
└─────────────────────────────────────────────────────┘
```

**Characteristics:**
- **Constant output rate**: traffic shaping
- **Adds latency**: requests must wait in the queue

---

### 1.3 Algorithm Selection Guide

| Situation | Recommended | Why |
|-----------|-------------|-----|
| API billing (exact counts) | Sliding Window Log | accuracy first |
| General API rate limiting | **Token Bucket** | bursts allowed + simple |
| Protecting a DB, flattening traffic | Leaky Bucket | constant throughput |
| Simplest possible implementation | Fixed Window | easy to build |

---

## 2. Redis Lua Scripts

### 2.1 Why Lua Scripts?

**The problem: race conditions**

```
Server A                        Server B
   │                               │
   ├─ GET tokens → 5               │
   │                               ├─ GET tokens → 5
   ├─ tokens - 1 = 4               │
   │                               ├─ tokens - 1 = 4
   ├─ SET tokens 4                 │
   │                               ├─ SET tokens 4
   ▼                               ▼

Result: two consumed but only one deducted! (bug)
```

**The solution: a Lua script = atomic execution**

```
Server A                        Redis (single-threaded)
   │                               │
   ├─ EVALSHA script ─────────────▶│ runs the whole script
   │                               │ (nothing interleaves)
   │◀─────────── result ───────────┤
   │                               │
Server B                           │
   ├─ EVALSHA script ─────────────▶│ next script runs
   │                               │
```

Redis is **single-threaded**, so a Lua script executes **atomically**.

---

### 2.2 Basic Lua Syntax

```lua
-- variable declaration
local count = 10
local name = "fluxgate"

-- conditionals
if count > 5 then
    return "high"
elseif count > 0 then
    return "low"
else
    return "zero"
end

-- loops
for i = 1, 10 do
    print(i)
end

-- functions
local function add(a, b)
    return a + b
end

-- tables (arrays/maps)
local arr = {1, 2, 3}           -- array (1-indexed!)
local map = {name = "flux"}     -- map

print(arr[1])      -- 1 (Lua starts at 1!)
print(map.name)    -- "flux"
```

---

### 2.3 Using Lua in Redis

```lua
-- KEYS: Redis keys the script operates on
-- ARGV: arguments passed to the script

-- Example: EVALSHA sha1 1 mykey 10
-- KEYS[1] = "mykey"
-- ARGV[1] = "10"

local key = KEYS[1]
local increment = tonumber(ARGV[1])

-- calling Redis commands
local current = redis.call('GET', key)
current = tonumber(current) or 0

local new_value = current + increment
redis.call('SET', key, new_value)

return new_value
```

---

### 2.4 EVAL vs EVALSHA

```
EVAL (slower)
─────────────
sends the entire script every time

Client: EVAL "local x = redis.call('GET', KEYS[1]) ..." 1 mykey
                    └─────── long script ───────┘


EVALSHA (faster) ← used by FluxGate
──────────────
1. once: SCRIPT LOAD "script..." → SHA: "a1b2c3..."
2. afterwards: EVALSHA "a1b2c3..." 1 mykey
                 └─ only a 40-byte hash travels
```

---

### 2.5 FluxGate's Token Bucket Lua Script

```lua
-- token_bucket_consume.lua, reduced to ONE TOKEN_BUCKET band (simplified).
-- The real script takes every band of a rule in one call (KEYS[1..n], one hash tag),
-- checks all of them before writing any, and also handles SLIDING_WINDOW and FIXED_WINDOW.
-- ARGV[1] = permits, ARGV[2] = max_bucket_ttl_seconds, then per band:
-- capacity, window_micros, algorithm_code, buckets_or_zero, window_end_or_zero

local key            = KEYS[1]
local permits        = tonumber(ARGV[1])
local max_ttl        = tonumber(ARGV[2])
local capacity       = tonumber(ARGV[3])
local window_micros  = tonumber(ARGV[4])

-- use Redis server time (avoids clock drift between application nodes)
local time_info  = redis.call('TIME')
local now_micros = tonumber(time_info[1]) * 1000000 + tonumber(time_info[2])
local ttl = math.min(max_ttl, math.max(1, math.ceil(window_micros / 1000000 * 1.1)))

-- read the current state; a missing bucket starts full
local data        = redis.call('HMGET', key, 'tokens', 'last_refill_micros')
local tokens      = tonumber(data[1])
local last_refill = tonumber(data[2])
if tokens == nil or last_refill == nil then
    tokens, last_refill = capacity, now_micros
end

-- refill whole tokens only; the timestamp advances only by what those tokens cost,
-- so the sub-token remainder carries over to the next call
local elapsed = math.min(math.max(0, now_micros - last_refill), window_micros)
local to_add  = math.floor(elapsed * capacity / window_micros)
if to_add > 0 then
    last_refill = last_refill + math.floor(to_add * window_micros / capacity)
end
tokens = math.min(capacity, tokens + to_add)
if tokens >= capacity then last_refill = now_micros end

if tokens < permits then
    -- rejected: nothing is written, only the TTL of an existing bucket is refreshed
    redis.call('EXPIRE', key, ttl)
    local wait = math.ceil((permits - tokens) * window_micros / capacity)
    return {0, tokens, wait}
end

tokens = tokens - permits
redis.call('HMSET', key, 'tokens', string.format('%.0f', tokens),
                         'last_refill_micros', string.format('%.0f', last_refill))
redis.call('EXPIRE', key, ttl)   -- min(max-bucket-ttl, max(1, ceil(window × 1.1)))
return {1, tokens, 0}
```

The return value is shortened here; the real script returns eight values (allowed, rejecting band,
remaining, wait, reset time, limit, binding band, Redis time). See
[Lua Script Flow](README.md#lua-script-flow) and `fluxgate-redis-ratelimiter/README.md` for the
full contract.

---

### 2.6 NOSCRIPT Error Handling

```
Problem: a Redis restart wipes the script cache

EVALSHA "a1b2c3..." → NOSCRIPT error!

Solution (FluxGate's implementation):
1. try EVALSHA
2. NOSCRIPT error raised
3. fall back to EVAL (still works)
4. load the script again (EVALSHA from the next request on)
```

---

## 3. Redis Pub/Sub

### 3.1 Concept

```
┌─────────────────────────────────────────────────────────────┐
│                        Redis Server                          │
│                                                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              Channel: "fluxgate:rule-reload"         │   │
│   └─────────────────────────────────────────────────────┘   │
│         ▲                    │                              │
│         │ PUBLISH            │ message delivery             │
│         │                    ▼                              │
└─────────┼────────────────────┼──────────────────────────────┘
          │                    │
    ┌─────┴─────┐    ┌────────┴────────┐
    │ Publisher │    │   Subscribers    │
    │ (Admin)   │    │ (App Instances)  │
    └───────────┘    │                  │
                     │  Instance 1      │
                     │  Instance 2      │
                     │  Instance 3      │
                     └──────────────────┘
```

### 3.2 Usage Example

**Publisher (publish a message)**
```bash
PUBLISH fluxgate:rule-reload '{"ruleSetId": "api-limits", "action": "RELOAD"}'
```

**Subscriber (subscribe to messages)**
```bash
SUBSCRIBE fluxgate:rule-reload
# messages are received automatically as they arrive
```

### 3.3 How FluxGate Uses It

```
Admin changes a rule
       │
       ▼
┌─────────────────────┐
│ PUBLISH a message   │
│ Channel: rule-reload│
└─────────────────────┘
       │
       ▼ Redis delivers to every subscriber
       │
┌──────┴──────┬──────────────┐
▼             ▼              ▼
Instance 1  Instance 2  Instance 3
    │             │            │
    ▼             ▼            ▼
refresh cache  refresh cache  refresh cache
reset buckets  reset buckets  reset buckets
```

### 3.4 Pub/Sub vs Polling

| Approach | Pros | Cons |
|----------|------|------|
| **Polling** | simple to implement | latency (the polling interval) |
| **Pub/Sub** | real-time synchronization | connection management required |

```yaml
# FluxGate configuration
fluxgate:
  reload:
    strategy: PUBSUB         # or POLLING
    polling:
      interval: 30s          # used by POLLING
```

---

## 4. Caffeine Cache

### 4.1 Why a Cache?

```
Without a cache:
─────────────────────────────────────────
request 1 → MongoDB query (10ms)
request 2 → MongoDB query (10ms)
request 3 → MongoDB query (10ms)
...
request 1000 → MongoDB query (10ms)
total: 10,000ms

With a cache:
─────────────────────────────────────────
request 1 → MongoDB query (10ms) → cached
request 2 → cache hit (0.1ms)
request 3 → cache hit (0.1ms)
...
request 1000 → cache hit (0.1ms)
total: ~110ms (90× faster)
```

### 4.2 What Is Caffeine?

A high-performance in-process caching library for Java.

```java
Cache<String, RateLimitRuleSet> cache = Caffeine.newBuilder()
    .maximumSize(1000)                    // at most 1000 entries
    .expireAfterWrite(Duration.ofMinutes(5))  // expire 5 minutes after write
    .recordStats()                        // collect statistics
    .build();

// store
cache.put("api-limits", ruleSet);

// look up
RateLimitRuleSet cached = cache.getIfPresent("api-limits");

// load when absent
RateLimitRuleSet loaded = cache.get("api-limits", key -> loadFromDB(key));
```

### 4.3 Eviction Policies

```
┌───────────────────────────────────────────────────────────┐
│                    Caffeine Cache                          │
│                                                            │
│   Eviction policies:                                       │
│                                                            │
│   1. Size-based                                            │
│      └─ maximumSize(1000) → evict beyond 1000 entries      │
│                                                            │
│   2. Time-based                                            │
│      ├─ expireAfterWrite(5min) → expire 5 min after write  │
│      └─ expireAfterAccess(5min) → expire 5 min after access│
│                                                            │
│   3. Reference-based                                      │
│      └─ weakKeys(), weakValues() → GC can collect them     │
│                                                            │
│   Eviction algorithm: Window TinyLFU (high hit rate)       │
└───────────────────────────────────────────────────────────┘
```

### 4.4 Window TinyLFU

Caffeine's core algorithm.

```
Traditional LRU (Least Recently Used):
─────────────────────────────────────
Evict the entry unused for the longest time
Problem: even a frequently used entry is evicted
after a short idle period

Window TinyLFU:
─────────────────────────────────────
Combines frequency + recency
for a smarter eviction decision

┌─────────────────────────────────────────────────┐
│                Window TinyLFU                    │
│                                                  │
│  ┌─────────┐     ┌─────────────────────────┐   │
│  │ Window  │────▶│      Main Cache          │   │
│  │ (1%)    │     │       (99%)              │   │
│  └─────────┘     └─────────────────────────┘   │
│       │                    │                    │
│       ▼                    ▼                    │
│  new entries enter    frequency-based eviction  │
│                     (TinyLFU sketch)            │
└─────────────────────────────────────────────────┘
```

### 4.5 Usage in FluxGate

```java
// FluxGate's RuleCache implementation (simplified)
@Component
public class CaffeineRuleCache implements RuleCache {

    private final Cache<String, RateLimitRuleSet> cache;

    public CaffeineRuleCache(FluxgateProperties props) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(props.getCache().getMaxSize())      // default 1000
            .expireAfterWrite(props.getCache().getTtl())     // default 5 minutes
            .recordStats()
            .build();
    }

    @Override
    public Optional<RateLimitRuleSet> get(String ruleSetId) {
        return Optional.ofNullable(cache.getIfPresent(ruleSetId));
    }

    @Override
    public void put(String ruleSetId, RateLimitRuleSet ruleSet) {
        cache.put(ruleSetId, ruleSet);
    }

    @Override
    public void invalidate(String ruleSetId) {
        cache.invalidate(ruleSetId);  // called on hot reload
    }
}
```

---

## 5. Spring Filter Architecture

### 5.1 The Filter Chain Concept

```
HTTP request
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│                      Filter Chain                            │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Filter 1 │─▶│ Filter 2 │─▶│ Filter 3 │─▶│ Servlet  │    │
│  │ (auth)   │  │ (Rate   │  │ (logging)│  │(Controller)│   │
│  │          │  │  Limit) │  │          │  │          │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│       │              │             │             │          │
│       ▼              ▼             ▼             ▼          │
│   doFilter()    doFilter()    doFilter()    service()       │
│       │              │             │             │          │
│       ◀──────────────◀─────────────◀─────────────┘          │
│                   response returns                           │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
HTTP response
```

### 5.2 The Filter Interface

```java
public interface Filter {

    default void init(FilterConfig filterConfig) throws ServletException {}

    void doFilter(
        ServletRequest request,
        ServletResponse response,
        FilterChain chain
    ) throws IOException, ServletException;

    default void destroy() {}
}
```

### 5.3 Filter Execution Flow

```java
public class MyFilter implements Filter {

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        // ===== pre-processing (request coming in) =====
        System.out.println("Before request processing");

        // pass to the next filter (or the servlet)
        chain.doFilter(request, response);

        // ===== post-processing (response going out) =====
        System.out.println("After request processing");
    }
}
```

```
Execution order:
─────────────────────────────────────────
request → Filter1 pre-processing
              → Filter2 pre-processing
                     → Filter3 pre-processing
                            → Servlet handles it
                     ← Filter3 post-processing
              ← Filter2 post-processing
     ← Filter1 post-processing
← response
```

### 5.4 How to Register a Filter

**Option 1: @Component (Spring Boot)**
```java
@Component
@Order(1)  // specify the order
public class RateLimitFilter implements Filter {
    // ...
}
```

**Option 2: FilterRegistrationBean**
```java
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>();

        registration.setFilter(new RateLimitFilter());
        registration.addUrlPatterns("/api/*");  // only these paths
        registration.setOrder(1);

        return registration;
    }
}
```

### 5.5 The FluxGate Rate Limit Filter

```java
public class FluxgateRateLimitFilter implements Filter {

    private final FluxgateRateLimitHandler handler;
    private final List<String> includePatterns;
    private final List<String> excludePatterns;

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // 1. check the exclude patterns
        if (shouldExclude(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. build the RequestContext
        RequestContext context = RequestContext.builder()
            .clientIp(getClientIp(httpRequest))
            .method(httpRequest.getMethod())
            .endpoint(path)
            .build();

        // 3. run the rate limit check
        RateLimitResponse result = handler.tryConsume(context, ruleSetId);

        // 4. add response headers
        httpResponse.setHeader("X-RateLimit-Remaining",
            String.valueOf(result.getRemainingTokens()));

        // 5. allow or reject
        if (result.isAllowed()) {
            chain.doFilter(request, response);  // continue the chain
        } else {
            httpResponse.setStatus(429);  // Too Many Requests
            httpResponse.setHeader("Retry-After",
                String.valueOf(result.getRetryAfterMillis() / 1000));
            httpResponse.getWriter().write("Rate limit exceeded");
        }
    }
}
```

### 5.6 Filter vs Interceptor vs AOP

```
┌────────────────────────────────────────────────────────────────┐
│                     Request processing order                    │
│                                                                │
│  HTTP request                                                  │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                    Filter (Servlet)                       │ │
│  │  - Servlet specification                                  │ │
│  │  - works outside of Spring too                            │ │
│  │  - direct access to Request/Response                      │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                 Interceptor (Spring MVC)                  │ │
│  │  - Spring MVC specification                               │ │
│  │  - access to handler information                          │ │
│  │  - preHandle, postHandle, afterCompletion                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                      AOP (Spring)                         │ │
│  │  - method level                                           │ │
│  │  - close to the business logic                            │ │
│  │  - @Before, @After, @Around                               │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                   Controller                              │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

| | Filter | Interceptor | AOP |
|---|--------|-------------|-----|
| **Specification** | Servlet | Spring MVC | Spring |
| **Level** | HTTP request/response | Handler | method |
| **Can access** | Request, Response | Handler, ModelAndView | JoinPoint |
| **Typical use** | auth, logging, **rate limiting** | authorization, logging | transactions, logging |

**Why FluxGate chose a Filter:**
- blocks early at the HTTP level (no wasted processing)
- direct control over Request/Response
- usable outside Spring

---

## 6. Token Bucket vs Leaky Bucket

### 6.1 Behavior Comparison

```
Scenario: a payments API (10 req/sec limit)
A user fires 10 requests at once at t=0

┌─────────────────────────────────────────────────────────────┐
│ Token Bucket                                                │
├─────────────────────────────────────────────────────────────┤
│ t=0.0: 10 requests → all 10 pass immediately ✅             │
│ t=0.1: 1 request  → no tokens, rejected ❌                  │
│ t=1.0: tokens refill → 10 possible again                    │
│                                                             │
│ Result: the server must handle 10 at once 💥                 │
│ Pro: fast responses for the user                            │
│ Con: possible load spikes on the server                     │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Leaky Bucket                                                │
├─────────────────────────────────────────────────────────────┤
│ t=0.0: 10 requests → 1 passes, 9 queue                      │
│ t=0.1: 1 processed from the queue → 8 waiting               │
│ t=0.2: 1 processed from the queue → 7 waiting               │
│ ...                                                         │
│ t=0.9: 1 processed from the queue → 0 waiting               │
│                                                             │
│ Result: the server always handles one at a time ✅           │
│ Pro: steady server load                                     │
│ Con: user-visible latency (up to 0.9s of waiting)           │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 When to Use Which?

| Situation | Recommended | Why |
|-----------|-------------|-----|
| API Gateway | Token Bucket | fast responses matter for UX |
| DB protection | Leaky Bucket | constant throughput protects the DB |
| Payments API | Token Bucket | immediate feedback needed |
| Batch job queue | Leaky Bucket | flatten the throughput |

### 6.3 FluxGate's Choice

FluxGate chose the **Token Bucket**:

1. **API gateway use case** → fast responses matter
2. **O(1) time complexity** → high performance
3. **Atomic processing via a Redis Lua script**
4. **Multi-band support** (10/sec + 100/min + 1000/hour)

```java
RateLimitRule rule = RateLimitRule.builder("multi-band")
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())    // 10 per second
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())   // 100 per minute
    .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).build())    // 1000 per hour
    .build();
```

---

## Related Documentation

- [Architecture Overview](README.md)
- [RateLimiter Layer](ratelimiter-layer.md)
- [Storage Layer](storage-layer.md)
- [Hot Reload](hot-reload.md)
