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
| **공간** | 대역당 O(1) | 버킷당 2개 필드 (`tokens`, `last_refill_micros`) |
| **네트워크** | 규칙당 1 RTT | 한 규칙의 모든 대역을 한 번의 왕복으로 처리 |

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

#### Fix #4: TTL 안전 마진과 설정 가능한 상한

**문제:** Clock Skew로 인해 키가 조기 만료될 수 있음. 반대로 0.3.x의 고정 24시간 상한은 하루보다 긴
윈도를 조용히 초기화했음

```lua
-- 윈도에 Clock Skew 대비 10% 여유를 더하고, 최소 1초, 상한은 max_bucket_ttl(ARGV[2],
-- fluxgate.redis.max-bucket-ttl, 기본 7일). TOKEN_BUCKET과 SLIDING_WINDOW에 쓴다.
local function ttl_for_window(win_micros)
    return math.min(max_ttl_seconds, math.max(1, math.ceil(win_micros / 1000000 * 1.1)))
end
```

**효과:** 버킷이 너무 일찍 만료되지 않음. 이전의 `math.min(desired_ttl, 86400)` 상한은 7일 쿼터를
24시간마다 초기화해 실질적으로 용량의 7배를 허용했음. FIXED_WINDOW 카운터는 윈도 끝에 만료되며
(`PEXPIREAT`) 상한에서 제외됨. `max-bucket-ttl`보다 오래 유휴 상태인 TOKEN_BUCKET·SLIDING_WINDOW 버킷은
만료되어 가득 찬 상태로 다시 시작하므로, 상한보다 긴 윈도는 유휴 호출자에게 실질적으로 짧아지며
리미터는 그런 규칙마다 WARN을 한 번 남김

거절할 때 스크립트는 모든 키에 `EXPIRE`를 걸지만 상태는 쓰지 않음. 존재하지 않는 키에 대한 `EXPIRE`는
아무 일도 하지 않으므로 한 번도 차감되지 않은 대역은 생성되지 않으며, 거절만 받는 버킷도 마지막으로
허용된 요청의 줄어드는 TTL을 물려받지 않고 예정대로 만료됨

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
| 버킷 정리 (SCAN + UNLINK) | 전체 O(N), 논블로킹 | N = 전체 Redis 키, SCAN 페이지 단위 (5.1 참조) |

---

## 5. 최적화 가능 영역

### 5.1 버킷 삭제: KEYS → SCAN (0.4에서 적용)

**0.3.x (O(N), 블로킹):** `connectionProvider.keys("fluxgate:*")` - `KEYS`는 전체 키스페이스를 훑는 동안
Redis를 막고, `fluxgate:*` 패턴은 룰셋 정의까지 지웠습니다.

**0.4 (적용됨):** 리셋 경로(`deleteBucketsByRuleSetId`, `deleteAllBuckets`)는 `fluxgate:bucket:...` 패턴을
`SCAN`으로 훑고, 페이지가 도착하는 대로 `UNLINK`로 지웁니다. 키 전체를 한꺼번에 메모리에 들지 않습니다.

```java
// RedisTokenBucketStore.java - 실제 코드
private long scanAndUnlink(String pattern) {
  long[] deleted = {0L};
  try {
    connectionProvider.scanKeys(
        pattern, BUCKET_SCAN_COUNT, page -> deleted[0] += deleteInBatches(page));
  } catch (RedisException e) {
    throw driverFailed("SCAN/UNLINK " + pattern, e);
  }
  return deleted[0];
}

private long deleteInBatches(List<String> keys) {
  long deleted = 0;
  for (int start = 0; start < keys.size(); start += DELETE_BATCH_SIZE) {
    int end = Math.min(start + DELETE_BATCH_SIZE, keys.size());
    deleted += connectionProvider.unlink(keys.subList(start, end).toArray(new String[0]));
  }
  return deleted;
}
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
