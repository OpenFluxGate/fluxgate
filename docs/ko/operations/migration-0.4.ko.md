# 0.4 마이그레이션

FluxGate 0.3.x에서 올라올 때 무엇이 바뀌는지를, 실제로 부딪히는 순서대로 정리했습니다.

[< 문서 색인으로](../../README.ko.md) | [English](../../en/operations/migration-0.4.md)

전체 변경 목록은 [CHANGELOG.md](../../../CHANGELOG.md)에 있습니다.

---

## 한 문단 요약

거의 모든 사용자에게 소스 호환이며, **운영상** 눈에 보이는 영향은 하나입니다. 버킷 키 형태가 바뀌므로
모든 Rate Limit 쿼터가 **한 번** 초기화됩니다. 나머지는 "예전에는 동작하지 않던 것이 이제 동작한다"는
목록입니다 — 읽히지 않던 필터 프로퍼티, 배선되지 않던 서킷 브레이커, 문서에만 있던 표준 Rate Limit
헤더 — 여기에 429 본문이 RFC 9457 problem JSON으로 바뀌었고 Micrometer 미터 하나가 제거되었습니다.

몇몇 **보안 기본값**도 엄격해졌고, 이는 누가 어떻게 제한되는지를 바꿀 수 있습니다. 명시적으로 켜지
않으면 신원 헤더를 더 이상 신뢰하지 않고, Redis Pub/Sub 룰 리로드는 서명 시크릿이 있을 때만
동작합니다. 15절에 항목별로, 0.3 동작을 되돌리는 프로퍼티와 함께
정리했습니다.

## 1. 모든 쿼터가 한 번 초기화됩니다

버킷 키와 버킷 해시 레이아웃이 모두 바뀝니다:

| | 0.3.x | 0.4 |
|---|---|---|
| 키 | `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel\|default}` | `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>` |
| 키 값 | `192.168.1.100` | `ip:192.168.1.100` (스코프 접두사, 새니타이즈) |
| 해시 필드 | `tokens`, `last_refill_nanos` | `tokens`, `last_refill_micros` |

세 가지 변경이지만 초기화는 **한 번**입니다. 첫 기동 시 모든 호출자가 가득 찬 버킷을 받습니다.
`{...}` 해시 태그는 한 규칙+키의 모든 대역을 하나의 Redis Cluster 슬롯에 고정하며, 다중 대역
스크립트가 클러스터에서 원자적으로 동작하기 위한 전제 조건입니다.

**할 일:**

- 일시적 버스트가 문제라면 트래픽이 적은 시간대에 배포하세요.
- 옛 키는 읽히지 않고 자신의 TTL로 소멸합니다. 메모리를 더 빨리 회수하려면:
  ```bash
  redis-cli --scan --pattern 'fluxgate:*' | grep -v '^fluxgate:bucket:' | xargs -r redis-cli unlink
  ```
  실행 전에 출력을 확인하세요 — deprecated된 Redis 규칙 저장소를 아직 쓴다면
  `fluxgate:ruleset:*`를 지우면 안 됩니다.
- **FIXED_WINDOW 카운터**(0.4 신규)는 `<버킷 키>:fw` 아래의 해시 `{count, window_end_micros}`
  입니다. FIXED_WINDOW 대역으로 0.4 사전 릴리스 빌드를 운영했다면, 접미사 없는 키의 옛 문자열
  카운터는 더 이상 읽히지 않고 각자 자기 윈도 끝에 만료되므로 그 윈도의 카운트가 한 번 다시
  시작됩니다. 지울 것은 없습니다.
- **FluxGate 키나 버킷 해시를 읽거나 만드는 외부 도구를 반드시 수정하세요.** 해시 값이
  `1.76e+18`이 아니라 평범한 숫자로 저장됩니다. 키는 문자열 연결이 아니라
  `RedisRateLimiter.BUCKET_KEY_PREFIX`와 `bucketKeyPattern(ruleSetId)`로 만드세요.

## 2. `include-patterns` 기본값이 전체 경로로 바뀝니다

