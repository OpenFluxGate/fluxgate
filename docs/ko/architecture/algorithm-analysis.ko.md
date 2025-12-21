# 알고리즘 분석

이 문서는 FluxGate 핵심 컴포넌트의 알고리즘 설계와 복잡도를 분석합니다.

[< 아키텍처 개요로 돌아가기](README.ko.md)

---

## 목차

1. [개요](#1-개요)
2. [토큰 버킷 알고리즘 (Lua 스크립트)](#2-토큰-버킷-알고리즘-lua-스크립트)
3. [키 해석](#3-키-해석)
4. [복잡도 요약](#4-복잡도-요약)
5. [최적화 가능 영역](#5-최적화-가능-영역)

---

## 1. 개요

FluxGate는 분산 환경에서 **고처리량, 저지연** Rate Limiting을 위해 설계되었습니다. 핵심 알고리즘의 목표:

- **O(1) 시간 복잡도** - Rate Limit 검사
- **원자적 연산** - Race Condition 방지
- **분산 일관성** - Clock Drift 문제 해결

---

## 2. 토큰 버킷 알고리즘 (Lua 스크립트)

FluxGate의 핵심은 Redis Lua 스크립트로 실행되는 최적화된 토큰 버킷 구현입니다.

### 2.1 복잡도

| 지표 | 복잡도 | 설명 |
|------|--------|------|
| **시간** | O(1) | Rate Limit 검사당 상수 시간 |
| **공간** | O(1) per key | 버킷당 2개 필드 (tokens, last_refill_nanos) |
| **네트워크** | 1 RTT | 원자적 실행을 위한 단일 왕복 |

### 2.2 핵심 최적화

#### Fix #1: Redis 서버 시간 (Clock Drift 방지)

**문제:** 분산 노드들의 시스템 시계가 달라 일관성 없는 Rate Limiting 발생

**해결:** 클라이언트 타임스탬프 대신 Redis `TIME` 명령어 사용

```lua
-- Redis 서버 시간 사용 (모든 클라이언트에서 일관됨)
local time_info = redis.call('TIME')
local current_time_nanos = tonumber(time_info[1]) * 1000000000
                         + tonumber(time_info[2]) * 1000
```

**효과:** 멀티 노드 배포에서 Clock Drift 완전 제거

---

#### Fix #2: 정수 연산만 사용 (정밀도 보장)

**문제:** 부동소수점 연산은 시간이 지남에 따라 정밀도 손실 발생

```lua
-- 나쁜 예: 부동소수점 (정밀도 손실)
local refill_rate = capacity / window_nanos  -- 0.0000000016667...
local tokens_to_add = elapsed_nanos * refill_rate

-- 좋은 예: 정수만 사용 (정밀도 손실 없음)
local tokens_to_add = math.floor((elapsed_nanos * capacity) / window_nanos)
```

**효과:** 수백만 요청 후에도 정확한 토큰 카운팅

---

#### Fix #3: 거절 시 읽기 전용 (공정한 Rate Limiting)

**문제:** 거절 시 타임스탬프를 업데이트하면 불공정한 Rate Limiting 발생

```
이 수정 없이 발생하는 시나리오:
1. 요청 A: tokens=0, 거절됨, 하지만 타임스탬프가 T1으로 업데이트
2. 요청 B (1ms 후): 1ms만 경과된 것으로 보여 더 적은 리필 토큰 받음
   결과: 요청 B가 불공정하게 불이익
```

**해결:** 성공적인 소비 시에만 상태 업데이트

```lua
if new_tokens >= permits then
    -- 성공: 상태 업데이트
    redis.call('HMSET', bucket_key, 'tokens', new_tokens, ...)
else
    -- 거절: 읽기 전용, 상태 업데이트 안 함
    return {0, new_tokens, nanos_to_wait, reset_time_millis}
end
```

**효과:** 높은 경합 상황에서도 공정한 Rate Limiting

---

#### Fix #4: TTL 안전 마진

**문제:** Clock Skew로 인해 키가 조기 만료될 수 있음

```lua
-- 10% 안전 마진 추가
local desired_ttl = math.ceil(window_nanos / 1000000000 * 1.1)

-- 24시간으로 상한 설정 (무한 증가 방지)
local actual_ttl = math.min(desired_ttl, 86400)
```

**효과:** 버킷이 너무 일찍 만료되는 엣지 케이스 방지

---

### 2.3 원자성 보장

모든 연산이 단일 Lua 스크립트 내에서 실행되어 다음을 제공:

```
┌─────────────────────────────────────────────────────────────┐
│                    Redis Lua 스크립트                        │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  1. 현재 시간 조회 (Redis TIME)                      │   │
│  │  2. 버킷 상태 읽기 (HMGET)                           │   │
│  │  3. 토큰 리필 계산                                   │   │
│  │  4. 소비 가능 여부 확인                              │   │
│  │  5. 허용 시 상태 업데이트 (HMSET)                    │   │
│  │  6. TTL 설정 (EXPIRE)                               │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  모든 단계가 원자적으로 실행 - Race Condition 없음            │
└─────────────────────────────────────────────────────────────┘

동시 요청 처리:
Client A ──┐
Client B ──┼──→ [Lua 스크립트] ──→ 직렬화된 실행
Client C ──┘
```

---

## 3. 키 해석

### 3.1 LimitScopeKeyResolver

키 해석은 O(1) switch-case 매핑 사용:

```java
switch (scope) {
    case GLOBAL:      return "global";                  // O(1)
    case PER_IP:      return context.getClientIp();    // O(1)
    case PER_USER:    return context.getUserId();      // O(1)
    case PER_API_KEY: return context.getApiKey();      // O(1)
    case CUSTOM:      return context.getAttribute(key); // O(1)
}
```

| 스코프 | 시간 복잡도 | 공간 복잡도 |
|--------|-------------|-------------|
| 모든 스코프 | O(1) | O(1) |

---

## 4. 복잡도 요약

### 요청당 경로

| 컴포넌트 | 시간 | 공간 | 비고 |
|----------|------|------|------|
| Filter (컨텍스트 빌드) | O(1) | O(H) | H = 헤더 수 |
| 키 해석 | O(1) | O(1) | Switch-case 조회 |
| 규칙 캐시 (Caffeine) | O(1) | O(R) | R = 캐시된 규칙 수 |
| 토큰 버킷 (Lua) | O(1) | O(1) | 단일 Redis 호출 |
| **전체** | **O(1)** | **O(H)** | 상수 시간 |

### 백그라운드 작업

| 작업 | 시간 | 비고 |
|------|------|------|
| 캐시 갱신 | O(R) | R = MongoDB 규칙 수 |
| 버킷 정리 (KEYS) | O(N) | N = 전체 Redis 키 (아래 최적화 참조) |

---

## 5. 최적화 가능 영역

### 5.1 버킷 삭제: KEYS → SCAN

**현재 구현 (O(N)):**
```java
// 경고: KEYS는 전체 키스페이스 스캔 동안 Redis 블로킹
List<String> keys = connectionProvider.keys("fluxgate:*");
```

**권장 개선안 (반복당 O(1)):**
```java
// 논블로킹 점진적 스캔
String cursor = "0";
do {
    ScanResult<String> result = redis.scan(cursor, "fluxgate:*", 100);
    cursor = result.getCursor();
    redis.del(result.getResult());
} while (!cursor.equals("0"));
```

| 방식 | 시간 | 블로킹 여부 |
|------|------|-------------|
| KEYS | O(N) | 예 (Redis 블로킹) |
| SCAN | 전체 O(N), 호출당 O(1) | 아니오 |

---

### 5.2 규칙 매칭 (향후 개선)

규칙이 많은 애플리케이션(100개 이상)의 경우:

| 방식 | 현재 | 개선안 |
|------|------|--------|
| 경로 매칭 | O(R) 선형 스캔 | O(K) Trie 조회 |
| Bloom Filter | 해당 없음 | O(1) 미스 사전 체크 |

R = 규칙 수, K = 경로 길이

---

## 요약

FluxGate는 핵심 경로(Rate Limit 검사)에서 **O(1) 시간 복잡도**를 달성합니다:

1. **원자적 Lua 스크립트** - 단일 Redis 왕복
2. **서버 측 타임스탬프** - Clock Drift 없음
3. **정수 연산** - 정밀도 손실 없음
4. **Caffeine 캐싱** - O(1) 규칙 조회
5. **공정한 거절 처리** - 거절 시 읽기 전용

이러한 최적화로 FluxGate는 **초당 수백만 요청을 처리하는 고처리량 프로덕션 환경**에 적합합니다.

---

## 관련 문서

- [Storage Layer](deep-dive/storage-layer.ko.md) - Redis 구현 상세
- [아키텍처 개요](README.ko.md)
