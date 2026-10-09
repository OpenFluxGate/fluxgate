# FluxGate 기반 지식

이 문서는 FluxGate를 이해하기 위해 필요한 기반 지식을 설명합니다.

[< 아키텍처 개요로 돌아가기](README.ko.md) | [English](../../en/architecture/base-knowledge.md)

---

## 목차

1. [Rate Limiting 알고리즘](#1-rate-limiting-알고리즘)
2. [Redis Lua 스크립트](#2-redis-lua-스크립트)
3. [Redis Pub/Sub](#3-redis-pubsub)
4. [Caffeine 캐시](#4-caffeine-캐시)
5. [Spring Filter 아키텍처](#5-spring-filter-아키텍처)
6. [Token Bucket vs Leaky Bucket](#6-token-bucket-vs-leaky-bucket)

---

## 1. Rate Limiting 알고리즘

### 1.1 왜 Rate Limiting이 필요한가?

```
문제 상황:
─────────────────────────────────────────────────
악성 사용자 or 버그 있는 클라이언트
    │
    ▼ 초당 10,000 요청
┌─────────────────┐
│   API Server    │ ← 과부하로 다운!
└─────────────────┘
    │
    ▼ 정상 사용자도 서비스 불가

해결:
─────────────────────────────────────────────────
모든 요청
    │
    ▼
┌─────────────────┐
│  Rate Limiter   │ ← 초당 100개만 허용
└─────────────────┘
    │
    ├─ 허용 → API Server
    └─ 거부 → 429 Too Many Requests
```

### 1.2 주요 알고리즘 비교

#### Fixed Window (고정 윈도우)

```
시간: 00:00 ──────────────────────────────────► 02:00
      │      Window 1      │      Window 2      │
      │    00:00-01:00     │    01:00-02:00     │
      │                    │                    │
      │  요청 100개 허용   │  요청 100개 허용   │
      │  101번째 → 거부    │  101번째 → 거부    │
```

**장점:** 구현 단순, 메모리 효율적

**단점:** 경계 문제 (Boundary Problem)
```
      00:59에 100개 + 01:00에 100개
           │              │
           ▼              ▼
      ┌─────────────────────────┐
      │  1분 동안 200개 통과!   │ ← 의도한 제한의 2배
      └─────────────────────────┘
```

---

#### Sliding Window Log (슬라이딩 윈도우 로그)

```
현재 시간: 01:30
윈도우 크기: 1시간
──────────────────────────────────────────────────►
    │                                        │
  00:30                                    01:30
    └─────── 이 구간의 요청만 카운트 ────────┘

저장된 타임스탬프:
[00:25, 00:45, 01:00, 01:15, 01:25]
    │
    ▼ 00:30 이전은 제거
[01:00, 01:15, 01:25] → 현재 카운트: 3
```

**장점:** 정확한 Rate Limiting

**단점:** 메모리 사용량 높음 (모든 요청 타임스탬프 저장)

---

#### Sliding Window Counter (슬라이딩 윈도우 카운터)

```
현재: 01:15 (현재 윈도우의 25% 지점)

┌─────────────────┬─────────────────┐
│  이전 윈도우    │  현재 윈도우    │
│  00:00-01:00    │  01:00-02:00    │
│                 │                 │
│  80개 요청      │  20개 요청      │
│  × 75% = 60개   │  × 100% = 20개  │
└─────────────────┴─────────────────┘
                  ▲
               01:15

예상 카운트 = 60 + 20 = 80개
```

**장점:** 정확도와 효율성의 균형

**단점:** 완벽히 정확하지는 않음 (근사치)

---

#### Token Bucket (토큰 버킷) ⭐ FluxGate 채택

```
┌─────────────────────────────────────────────────────┐
│                   Token Bucket                       │
│                                                      │
│   용량 (Capacity): 100개                             │
│   리필 속도: 10개/초                                  │
│                                                      │
│   ┌───────────────────────────────────────────┐     │
│   │ ● ● ● ● ● ● ● ○ ○ ○   (현재 70개)        │     │
│   └───────────────────────────────────────────┘     │
│            │                      ▲                  │
│            ▼                      │                  │
│       요청 시 1개 소비        시간 지나면 리필       │
│                                                      │
│   토큰 > 0  → 허용 ✓                                │
│   토큰 = 0  → 거부 ✗                                │
└─────────────────────────────────────────────────────┘
```

**특징:**
- **버스트 허용**: 순간적으로 용량만큼 요청 가능
- **평균 속도 제한**: 장기적으로 리필 속도로 수렴
- **메모리 효율적**: 토큰 수 + 마지막 리필 시간만 저장

---

#### Leaky Bucket (누수 버킷)

```
┌─────────────────────────────────────────────────────┐
│                   Leaky Bucket                       │
│                                                      │
│   요청 들어옴                                        │
│       ↓                                              │
│   ┌───────────────────────────────────────────┐     │
│   │ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣   (큐에 대기 중)          │     │
│   └───────────────────────────────────────────┘     │
│                       │                              │
│                       ↓ 일정한 속도로 처리 (leak)    │
│                   ─────────                          │
│                    처리됨                            │
│                                                      │
│   큐 가득 참 → 새 요청 거부                          │
└─────────────────────────────────────────────────────┘
```

**특징:**
- **출력 속도 일정**: 트래픽 성형 (Traffic Shaping)
- **지연 발생**: 큐에서 대기해야 함

---

### 1.3 알고리즘 선택 가이드

| 상황 | 추천 알고리즘 | 이유 |
|------|-------------|------|
| API 과금 (정확한 카운트) | Sliding Window Log | 정확도 최우선 |
| 일반 API Rate Limit | **Token Bucket** | 버스트 허용 + 간단 |
| DB 보호, 트래픽 평탄화 | Leaky Bucket | 일정한 처리량 |
| 단순한 구현 필요 | Fixed Window | 구현 쉬움 |

---

## 2. Redis Lua 스크립트

### 2.1 왜 Lua 스크립트인가?

**문제: Race Condition**

```
서버 A                          서버 B
   │                               │
   ├─ GET tokens → 5               │
   │                               ├─ GET tokens → 5
   ├─ tokens - 1 = 4               │
   │                               ├─ tokens - 1 = 4
   ├─ SET tokens 4                 │
   │                               ├─ SET tokens 4
   ▼                               ▼

결과: 2번 소비했는데 1개만 감소! (버그)
```

**해결: Lua 스크립트 = 원자적 실행**

```
서버 A                          Redis (싱글 스레드)
   │                               │
   ├─ EVALSHA script ─────────────▶│ 스크립트 전체 실행
   │                               │ (중간에 끊기지 않음)
   │◀─────────── 결과 ─────────────┤
   │                               │
서버 B                              │
   ├─ EVALSHA script ─────────────▶│ 다음 스크립트 실행
   │                               │
```

Redis는 **싱글 스레드**이므로 Lua 스크립트는 **원자적(Atomic)**으로 실행됩니다.

---

### 2.2 Lua 기본 문법

```lua
-- 변수 선언
local count = 10
local name = "fluxgate"

-- 조건문
if count > 5 then
    return "high"
elseif count > 0 then
    return "low"
else
    return "zero"
end

-- 반복문
for i = 1, 10 do
    print(i)
end

-- 함수
local function add(a, b)
    return a + b
end

-- 테이블 (배열/맵)
local arr = {1, 2, 3}           -- 배열 (1부터 시작!)
local map = {name = "flux"}     -- 맵

print(arr[1])      -- 1 (Lua는 1부터!)
print(map.name)    -- "flux"
```

---

### 2.3 Redis에서 Lua 사용

```lua
-- KEYS: 스크립트에서 사용하는 Redis 키
-- ARGV: 스크립트에 전달하는 인자

-- 예시: EVALSHA sha1 1 mykey 10
-- KEYS[1] = "mykey"
-- ARGV[1] = "10"

local key = KEYS[1]
local increment = tonumber(ARGV[1])

-- Redis 명령어 호출
local current = redis.call('GET', key)
current = tonumber(current) or 0

local new_value = current + increment
redis.call('SET', key, new_value)

return new_value
```

---

### 2.4 EVAL vs EVALSHA

```
EVAL (느림)
─────────────
매번 스크립트 전체 전송

Client: EVAL "local x = redis.call('GET', KEYS[1]) ..." 1 mykey
                    └─────── 긴 스크립트 ───────┘


EVALSHA (빠름) ← FluxGate 사용
──────────────
1. 최초 1회: SCRIPT LOAD "스크립트..." → SHA: "a1b2c3..."
2. 이후: EVALSHA "a1b2c3..." 1 mykey
              └─ 40바이트 해시만 전송
```

---

### 2.5 FluxGate의 Token Bucket Lua 스크립트

```lua
-- token_bucket_consume.lua (간략화)

local bucket_key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_micros = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])

-- Redis 서버 시간 사용 (클럭 드리프트 방지)
local time_info = redis.call('TIME')
local now_micros = time_info[1] * 1000000 + time_info[2]

-- 현재 상태 읽기
local data = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_micros')
local tokens = tonumber(data[1]) or capacity
local last_refill = tonumber(data[2]) or now_micros

-- 토큰 리필 계산 (정수 연산만 사용)
local elapsed = now_micros - last_refill
local refill = math.floor((elapsed * capacity) / window_micros)
tokens = math.min(capacity, tokens + refill)

-- 소비 시도
if tokens >= permits then
    tokens = tokens - permits
    redis.call('HMSET', bucket_key, 'tokens', tokens, 'last_refill_micros', now_micros)
    redis.call('EXPIRE', bucket_key, 86400)  -- TTL 설정
    return {1, tokens, 0}  -- 허용
else
    local wait = math.ceil((permits - tokens) * window_micros / capacity)
    return {0, tokens, wait}  -- 거부
end
```

---

### 2.6 NOSCRIPT 에러 처리

```
문제: Redis 재시작 시 스크립트 캐시 사라짐

EVALSHA "a1b2c3..." → NOSCRIPT 에러!

해결 (FluxGate 구현):
1. EVALSHA 시도
2. NOSCRIPT 에러 발생
3. EVAL로 폴백 (동작은 함)
4. 스크립트 다시 로드 (다음 요청부터 EVALSHA 사용)
```

---

## 3. Redis Pub/Sub

### 3.1 개념

```
┌─────────────────────────────────────────────────────────────┐
│                        Redis Server                          │
│                                                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │              Channel: "fluxgate:rule-reload"         │   │
│   └─────────────────────────────────────────────────────┘   │
│         ▲                    │                              │
│         │ PUBLISH            │ 메시지 전달                   │
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

### 3.2 사용 예시

**Publisher (메시지 발행)**
```bash
PUBLISH fluxgate:rule-reload '{"ruleSetId": "api-limits", "action": "RELOAD"}'
```

**Subscriber (메시지 구독)**
```bash
SUBSCRIBE fluxgate:rule-reload
# 메시지가 오면 자동으로 수신
```

### 3.3 FluxGate에서의 활용

```
Admin이 규칙 변경
       │
       ▼
┌─────────────────────┐
│ PUBLISH 메시지 발행  │
│ Channel: rule-reload│
└─────────────────────┘
       │
       ▼ Redis가 모든 구독자에게 전달
       │
┌──────┴──────┬──────────────┐
▼             ▼              ▼
Instance 1  Instance 2  Instance 3
    │             │            │
    ▼             ▼            ▼
캐시 갱신     캐시 갱신    캐시 갱신
버킷 리셋     버킷 리셋    버킷 리셋
```

### 3.4 Pub/Sub vs Polling

| 방식 | 장점 | 단점 |
|------|------|------|
| **Polling** | 구현 단순 | 지연 발생 (폴링 주기) |
| **Pub/Sub** | 실시간 동기화 | 연결 관리 필요 |

```yaml
# FluxGate 설정
fluxgate:
  reload:
    strategy: PUBSUB         # 또는 POLLING
    polling:
      interval: 30s          # POLLING일 때 사용
```

---

## 4. Caffeine 캐시

### 4.1 왜 캐시가 필요한가?

```
캐시 없을 때:
─────────────────────────────────────────
요청 1 → MongoDB 조회 (10ms)
요청 2 → MongoDB 조회 (10ms)
요청 3 → MongoDB 조회 (10ms)
...
요청 1000 → MongoDB 조회 (10ms)
총: 10,000ms

캐시 있을 때:
─────────────────────────────────────────
요청 1 → MongoDB 조회 (10ms) → 캐시 저장
요청 2 → 캐시 히트 (0.1ms)
요청 3 → 캐시 히트 (0.1ms)
...
요청 1000 → 캐시 히트 (0.1ms)
총: ~110ms (90배 빠름)
```

### 4.2 Caffeine이란?

Java의 고성능 로컬 캐시 라이브러리입니다.

```java
Cache<String, RateLimitRuleSet> cache = Caffeine.newBuilder()
    .maximumSize(1000)                    // 최대 1000개 항목
    .expireAfterWrite(Duration.ofMinutes(5))  // 5분 후 만료
    .recordStats()                        // 통계 수집
    .build();

// 저장
cache.put("api-limits", ruleSet);

// 조회
RateLimitRuleSet cached = cache.getIfPresent("api-limits");

// 없으면 로드
RateLimitRuleSet loaded = cache.get("api-limits", key -> loadFromDB(key));
```

### 4.3 Eviction 정책

```
┌───────────────────────────────────────────────────────────┐
│                    Caffeine Cache                          │
│                                                            │
│   Eviction (제거) 정책:                                    │
│                                                            │
│   1. Size-based (크기 기반)                                │
│      └─ maximumSize(1000) → 1000개 초과 시 제거            │
│                                                            │
│   2. Time-based (시간 기반)                                │
│      ├─ expireAfterWrite(5min) → 쓴 후 5분 지나면 만료     │
│      └─ expireAfterAccess(5min) → 접근 후 5분 지나면 만료  │
│                                                            │
│   3. Reference-based (참조 기반)                           │
│      └─ weakKeys(), weakValues() → GC가 수거 가능         │
│                                                            │
│   제거 알고리즘: Window TinyLFU (높은 히트율)              │
└───────────────────────────────────────────────────────────┘
```

### 4.4 Window TinyLFU

Caffeine의 핵심 알고리즘입니다.

```
전통적인 LRU (Least Recently Used):
─────────────────────────────────────
가장 오래 사용 안 된 항목 제거
문제: 한 번 많이 사용된 항목도 잠시 안 쓰면 제거됨

Window TinyLFU:
─────────────────────────────────────
빈도(Frequency) + 최근성(Recency) 조합
더 스마트한 제거 결정

┌─────────────────────────────────────────────────┐
│                Window TinyLFU                    │
│                                                  │
│  ┌─────────┐     ┌─────────────────────────┐   │
│  │ Window  │────▶│      Main Cache          │   │
│  │ (1%)    │     │       (99%)              │   │
│  └─────────┘     └─────────────────────────┘   │
│       │                    │                    │
│       ▼                    ▼                    │
│  새 항목 진입         빈도 기반 제거             │
│                     (TinyLFU 스케치)            │
└─────────────────────────────────────────────────┘
```

### 4.5 FluxGate에서의 사용

```java
// FluxGate의 RuleCache 구현
@Component
public class CaffeineRuleCache implements RuleCache {

    private final Cache<String, RateLimitRuleSet> cache;

    public CaffeineRuleCache(FluxgateProperties props) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(props.getCache().getMaxSize())      // 기본 1000
            .expireAfterWrite(props.getCache().getTtl())     // 기본 5분
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
        cache.invalidate(ruleSetId);  // Hot Reload 시 호출
    }
}
```

---

## 5. Spring Filter 아키텍처

### 5.1 Filter Chain 개념

```
HTTP 요청
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│                      Filter Chain                            │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ Filter 1 │─▶│ Filter 2 │─▶│ Filter 3 │─▶│ Servlet  │    │
│  │ (인증)   │  │ (Rate   │  │ (로깅)   │  │(Controller)│   │
│  │          │  │  Limit) │  │          │  │          │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
│       │              │             │             │          │
│       ▼              ▼             ▼             ▼          │
│   doFilter()    doFilter()    doFilter()    service()       │
│       │              │             │             │          │
│       ◀──────────────◀─────────────◀─────────────┘          │
│                   응답 반환                                  │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
HTTP 응답
```

### 5.2 Filter 인터페이스

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

### 5.3 Filter 동작 흐름

```java
public class MyFilter implements Filter {

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {

        // ===== 전처리 (요청 들어올 때) =====
        System.out.println("Before request processing");

        // 다음 필터로 전달 (또는 서블릿으로)
        chain.doFilter(request, response);

        // ===== 후처리 (응답 나갈 때) =====
        System.out.println("After request processing");
    }
}
```

```
실행 순서:
─────────────────────────────────────────
요청 → Filter1 전처리
           → Filter2 전처리
                  → Filter3 전처리
                         → Servlet 처리
                  ← Filter3 후처리
           ← Filter2 후처리
     ← Filter1 후처리
← 응답
```

### 5.4 Filter 등록 방법

**방법 1: @Component (Spring Boot)**
```java
@Component
@Order(1)  // 순서 지정
public class RateLimitFilter implements Filter {
    // ...
}
```

**방법 2: FilterRegistrationBean**
```java
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter() {
        FilterRegistrationBean<RateLimitFilter> registration =
            new FilterRegistrationBean<>();

        registration.setFilter(new RateLimitFilter());
        registration.addUrlPatterns("/api/*");  // 특정 경로만
        registration.setOrder(1);

        return registration;
    }
}
```

### 5.5 FluxGate Rate Limit Filter

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

        // 1. 제외 패턴 체크
        if (shouldExclude(path)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. RequestContext 생성
        RequestContext context = RequestContext.builder()
            .clientIp(getClientIp(httpRequest))
            .method(httpRequest.getMethod())
            .endpoint(path)
            .build();

        // 3. Rate Limit 체크
        RateLimitResponse result = handler.tryConsume(context, ruleSetId);

        // 4. 응답 헤더 추가
        httpResponse.setHeader("X-RateLimit-Remaining",
            String.valueOf(result.getRemainingTokens()));

        // 5. 허용/거부 결정
        if (result.isAllowed()) {
            chain.doFilter(request, response);  // 다음으로 전달
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
│                        요청 처리 순서                           │
│                                                                │
│  HTTP 요청                                                     │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                    Filter (Servlet)                       │ │
│  │  - Servlet 스펙                                           │ │
│  │  - Spring 외부에서도 동작                                  │ │
│  │  - Request/Response 직접 조작 가능                        │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                 Interceptor (Spring MVC)                  │ │
│  │  - Spring MVC 스펙                                        │ │
│  │  - Handler 정보 접근 가능                                  │ │
│  │  - preHandle, postHandle, afterCompletion                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                      AOP (Spring)                         │ │
│  │  - 메서드 레벨                                             │ │
│  │  - 비즈니스 로직에 가까움                                   │ │
│  │  - @Before, @After, @Around                               │ │
│  └──────────────────────────────────────────────────────────┘ │
│      │                                                         │
│      ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                   Controller                              │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

| 구분 | Filter | Interceptor | AOP |
|------|--------|-------------|-----|
| **스펙** | Servlet | Spring MVC | Spring |
| **레벨** | HTTP 요청/응답 | Handler | 메서드 |
| **접근 가능** | Request, Response | Handler, ModelAndView | JoinPoint |
| **사용 사례** | 인증, 로깅, **Rate Limit** | 권한, 로깅 | 트랜잭션, 로깅 |

**FluxGate가 Filter를 선택한 이유:**
- HTTP 레벨에서 조기 차단 (불필요한 처리 방지)
- Request/Response 직접 제어 가능
- Spring 외에서도 사용 가능

---

## 6. Token Bucket vs Leaky Bucket

### 6.1 동작 비교

```
시나리오: 결제 API (10 req/sec 제한)
사용자가 0초에 10개 요청을 동시에 보냄

┌─────────────────────────────────────────────────────────────┐
│ Token Bucket                                                │
├─────────────────────────────────────────────────────────────┤
│ 0.0초: 10개 요청 → 10개 모두 즉시 통과 ✅                     │
│ 0.1초: 1개 요청 → 토큰 없음, 거부 ❌                         │
│ 1.0초: 토큰 리필 → 다시 10개 가능                            │
│                                                             │
│ 결과: 서버가 순간적으로 10개 동시 처리해야 함 💥              │
│ 장점: 사용자 응답 빠름                                       │
│ 단점: 서버 부하 스파이크 발생 가능                            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Leaky Bucket                                                │
├─────────────────────────────────────────────────────────────┤
│ 0.0초: 10개 요청 → 1개 통과, 9개 큐 대기                     │
│ 0.1초: 큐에서 1개 처리 → 8개 대기                            │
│ 0.2초: 큐에서 1개 처리 → 7개 대기                            │
│ ...                                                         │
│ 0.9초: 큐에서 1개 처리 → 0개 대기                            │
│                                                             │
│ 결과: 서버는 항상 1개씩만 처리 ✅                             │
│ 장점: 서버 부하 일정                                         │
│ 단점: 사용자 응답 지연 (최대 0.9초 대기)                      │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 언제 어떤 것을 사용?

| 상황 | 추천 | 이유 |
|------|------|------|
| API Gateway | Token Bucket | 빠른 응답이 사용자 경험에 중요 |
| DB 보호 | Leaky Bucket | 일정한 처리량으로 DB 보호 |
| 결제 API | Token Bucket | 즉각적인 피드백 필요 |
| 배치 작업 큐 | Leaky Bucket | 처리량 평탄화 |

### 6.3 FluxGate의 선택

FluxGate는 **Token Bucket**을 선택했습니다:

1. **API Gateway 용도** → 빠른 응답 중요
2. **O(1) 시간복잡도** → 고성능
3. **Redis Lua 스크립트로 원자적 처리 가능**
4. **Multi-Band 지원** (10/초 + 100/분 + 1000/시간)

```java
RateLimitRule rule = RateLimitRule.builder("multi-band")
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())    // 초당 10개
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100).build())   // 분당 100개
    .addBand(RateLimitBand.builder(Duration.ofHours(1), 1000).build())    // 시간당 1000개
    .build();
```

---

## 관련 문서

- [아키텍처 개요](README.ko.md)
- [RateLimiter Layer Deep Dive](deep-dive/ratelimiter-layer.ko.md)
- [Storage Layer Deep Dive](deep-dive/storage-layer.ko.md)
- [Hot Reload Deep Dive](deep-dive/hot-reload.ko.md)