옛 기본값 `{"/*"}`는 경로 세그먼트 **하나**만 매칭합니다. 즉 이 프로퍼티를 설정하지 않은
애플리케이션은 첫 세그먼트 아래를 사실상 Rate Limiting하지 않고 있었습니다. 이제 기본값은 전체
경로입니다.

```yaml
fluxgate:
  ratelimit:
    include-patterns:
      - /api/**        # 좁은 범위를 유지하려면 명시적으로 설정하세요
```

관련: 매칭이 **정규화된** 경로(URL 디코드, `;` 파라미터 제거, `//` 축약, 끝 슬래시 제거, 컨텍스트 경로
독립)를 대상으로 수행되므로, 예전에 exclude 패턴을 빠져나갔던 요청이 더는 빠져나가지 않습니다.

## 3. 조용히 무시되던 프로퍼티가 이제 적용됩니다

다음은 문서에는 있었지만 라이브러리 코드가 전혀 읽지 않던 키들입니다:

`include-patterns`, `exclude-patterns`, `filter-order`, `include-headers`, `client-ip-header`,
`trust-client-ip-header`, `missing-rule-behavior`, `metrics.include-endpoint`

**설정 파일을 처음 보는 것처럼 다시 읽으세요.** 오래전에 `trust-client-ip-header: true`를 넣었는데
아무 일도 없었다면, 이제는 일이 일어납니다. `missing-rule-behavior: ALLOW`를 넣고도 코드의
fail-closed 동작에 의존하고 있었다면, 이제는 프로퍼티가 이깁니다.

프로퍼티는 대응하는 `@EnableFluxgateFilter` 애트리뷰트보다 **우선**합니다 — 애너테이션 Javadoc이
원래 약속하던 동작입니다. 둘 다 설정했다면 프로퍼티가 이깁니다.

## 4. 전달 헤더를 신뢰한다면 `trusted-proxies`를 설정하세요

```yaml
fluxgate:
  ratelimit:
    trust-client-ip-header: true
    trusted-proxies:
      - 10.0.0.0/8
      - 2001:db8::/32
```

목록이 설정되면 FluxGate는 `X-Forwarded-For`를 오른쪽에서 왼쪽으로 순회하며 신뢰 프록시를
건너뜁니다. 목록이 **비어 있고** 신뢰가 켜져 있으면 **최우측** 유효 홉을 사용하고 기동 시 WARN 1회를
남깁니다 — 즉각 프록시가 추가한 값으로 최좌측 클라이언트 제공 값보다 위조가 어렵지만,
멀티홉 배포에서는 반드시 `trusted-proxies`를 설정하세요.

## 5. 429 본문이 바뀌었습니다

```
0.3.x:  {"error":"Too Many Requests","retryAfter":30}
        Content-Type: application/json

0.4:    {"type":"about:blank","title":"Too Many Requests","status":429,
         "detail":"Rate limit exceeded, retry after 30 seconds","retryAfterMillis":29340}
        Content-Type: application/problem+json;charset=UTF-8
```

옛 키를 파싱하던 클라이언트는 깨집니다. 되돌리는 방법 두 가지:

```yaml
fluxgate:
  ratelimit:
    response:
      content-type: application/json
      body-template: '{"error":"Too Many Requests","retryAfter":{retryAfterSeconds}}'
```

또는 본문을 완전히 가져가기:

```java
@Bean
public RateLimitResponseWriter rateLimitResponseWriter() {
    return (request, response, result) -> {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"rate limited\"}");
    };
}
```

## 6. 없던 응답 헤더가 생깁니다

`X-RateLimit-Limit`과 `X-RateLimit-Reset`은 0.3.x 문서에 있었지만 실제로는 기록되지 않았습니다 —
`X-RateLimit-Remaining`과 `Retry-After`만 있었습니다. 이제 둘 다 기록되며 IETF 계열도 함께 나갑니다:

```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1701388800     # epoch 초
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 27               # 잔여 초
RateLimit-Policy: 100;w=60
Retry-After: 27
```

`fluxgate.ratelimit.response.include-legacy-headers` / `include-standard-headers`로 계열별로 끄고,
`fluxgate.ratelimit.include-headers=false`로 둘 다 끕니다. Limiter가 "알 수 없음"(`-1`)으로 보고한
값은 `-1`로 쓰지 않고 헤더를 생략합니다.

`Retry-After`는 이제 올림 + 최소 1초입니다. 1초 미만 값이 `Retry-After: 0`으로 잘려 클라이언트가
busy loop에 빠지던 문제가 사라졌습니다.

## 7. `fluxgate.requests.total`이 제거되었습니다

Micrometer의 Prometheus 네이밍에서 `fluxgate_requests_total`로 노출되어, `result` 태그를 가진
`fluxgate.requests`와 충돌했습니다. 태그로 합산하세요:

```promql
# 이전
rate(fluxgate_requests_total[5m])

# 이후
sum(rate(fluxgate_requests_total{result=~"allowed|rejected"}[5m]))
```

`docker/` 아래 동봉된 대시보드와 알림 규칙은 이미 `result`로 필터링하므로 수정이 필요 없습니다.

추가로 `endpoint` 태그 값이 정규화되고(`/api/users/42` → `/api/users/{id}`),
`fluxgate.metrics.max-endpoint-tags`(기본 1000)로 개수가 제한됩니다. ID가 들어간 리터럴 경로로
대시보드를 만들었다면 이제 `{id}`에 매칭됩니다.

## 8. 핸들러는 대개 삭제할 수 있습니다

`FluxgateRateLimitHandler`가 Redis를 필터에 연결하는 용도만이었다면 삭제하세요. 컨텍스트에
`RateLimiter`(Redis 또는 인메모리)와 `RateLimitRuleSetProvider`가 있으면 스타터가
`EngineBackedRateLimitHandler`를 등록합니다:

```java
// 0.3.x
@EnableFluxgateFilter(handler = MyRedisRateLimitHandler.class)

// 0.4
@EnableFluxgateFilter
```

직접 만든 빈을 남겨두면 그쪽이 이깁니다. 라이브러리가 하지 않는 일 — 중앙 서비스 HTTP 호출, 비즈니스
규칙 적용, 두 번째 저장소 조회 — 을 한다면 남겨두세요.

스타터는 룰셋 Provider를 `delegateRuleSetProvider`라는 이름의 빈에서 먼저 찾고, 그 이름이 없으면
유일한 `RateLimitRuleSetProvider` 빈으로 폴백합니다. 따라서 Provider 빈이 하나라면 이름은
무엇이든 동작합니다.

## 9. Redis 장애 시 동작을 다시 결정하세요

Redis에 연결할 수 없어도 **애플리케이션 기동이 실패하지 않습니다.** Limiter는 지연 연결하고
지수 백오프로 백그라운드에서 재연결하며, 요청 스레드가 재연결을 기다리며 블로킹되지 않습니다.
옛 즉시 연결 동작으로 되돌리려면:

```yaml
fluxgate:
  redis:
    fail-fast: true
```

Redis가 죽어 있는 동안에는 `fluxgate.ratelimit.failure-behavior`가 요청의 운명을 결정합니다 —
`DENY`(기본)는 429, `ALLOW`는 무제한 통과입니다. 이제 보통 더 나은 세 번째 선택지가 있습니다:

```yaml
fluxgate:
  ratelimit:
    fallback:
      mode: IN_MEMORY        # 장애 중에도 인스턴스별 제한 유지
      max-buckets: 100000
      expire-after-access: 1h
```

이 상태를 알려면 `fluxgate.limiter.failures`의 `action=fallback_in_memory`를 보세요.

재시도와 서킷 브레이커도 이제 살아 있습니다(`fluxgate.resilience.*`). 0.3.x에서는 완성된 코드가
아무 곳에도 배선되지 않은 상태였습니다. `circuit-breaker.failure-threshold`는 이제 `Integer`이고
기본 미설정이며, 설정할 때만 적용됩니다. 설정하지 않으면 슬라이딩 윈도 실패율을 씁니다
(`sliding-window-size` 20, `failure-rate-threshold` 50%, `minimum-number-of-calls` 10).

## 10. Redis 없이 개발하기

새 기능이며 로컬 프로파일과 테스트에 유용합니다:

```yaml
fluxgate:
  redis:
    enabled: false
  ratelimit:
    mode: IN_MEMORY          # AUTO로 두어도 Redis가 꺼져 있으면 인메모리로 내려갑니다
```

이때 제한은 **인스턴스별**로 적용되므로 단일 프로세스에서는 맞고 클러스터에서는 틀립니다. 선택 결과는
기동 시 INFO로 남습니다. 테스트에서는 `fluxgate-testkit`이 같은 배선을
`FluxgateInMemoryExtension`으로 포장해 제공합니다.

## 11. 재컴파일이 필요한 소스 변경

| 변경 | 할 일 |
|------|-------|
| `RateLimitRuleSet.build()` / `RateLimitRule.build()`가 `InvalidRuleConfigException`을 던짐 | `IllegalArgumentException` / `IllegalStateException`을 기대한 `catch` 블록 수정 |
| `RateLimitRule.build()`가 유도 키 라벨이 같은 두 대역을 거부 | 대역 라벨을 다르게, 또는 `(capacity, window)` 조합을 다르게 |
| `permits > 대역 용량`이 `InvalidRuleConfigException`을 던짐 | 호출 전에 비용을 용량과 비교해 검증 |
| Redis 연결 실패가 `org.fluxgate.core.exception.RedisConnectionException`을 던짐 | 기존 `catch`는 그대로 컴파일됩니다. 모듈 로컬 타입이 이제 이를 상속합니다 |
| `RedisRateLimiterConfig` 생성자에서 `throws IOException` 제거 | 생성자를 감싼 `catch (IOException)`이 있으면 제거 |
| `CircuitBreaker.execute(...)`는 서킷이 열려 있으면 항상 `CircuitBreakerOpenException`을 던지고, 어떤 것도 `null`을 반환하지 않음 | `executeWithFallback(operation, action, fallback)` 사용 |
| 제거: `BucketState.getRetryAfterSeconds()`, `RedisTokenBucketStore.close()`, `DefaultCircuitBreaker.handleOpenState()` | CHANGELOG의 대체 수단 참고 |
| `fluxgate.ratelimit.filter-order`가 `Integer`, 기본 미설정 | 프로퍼티를 직접 읽는다면 `null` 처리 |
| `RateLimitResult.allowedWithoutRule()`이 `remainingTokens = -1` 보고 | `-1`은 "알 수 없음"이므로 헤더를 생략하고, `-1`을 출력하지 마세요 |
| 거부 결과가 **실제** 남은 토큰을 담음 | 거부 시 `0`을 단정한 코드 수정 |

바인딩 호환을 위해 남겨둔 deprecated + 무동작 항목: `fluxgate.ratelimit.filter-enabled`,
`fluxgate.resilience.circuit-breaker.fallback`, `@RateLimit(maxConcurrentWaits = …)`,
`FluxgateConstants.Metrics.REQUESTS_TOTAL`, `LuaScripts`, `LuaScriptLoader`, `RedisRuleSetStore`,
`RuleSetData`, `org.fluxgate.redis.connection.RedisConnectionException`.

`@RateLimit(maxConcurrentWaits = …)`는 대기 세마포어가 애스펙트 전역이 되었으므로 무시됩니다.
`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits`를 쓰세요(기본값이 100 → 50으로 낮아졌습니다).

## 12. 요청하지 않았지만 알아야 하는 동작 변경

- **24시간을 넘는 윈도가 전체 길이만큼 적용됩니다.** 옛 TTL 상한은 7일 쿼터를 24시간마다
  초기화해 실질적으로 용량의 7배를 허용했습니다. 긴 윈도를 설정하고 깨진 동작에 맞춰 용량을 조정해
  두었다면 다시 조정하세요.
- **라벨 없는 두 대역이 더는 충돌하지 않습니다.** 라벨이 없는 대역은
  `<capacity>-per-<windowSeconds>s`로 키를 만들므로, 두 대역 중 하나만 적용되던 규칙이 이제 둘 다
  적용합니다. 1초 미만 윈도는 `-per-<millis>ms`를 씁니다.
- **대역 라벨을 바꾸면 그 대역의 버킷이 이동**해 한 번 초기화됩니다.
- **거부된 요청이 같은 규칙의 다른 대역도, 앞선 규칙도 소모하지 않습니다.** 단독(standalone)
  Redis이거나 일치하는 규칙들의 키가 모두 한 클러스터 슬롯에 있으면 모든 규칙을 하나의 Lua 호출에서
  전부-또는-전무로 평가합니다. 그렇지 않으면(클러스터에서 규칙들이 다른 슬롯에 있는 경우) 규칙을
  하나씩 차감하고, 뒤의 규칙이 거부하면 이미 차감한 규칙을 환불합니다. 이 보상은 원자적이지 않습니다.
  규칙당 한 번의 왕복 동안 동시 요청은 앞선 규칙을 한 개 적게 보며, 환불이 실행되지 못하면(프로세스
  종료, 그 사이의 Redis 장애) 그 토큰은 소비된 채로 남습니다. 클러스터에서 엄격한 원자성이 필요하면
  여러 대역을 가진 하나의 규칙을 쓰세요. 인메모리 리미터(`mode=IN_MEMORY`와 `IN_MEMORY` 폴백)는
  이런 단서 없이 규칙 간에도 전부-또는-전무입니다.
- **`collect-headers` 기본값이 `false`**이므로, 옵트인하고 허용 목록에 넣지 않으면
  `RequestContext.getHeaders()`는 비어 있습니다. `Authorization`, `Cookie`, `Set-Cookie`,
  `Proxy-Authorization`, `X-API-Key`는 절대 복사되지 않습니다.
- **파싱할 수 없는 Pub/Sub 리로드 메시지는 무시**되며 전체 리로드로 취급되지 않습니다. 메시지는
  `version: 1`을 담고, `AUTO` / `PUBSUB`는 항상 60초 폴링 백스톱을 함께 돌립니다.
- **MongoDB 실패가 FluxGate 예외로 전파**되며 "규칙 없음"처럼 보이지 않습니다. 연결 실패가
  `missing-rule-behavior`에 따라 전면 통과나 전면 거부로 바뀌던 문제가 사라졌습니다.
- **HTTP 요청이 없는 `@RateLimit` 메서드가 동작**하며, 거부 시
  `RateLimitExceededException`을 던집니다(`throwOnReject = true`면 항상).
- **`@RateLimit(maxWaitTimeMs)`가 `wait-for-refill.max-wait-time-ms`를 넘을 수 없습니다.** 실제 대기
  한도는 둘 중 작은 값입니다. WAIT_FOR_REFILL은 필터와 Aspect 모두에서 여전히 요청 스레드를
  차단합니다. Servlet `ASYNC` 재디스패치는 FluxGate 뒤에 등록된 필터를 건너뛰므로 FluxGate는 비동기로
  대기하지 않습니다. 429와 `Retry-After`, 클라이언트 백오프를 권장하며, 논블로킹 대기는 WebFlux
  스택의 영역입니다.

## 13. 헬스와 운영

로드밸런서가 헬스를 검사한다면 커스텀 `DEGRADED` 상태를 매핑하세요. Spring Boot는 기본적으로
HTTP 200으로 매핑합니다:

```yaml
management:
  endpoint:
    health:
      status:
        http-mapping:
          DEGRADED: 503
```

헬스 인디케이터는 `filter-enabled` 프로퍼티가 아니라 실제 필터·애스펙트 빈을 탐지하므로, 필터가
돌고 있는데 `filterEnabled: false`로 보고하던 문제가 해결되었습니다.

경보할 `fluxgate.limiter.failures` 태그 값:

| `action` | 의미 |
|----------|------|
| `fail_open` | 요청이 무제한 통과 중 — 0이 아니면 경보 |
| `fail_closed` | 의존성 장애로 요청이 거부 중 |
| `fallback_in_memory` | 제한이 전역이 아니라 인스턴스별로 적용 중 |

바로 쓸 수 있는 자산: [`docker/grafana/fluxgate-dashboard.json`](../../../docker/grafana/fluxgate-dashboard.json),
[`docker/prometheus/fluxgate-alerts.yml`](../../../docker/prometheus/fluxgate-alerts.yml).

## 14. 테스트 명령이 바뀌었습니다

```bash
./mvnw test              # 단위 테스트만 - Docker, Redis, MongoDB 불필요
./mvnw verify            # 단위 + 통합 테스트 (failsafe, *IntegrationTest / *IT)
./mvnw verify -DskipITs  # 통합 계층 명시적 생략
```

`./mvnw test`는 더 이상 통합 테스트를 실행하지 않습니다. 통합 테스트는 Testcontainers를 쓰며,
`FLUXGATE_REDIS_URI` / `FLUXGATE_MONGO_URI`도 Docker 데몬도 없으면 실패가 아니라 **건너뜁니다**.
또한 자기가 만들지 않은 `flushdb()`나 컬렉션 드롭을 하지 않으므로 공유 개발 서버를 가리켜도
안전합니다.

포크에서 FluxGate 통합 테스트를 이름 변경했다면 참고하세요.
`RedisRateLimiterTest` → `RedisRateLimiterIntegrationTest`,
`RedisTokenBucketStoreTest` → `RedisTokenBucketStoreIntegrationTest`.

## 15. 이제 명시적으로 켜야 하는 보안 기본값

### 신원 헤더는 켜지 않으면 무시됩니다

`PER_USER`와 `PER_API_KEY`는 인증된 principal이 없으면 `X-User-Id`, `X-API-Key`를 읽었습니다. 이
헤더는 클라이언트 입력이므로 익명 호출자가 자신이 소비할 버킷을 고르거나 돌려 쓸 수 있었습니다.
0.4에서는 Spring Security 유무와 관계없이 `fluxgate.ratelimit.identity.source` 기본값이
`PRINCIPAL`입니다.

| 요청 | 0.3 / 초기 0.4 스냅샷 | 0.4 기본값 (`PRINCIPAL`) |
|------|----------------------|--------------------------|
| 인증됨 | 헤더(0.3), principal(스냅샷) | principal 이름 |
| 미인증, `X-User-Id` / `X-API-Key` 전송 | 헤더 값 | **신원 없음** → `missing-key-behavior` |
| 클래스패스에 Spring Security 없음 | 헤더 값 | **신원 없음** → `missing-key-behavior` (시작 시 WARN) |

신원이 없는 요청은 `fluxgate.ratelimit.missing-key-behavior`를 따릅니다. `FALLBACK_TO_IP`(기본값)는
IP 기준으로 제한하고, `REJECT`는 429로 응답합니다. API 키는 principal에서 가져오지 않으므로
`PER_API_KEY` 규칙에는 헤더 opt-in이나, 직접 검증한 자격 증명으로 값을 채우는
`RequestContextCustomizer`가 필요합니다.

신뢰할 수 있는 프록시·게이트웨이가 이 헤더를 설정하거나 제거한다면 명시적으로 다시 켜세요.

```yaml
fluxgate:
  ratelimit:
    identity:
      source: PRINCIPAL_THEN_HEADERS   # 또는 HEADERS
      user-id-header: X-User-Id        # 프록시가 설정하는 헤더
      api-key-header: X-API-Key
```

`HEADERS`나 `PRINCIPAL_THEN_HEADERS`가 적용되면 시작 시 헤더 이름과 함께 WARN을 남깁니다.
MDC의 `userId` / `apiKey`도 리미터가 실제로 해석한 신원(기본값은 principal 이름)을 담으며, 원본
신원 헤더는 헤더 신원을 켰을 때만 로그에 남습니다. 익명 트래픽의 `X-User-Id`를 `userId`로 조회하던
로그 쿼리는 빈 값을 보게 됩니다.
`IdentitySource`를 받지 않는 `RequestContextFactory`, `FluxgateRateLimitFilter`, `RateLimitAspect`의
편의 생성자도 같은 `PRINCIPAL` 기본값을 따릅니다.

### Pub/Sub 룰 리로드에는 서명 시크릿이 필요합니다

룰 리로드 메시지는 모든 데이터 플레인 인스턴스에 룰 재적재와 버킷 리셋을 지시합니다. 서명이 없으면
Redis에 닿을 수 있는 누구든 `PUBLISH fluxgate:rule-reload '*'` 한 줄로 모든 토큰 버킷을 날릴 수
있습니다. 0.4에서는 양쪽 모두 서명이 필수입니다.

| 쪽 | 필수 설정 | 없으면 |
|----|----------|--------|
| 데이터 플레인, `fluxgate.redis.enabled=true`인 `AUTO`(기본값) | `fluxgate.reload.pubsub.secret` | WARN 후 `POLLING`으로 대체 — 룰 변경이 즉시가 아니라 `fluxgate.reload.polling.interval`(30초) 안에 반영 |
| 데이터 플레인, 명시적 `fluxgate.reload.strategy=PUBSUB` | `fluxgate.reload.pubsub.secret` | 기동 실패 (`IllegalStateException`) |
| `fluxgate-control-support`를 쓰는 컨트롤 플레인 (`fluxgate.control.redis.uri`를 설정했을 때만 활성) | `fluxgate.control.secret` | 기동 실패 (`IllegalStateException`) |

```yaml
# 데이터 플레인
fluxgate:
  reload:
    pubsub:
      secret: ${FLUXGATE_RELOAD_SECRET}
---
# 컨트롤 플레인
fluxgate:
  control:
    secret: ${FLUXGATE_RELOAD_SECRET}   # 같은 값
```

시크릿은 컨트롤 플레인과 모든 데이터 플레인에 같은 배포에서 적용하세요. 시크릿이 있는 데이터
플레인은 서명 없는 메시지를 무시하므로, 아직 서명 없이 발행하는 컨트롤 플레인의 알림은 폴링
백스톱(`backstop-polling-interval`, 60초)이 따라잡을 때까지 조용히 무시됩니다.

데이터 플레인이 `AUTO`라면 아무것도 하지 않아도 폴링으로 계속 동작합니다. WARN을 확인하고
시크릿을 추가하면 푸시 방식 리로드가 돌아옵니다. 대안: `fluxgate.reload.strategy=POLLING`은 채널
자체가 필요 없습니다. 로컬 개발에서만
`fluxgate.reload.pubsub.allow-unsigned=true`와 `fluxgate.control.allow-unsigned=true`로 인증 없는
채널을 되살릴 수 있으며, 기동 시 WARN을 남기고 시크릿이 설정되면 무시됩니다.

## 업그레이드 체크리스트

- [ ] 일회성 쿼터 초기화를 계획하고 트래픽이 적은 시간대 선택
- [ ] FluxGate 키·버킷 해시를 읽는 외부 도구 수정
- [ ] 옛 `/*` 기본값에 의존했다면 `include-patterns` 명시적 설정
- [ ] `fluxgate.*` 설정 재검토 — 무시되던 프로퍼티가 이제 적용됩니다
- [ ] `trust-client-ip-header=true`라면 `trusted-proxies` 설정
- [ ] 429 본문을 파싱하는 클라이언트 수정, 또는 `response.body-template` 설정
- [ ] `result` 태그 없이 `fluxgate_requests_total`을 쓰는 대시보드 수정
- [ ] Redis 연결만 하던 핸들러 삭제
- [ ] Redis 장애 시 `failure-behavior`와 `fallback.mode=IN_MEMORY` 중 선택
- [ ] `management.endpoint.health.status.http-mapping.DEGRADED=503` 추가
- [ ] CI를 `./mvnw test`에서 `./mvnw verify`로 전환
- [ ] 11절의 예외·제거 메서드 변경에 맞춰 재컴파일
- [ ] 신뢰할 수 있는 프록시가 신원 헤더를 제공한다면 `identity.source=HEADERS` 또는 `PRINCIPAL_THEN_HEADERS` 설정 (15절)
- [ ] Pub/Sub 리로드를 쓴다면 데이터·컨트롤 플레인에 같은 `fluxgate.reload.pubsub.secret` / `fluxgate.control.secret` 설정 (15절)

---

## 관련 문서

- [CHANGELOG](../../../CHANGELOG.md) - Breaking 절이 포함된 전체 목록
- [보안 정책](../../../SECURITY.md) - 보안 기본값과 신원 헤더 주의사항
- [Key Resolver](../customization/key-resolver.ko.md) - 키 형태 변경이 쿼터를 초기화하는 이유
- [문서 색인](../../README.ko.md)
