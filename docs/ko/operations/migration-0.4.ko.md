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

최종 0.4 라인에는 이미 의존하고 있을 수 있는 동작을 바꾸는 두 번째 하드닝이 더해졌습니다. Rate
Limit을 사용할 수 없을 때 429 대신 **503**을 응답하고, MongoDB는 기동 전에 고유 룰 인덱스가 필요하며,
룰 변경 메시지는 스키마 버전 2로 올라가고, 잘못된 설정은 기동을 실패시키며, 키 형식은 단사(injective)
이스케이프를 씁니다. 16절에서 항목마다 무엇이 바뀌는지, 누가 영향을 받는지, 무엇을 해야 하는지
설명합니다.

## 1. 모든 쿼터가 한 번 초기화됩니다

버킷 키와 버킷 해시 레이아웃이 모두 바뀝니다:

| | 0.3.x | 0.4 |
|---|---|---|
| 키 | `fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel\|default}` | `fluxgate:bucket:{<ruleSetId>:<ruleId>:<keyValue>}:<bandKeyLabel>` |
| 키 값 | `192.168.1.100` | `ip:192.168.1.100` (스코프 접두사, 새니타이즈 — 16.3절 참고) |
| 해시 필드 | `tokens`, `last_refill_nanos` | `tokens`, `last_refill_micros` |

세 가지 변경이지만 초기화는 **한 번**입니다. 첫 기동 시 모든 호출자가 가득 찬 버킷을 받습니다.
`{...}` 해시 태그는 한 규칙+키의 모든 대역을 하나의 Redis Cluster 슬롯에 고정하며, 다중 대역
스크립트가 클러스터에서 원자적으로 동작하기 위한 전제 조건입니다.

**할 일:**

- 일시적 버스트가 문제라면 트래픽이 적은 시간대에 배포하세요.
- 옛 키는 읽히지 않고 자신의 TTL로 소멸합니다. 메모리를 더 빨리 회수하려면 0.3 키 목록을 먼저
  만들고, 목록을 확인한 뒤에만 지우세요. `grep`은 0.4가 여전히 쓰는 키 계열을 모두 남깁니다. 버킷
  (`fluxgate:bucket:*`, FIXED_WINDOW 카운터 포함)과 deprecated된 Redis 규칙 저장소
  (`fluxgate:ruleset:*`와 그 인덱스 `fluxgate:rulesets`)입니다.
  ```bash
  # 1. 드라이 런: 지울 키를 파일로 써서 검토합니다.
  redis-cli --scan --pattern 'fluxgate:*' \
    | grep -v -e '^fluxgate:bucket:' -e '^fluxgate:ruleset:' -e '^fluxgate:rulesets$' \
    > fluxgate-0.3-keys.txt
  wc -l fluxgate-0.3-keys.txt && head fluxgate-0.3-keys.txt

  # 2. 검토한 키만 지웁니다(NUL 구분이라 키 값의 공백·따옴표도 안전합니다).
  tr '\n' '\0' < fluxgate-0.3-keys.txt | xargs -0 -r -n 100 redis-cli unlink
  ```
  두 `redis-cli` 호출 모두에 평소 쓰는 접속 옵션(`-h`, `-p`, `--user`, `--pass`, `--tls`)을 붙이세요.
  Redis Cluster에서는 두 단계를 각 프라이머리 노드에 대해 실행하고 2단계에 `-n 1`을 쓰세요. 여러
  슬롯에 걸친 다중 키 `UNLINK`는 `CROSSSLOT`으로 실패합니다.
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
      # 템플릿은 503에도 쓰이므로, 클라이언트가 둘을 구분한다면 503 본문을 따로 지정
      unavailable-body-template: '{"error":"Service Unavailable","retryAfter":{retryAfterSeconds}}'
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
`DENY`(기본)는 **503**(16.2절 참고), `ALLOW`는 무제한 통과입니다. 이제 보통 더 나은 세 번째 선택지가 있습니다:

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
| `ClientIpExtractor.extract(request)`와 `extract(request, header, trustHeader)`가 `@Deprecated` (Breaking 24) | 4개 인자의 `extract(request, header, trustHeader, TrustedProxies)`로 이전하세요. 인자 하나짜리는 `X-Forwarded-For`를 무조건 신뢰하고, 신뢰 프록시 목록이 없는 3개 인자 형태는 클라이언트가 위조할 수 있는 홉을 취합니다 |
| `Phase` 없는 `RedisConnectionException` 생성자는 `UNKNOWN`이며 재시도하지 **않음** (Breaking 33) | 예외를 던지면서 재시도를 기대하는 코드는 `Phase.CONNECT`를 전달하세요 (16.7절) |
| `RateLimitRuleSet.Builder.build()`가 id가 같은 두 규칙을 `InvalidRuleConfigException`으로 거부 (Breaking 32) | 한 룰 세트 안의 규칙마다 고유한 id를 부여 |
| `MongoRateLimitRuleRepository.findById(id)`, `existsById(id)`, `deleteById(id)`가 `@Deprecated`. 여러 룰 세트에 존재하는 id에 `findById(id)`·`deleteById(id)`는 `IllegalStateException`을 던지고 `existsById(id)`는 `true`를 반환 (Breaking 28) | `(ruleSetId, id)` 오버로드를 쓰고, 규칙 이동에는 `moveRule(id, from, to)` 사용 (16.1절) |
| `ResilientRateLimiter`, `EngineBackedRateLimitHandler`, `MissingRuleSetProviderRateLimitHandler`가 거부 결과를 반환하는 대신 `RateLimiterUnavailableException`을 던짐 (Breaking 36) | 이 클래스를 호출하는 코드는 예외를 처리 (16.2절) |

바인딩 호환을 위해 남겨둔 deprecated + 무동작 항목: `fluxgate.ratelimit.filter-enabled`,
`fluxgate.resilience.circuit-breaker.fallback`, `@RateLimit(maxConcurrentWaits = …)`,
`FluxgateConstants.Metrics.REQUESTS_TOTAL`, `LuaScripts`, `LuaScriptLoader`, `RedisRuleSetStore`,
`RuleSetData`, `org.fluxgate.redis.connection.RedisConnectionException`.

`@RateLimit(maxConcurrentWaits = …)`는 대기 허가가 필터와 애스펙트가 함께 쓰는 `FluxgateWaitPermits` 빈 하나가 되었으므로 무시됩니다.
`fluxgate.ratelimit.wait-for-refill.max-concurrent-waits`를 쓰세요(기본값이 100 → 50으로 낮아졌습니다).

## 12. 요청하지 않았지만 알아야 하는 동작 변경

- **고정 24시간 버킷 TTL 상한이 없어지고 `fluxgate.redis.max-bucket-ttl`(기본값 `7d`)이 대신합니다.**
  옛 상한은 7일 쿼터를 24시간마다 초기화해 실질적으로 용량의 7배를 허용했습니다. FIXED_WINDOW
  카운터는 이제 윈도 끝에 만료되며(`PEXPIREAT`) 상한에서 제외됩니다. TOKEN_BUCKET과 SLIDING_WINDOW의
  TTL은 `max-bucket-ttl`로 제한됩니다. 그보다 오래 유휴 상태인 버킷은 만료되어 가득 찬 상태로 다시
  시작하므로, 상한보다 긴 윈도는 유휴 호출자에게 실질적으로 짧아지며 리미터는 그런 규칙마다 WARN을
  한 번 남깁니다. 더 긴 윈도가 필요하면 `max-bucket-ttl`을 올리세요. 긴 윈도의 용량을 옛 동작에 맞춰
  조정해 두었다면 다시 조정하세요.
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
  `version: 2`와 nonce를 담고(16.4절), `AUTO` / `PUBSUB`는 항상 60초 폴링 백스톱을 함께 돌립니다.
- **MongoDB 실패가 FluxGate 예외로 전파**되며 "규칙 없음"처럼 보이지 않습니다. 연결 실패가
  `missing-rule-behavior`에 따라 전면 통과나 전면 거부로 바뀌던 문제가 사라졌습니다.
- **`@RateLimit(maxWaitTimeMs)`가 `wait-for-refill.max-wait-time-ms`를 넘을 수 없습니다.** 실제 대기
  한도는 둘 중 작은 값입니다. WAIT_FOR_REFILL은 필터와 Aspect 모두에서 여전히 요청 스레드를
  차단합니다. Servlet `ASYNC` 재디스패치는 FluxGate 뒤에 등록된 필터를 건너뛰므로 FluxGate는 비동기로
  대기하지 않습니다. 429와 `Retry-After`, 클라이언트 백오프를 권장하며, 논블로킹 대기는 WebFlux
  스택의 영역입니다.
- **HTTP 요청이 없는 `@RateLimit` 메서드가 동작**하며, 거부 시
  `RateLimitExceededException`을 던집니다(`throwOnReject = true`면 항상).
- **룰 변경 알림은 트랜잭션 커밋 후에 발행됩니다** (Breaking 23). `@Transactional` 경계 안의
  `@NotifyRuleChange` / `@NotifyFullReload`는 `afterCommit`에서 발행하며, **롤백되면 아예 발행하지
  않습니다.** "메서드가 반환되자마자 발행된다"고 가정한 테스트나 운영 스크립트는 수정해야 합니다.
  호출 직후가 아니라 커밋 이후에 단정하세요. 발행 실패는 별도 데몬 스레드에서 세 번 재시도하므로,
  Redis의 일시적 문제가 업무 트랜잭션을 실패시키지 않습니다.

## 13. 헬스와 운영

커스텀 `DEGRADED` 상태는 이제 **기본적으로 HTTP 503**을 응답합니다. FluxGate가
`management.endpoint.health.status.http-mapping` 기본값을 최저 우선순위 프로퍼티 소스로 추가합니다.
Spring Boot 자체 기본값인 `down=503`, `out-of-service=503`과 함께
`degraded=<fluxgate.actuator.health.degraded-http-status>`(기본 `503`)입니다:

```yaml
fluxgate:
  actuator:
    health:
      degraded-http-status: 503   # 0(또는 음수)이면 DEGRADED 매핑을 추가하지 않음. 이 경우 Boot는 200 응답
```

직접 매핑하지 않은 상태만 추가되므로, 사용자의 `http-mapping` 항목이나 `HttpCodeStatusMapper` 빈이 항상
우선합니다. 직접 쓴 `DEGRADED: 503` 매핑은 이제 필요 없으며, 이전 0.4 빌드처럼 `DOWN`을
HTTP 200으로 만들지도 않습니다(비어 있지 않은 매핑은 Spring Boot가 자체 `DOWN`, `OUT_OF_SERVICE` 기본값을
버리게 만드는데, 이제 FluxGate가 직접 매핑하지 않은 상태에 대해 이를 채워 줍니다).
`degraded-http-status: 0`으로 두거나 헬스 인디케이터를 끄면 FluxGate는 매핑을 추가하지 않고 Spring Boot의
규칙이 다시 적용되므로, 직접 쓴 매핑에 `DOWN: 503`을 포함하세요.

이제 이 상태는 **집계된** 헬스에도 반영됩니다. Spring Boot의 기본
`management.endpoint.health.status.order`에는 `DEGRADED`가 없고, 집계기는 모르는 상태를 버리기 때문에
`/actuator/health/fluxgate`가 `DEGRADED`여도 `/actuator/health`와 `readiness` 그룹은 `UP`(HTTP 200)을
보고했습니다. FluxGate는 같은 최저 우선순위 기본값으로
`management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown`을 추가하며, 자체 순서가
없는 그룹은 이를 물려받습니다. 따라서 FluxGate가 degraded이면 루트 엔드포인트와 `fluxgate`를 포함한
readiness 그룹도 **503**을 응답합니다. 엔드포인트나 그룹에 직접 지정한 순서는 그대로 두며, 거기에
`degraded`가 없으면 기동 시 WARN으로 알려 줍니다. `degraded-http-status: 0`은 순서는 유지합니다
(`DEGRADED`로 집계되지만 200 응답). `fluxgate.actuator.health.enabled=false`면 둘 다 추가하지 않습니다.

`PING`에는 응답하지만 `cluster_state`가 `ok`가 아니거나, 실패한 슬롯이 있거나, 노드 목록을 읽을 수 없는
Redis Cluster는 `DOWN`으로 보고합니다. 헬스 체크는 부수 효과로 지연 Redis 연결을 만들지 않으며,
`fluxgate.actuator.health.include-endpoint-details=true`가 아니면 응답에 `host:port`와 실패 메시지가
들어가지 않습니다.

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

## 16. 최종 0.4 라인의 하드닝 변경

이 변경들은 초기 0.4 빌드와 0.3.x가 잘못 처리하던 동작을 엄격하게 만듭니다. MongoDB 룰을 쓰거나,
Pub/Sub 리로드를 쓰거나, 429와 503을 다르게 취급하거나, FluxGate 컴포넌트를 직접 생성한다면 특히
중요합니다. 번호는 CHANGELOG의 *Breaking / Migration* 목록 항목입니다.

### 16.1 MongoDB: 고유 룰 인덱스와 룰 식별 (Breaking 28, 29)

**무엇이 바뀌나.** 규칙은 `(ruleSetId, id)`로 식별되며, 컬렉션에는 정확히 그 키에 대한 고유 인덱스가
필요합니다. 규칙 `r1`을 룰 세트 B에 저장하면 룰 세트 A에서 이동되던 동작이 사라졌습니다. 이제 두 번째
문서가 삽입되며, 이동은 `moveRule(id, fromRuleSetId, toRuleSetId)`로 명시합니다. `findById(id)`,
`existsById(id)`, `deleteById(id)`는 `@Deprecated`입니다. id가 여러 룰 세트에 있으면 `findById(id)`와
`deleteById(id)`는 `IllegalStateException`을 던지고, 예/아니오 질의인 `existsById(id)`는 `true`를
반환합니다. 룰 세트에 문서가 하나도 없을 때 `saveAccessControl`은 예외를
던집니다.

**누가 영향을 받나.** `fluxgate.mongo.enabled=true`인 모든 사용자입니다. 기본값
`ddl-auto=validate`가 기동 시 인덱스를 확인하기 때문입니다:

| `fluxgate.mongo.ddl-auto` | 동작 |
|---------------------------|------|
| `validate` (기본) | `{ruleSetId: 1, id: 1}` 고유 인덱스가 없으면 기동 실패. 인덱스 이름은 상관없지만 `sparse` 인덱스, `partialFilterExpression`, `simple` 외의 콜레이션은 모든 규칙의 고유성을 보장하지 않으므로 거부됨. 메시지에 `createIndex` 명령과 `ddl-auto=create` 안내가 담김 |
| `create` | 컬렉션을 만들고 `MongoRateLimitRuleRepository#ensureIndexes()`를 호출해 `ruleSetId_1_id_1_unique`(고유)와 `id_1`을 생성. 중복 `(ruleSetId, id)` 쌍이나 충돌하는 인덱스가 있으면 해당 쌍을 나열한 `IllegalStateException`으로 기동 실패 — 예전에는 경고만 남기고 모호한 룰 세트를 서빙했음 |

**무엇을 해야 하나.** 기존 배포를 업그레이드하기 전에 룰 컬렉션(`fluxgate.mongo.rule-collection`을
바꾸지 않았다면 `rate_limit_rules`)에 인덱스를 만드세요:

```javascript
db.rate_limit_rules.createIndex(
  { ruleSetId: 1, id: 1 },
  { unique: true, name: "ruleSetId_1_id_1_unique" }
)
```

명령이 중복 키 오류로 실패하면 두 문서가 같은 `(ruleSetId, id)` 쌍을 갖고 있다는 뜻입니다. 목록을 뽑아
여분을 삭제하거나 이름을 바꾼 뒤 다시 실행하세요:

```javascript
db.rate_limit_rules.aggregate([
  { $group: { _id: { ruleSetId: "$ruleSetId", id: "$id" }, n: { $sum: 1 } } },
  { $match: { n: { $gt: 1 } } }
])
```

또는 인스턴스 하나를 `fluxgate.mongo.ddl-auto=create`로 한 번 기동한 뒤 `validate`로 되돌려도 됩니다.
standalone 샘플의 기본값은 `validate`이며, `create`를 쓰는 `fluxgate-sample-mongo`가 먼저 만든
컬렉션에는 인덱스가 이미 있습니다. 코드에서는 `(ruleSetId, id)` 오버로드로 옮기고, "다른 룰 세트에
저장해서 이동"하던 부분은 `moveRule`로 바꾸세요.

조치가 필요 없지만 새 로그 줄을 설명하는 Mongo 변경이 둘 더 있습니다. BSON 숫자는 관대하게(`int`,
`long`, `double`) 읽고 알 수 없는 enum 값은 거부합니다. 형식이 잘못된 룰 문서는 룰 세트 전체를
실패시키지 않고 WARN과 함께 건너뛰며 집계합니다. 룰 문서의 접근 제어를 쓸 때마다(`saveAccessControl`,
새 규칙, `moveRule`) `aclUpdatedAt` 필드가 추가되며 0.3.x는 이 필드를 무시합니다. `saveAccessControl`이
중간에 끊겨 접근 제어 사본이 서로 달라지면 닫힌 쪽으로 병합하고(거부 목록은 합집합, 허용 목록은 교집합,
없는 목록은 빈 목록, WARN 기록), `aclUpdatedAt`도 목록도 없는 문서(예: 0.3.x가 넣은 규칙)는 병합에서
제외합니다. Rate Limit 이벤트는 이제 제한된 큐(10000건)에서
데몬 스레드가 기록하므로 느린 MongoDB가 요청을 막지 않으며, 큐가 가득 차면 이벤트를 버립니다.
이벤트의 헤더·속성 이름은 `.`과 `$`를 `_`로 바꾸는 대신 되돌릴 수 있게 이스케이프합니다(`.`은 `%2E`,
`$`는 `%24`, NUL은 `%00`, `%`는 `%25`, 빈 이름은 `%`). 그런 필드를 조회할 때는 이스케이프된 이름을 씁니다.

### 16.2 Rate Limit을 사용할 수 없을 때 429 대신 503 (Breaking 36)

**무엇이 바뀌나.** 429는 이제 한 가지 의미만 갖습니다. 한도를 초과했다는 뜻입니다.

| 상황 | 상태 |
|------|------|
| 한도 초과 | 429, `Retry-After` |
| `failure-behavior=DENY`에서 리미터 실패 (Redis 다운, 재시도 소진, 서킷 오픈) | **503**. `Retry-After`는 대기 시간을 알 때만, 예를 들어 서킷 브레이커가 열려 있는 동안 |
| `missing-rule-behavior=DENY`에서 룰 세트나 provider 없음 | **503** |
| `failure-behavior=ALLOW` / `missing-rule-behavior=ALLOW` | 요청 통과. 단, 만들 수 없는 룰 세트는 제외 |
| 만들 수 없는 룰 세트(`InvalidRuleConfigException`), `failure-behavior` 값과 **무관** | **503**. 0.4 이전에는 `failure-behavior=ALLOW`가 이런 요청을 통과시켰지만, 이제는 리미터 실패에만 적용됨 |
| 장애 중 `fallback.mode=IN_MEMORY` | 인스턴스별 제한, 초과 시 429 |
| (`max-cost` 적용 후의) `cost-header` 값이 일치하는 밴드의 용량보다 큼 | `Retry-After` 없는 **429**. problem 문서에 비용과 용량이 담김. 클라이언트 오류이므로 서킷 브레이커에 집계되지 않고 폴백이나 `failure-behavior`로 처리되지도 않음 |

**누가 영향을 받나.** 429에서만 재시도하거나 경보하는 클라이언트와 게이트웨이, 4xx를 클라이언트
오류로 집계하는 대시보드, 그리고 `ResilientRateLimiter`, `EngineBackedRateLimitHandler`,
`MissingRuleSetProviderRateLimitHandler`를 직접 호출하는 코드입니다. 이 클래스들은 거부 결과를 반환하던
자리에서 `RateLimiterUnavailableException`을 던집니다. 이제 장애가 5xx로 보이며, 모니터링이 기대하는
모습입니다.

**무엇을 해야 하나.** 클라이언트가 503을 백오프와 함께 재시도하게 하고, "Rate Limiter 다운" 경보를
5xx로 옮기며, 429 처리는 실제 스로틀링용으로 남겨두세요. 커스텀 `RateLimitResponseWriter`는 기본
`writeUnavailable(request, response, retryAfterMillis)`를 상속하며, 503 본문을 바꾸려면 이를
오버라이드하거나 `response.unavailable-body-template`을 설정하세요. 과도한 비용에 대해서는
`writeCostExceeded(request, response, permits, capacity)`도 상속합니다. HTTP 핸들러 밖에서 호출된
`@RateLimit` 메서드는 `RateLimitExceededException`을 받고, `isServiceUnavailable()`로 두 경우를
구분합니다. 서블릿 애플리케이션에서는 FluxGate의 기본 예외 핸들러가 이를 429 또는 503으로 응답합니다(16.6절).

과도한 요청 비용은 예전에는 리미터 실패로 취급되었습니다. 복원력 리미터 안에서 잘못된 룰 오류가 나므로,
큰 `cost-header` 값을 보내는 클라이언트 하나가 모두를 위한 서킷 브레이커를 열 수 있었습니다.
`failure-behavior=DENY`에서는 모든 요청이 503, `ALLOW`에서는 제한이 아예 꺼졌습니다. 이제 그런 비용은
브레이커에 닿기 전에 429로 거부됩니다. 거부 대신 잘라내기를 원한다면 `max-cost`를 헤더가 적용되는 룰의
가장 작은 밴드 용량 이하로 두세요. Aspect의 `@RateLimit(permits)`는 코드에 고정된 값이므로 용량보다 크면
설정 오류이며 503으로 응답합니다.

### 16.3 키 형식은 단사(injective)입니다 (Breaking 30, 31)

**무엇이 바뀌나.** 서로 다른 두 신원이 하나의 버킷이나 하나의 허용/거부 항목에 들어가는 일이 없어집니다.

| 입력 | 0.4 키 값 |
|------|-----------|
| 사용자 id `alice` (`[A-Za-z0-9._:@-]`만 포함) | `user:alice` (변경 없음) |
| 사용자 id `a+1` | `user:h:a_1:<16 hex>` — 마커, 제한된 값, 원본 SHA-256의 앞 16자리 16진수 |
| 재작성이 필요하면서 237자를 넘는 값, 또는 256자를 넘는 모든 값 | `user:h:<64 hex>` — 스코프 접두사는 해시 바깥에 유지 |

Redis 버킷 키 세그먼트는 `_`로 치환하는 대신 퍼센트 이스케이프합니다. `ruleSetId`나 `ruleId`의 `:` `{` `}`
`*` `?` `[` `]` `\`, 공백 문자, `%`는 `%XX`(UTF-8)가 되고, 대역 라벨의 `:`와 `%`도 마찬가지입니다.
그래서 `a:b`, `a_b`, `a b`는 서로 다른 버킷을 얻고, 라벨이 `x:fw`인 대역이 라벨 `x`인 대역의
`FIXED_WINDOW` 카운터에 쓰는 일도 없어집니다.

**누가 영향을 받나.** 키 값에 `[A-Za-z0-9._:@-]` 밖의 문자가 있거나 256자를 넘은 호출자, 그리고
이스케이프 대상 문자를 쓰는 룰 세트·규칙·대역 라벨뿐입니다. 해당 버킷은 가득 찬 상태로 한 번 다시
시작합니다(0.3.x에서 올라온다면 1절의 초기화에 더해서). 새니타이즈는 더 이상 멱등이 아니므로 값을
두 번 새니타이즈하지 마세요.

**무엇을 해야 하나.** 키를 만들거나 스캔하는 외부 도구를 수정하세요(`RedisRateLimiter.BUCKET_KEY_PREFIX`와
`bucketKeyPattern(ruleSetId)` 사용). 설정된 `allowed-keys` / `denied-keys`는 같은 규칙으로 정규화되고
바뀐 항목마다 WARN이 남으므로, `user:a+1` 같은 거부 항목도 해당 호출자에 계속 일치합니다. 이미 인코딩된
형태의 항목(로그에서 복사한 `user:h:a_1:<16 hex>`)은 그대로 유지됩니다. `ip:`, `user:`, `key:`,
`custom:`만 인식되므로, `tenant:` 같은 커스텀 접두사에서 값이 다시 쓰이는 항목은 인코딩된 형태
(`tenant:h:a_1:<16 hex>`)로 써야 합니다. 접두사가 붙은 키를 만드는 커스텀
`KeyResolver`는 기본 리졸버처럼 값만 새니타이즈하는 `RateLimitKey.of(prefix, rawValue)`를 쓰세요.
`RateLimitKey.of(key)`는 문자열 전체를 새니타이즈합니다. id가 같은 두
규칙을 가진 룰 세트는 `InvalidRuleConfigException`으로 거부되며(버킷과 메트릭을 공유했기 때문),
`AccessControl` 동등성에 IP 목록이 포함되므로 허용·거부 IP만 바뀐 리로드도 반영됩니다.

### 16.4 Pub/Sub 룰 변경 메시지: 스키마 버전 2와 60초 윈도 (Breaking 34, 35)

**무엇이 바뀌나.** 룰 변경 메시지는 `version: 2`와 무작위 `nonce`를 담습니다. 서명은 **채널**과 nonce를
묶는 길이 접두 정규 형식을 대상으로 합니다. 구독자는 수락한 nonce를 리플레이 윈도 동안 기억하고,
재전송된 메시지, 다른 채널용으로 서명된 메시지, nonce 없는 버전 2 메시지를 무시합니다. 버전 1과
서명 없는 메시지는 이전 규칙을 유지합니다. `fluxgate.reload.pubsub.max-message-age`의 기본값은 이제
`60s`입니다(프로퍼티는 `5m`, 전략 클래스는 `60s`였습니다).

**누가 영향을 받나.** `fluxgate-control-support`(또는 `RuleChangeMessage` 기반 발행자)로 Pub/Sub 리로드를
쓰는 모든 사용자입니다.

**무엇을 해야 하나 — 이 순서로 롤아웃하세요:**

1. **데이터 플레인**(구독자) 인스턴스를 먼저 모두 업그레이드합니다. 버전 1과 2를 모두 이해합니다.
2. 그다음 **컨트롤 플레인**(발행자)을 업그레이드하면 버전 2 전송이 시작됩니다.
3. 양쪽에 같은 시크릿을 씁니다(15절). 시크릿은 양쪽에서 앞뒤 공백이 제거되고 공백뿐이면 "없음"으로
   취급되며, 32바이트보다 짧으면 WARN으로 보고됩니다.

반대 순서도 파괴적이지는 않지만 느립니다. 버전 1만 아는 데이터 플레인은 버전 2 메시지를 알 수 없는
스키마 버전으로 버리므로, 룰 변경은 폴링 백스톱(`backstop-polling-interval`, 60초)을 기다립니다.
윈도는 발행자와 구독자 사이의 시계 비교입니다. 시계를 몇 초 이내로 맞추거나, 예전 허용 폭을 유지하려면
`max-message-age: 5m`을 명시하세요. 윈도는 시크릿이 설정된 경우에만 적용됩니다. 한쪽에서 채널을
바꿨다면 `fluxgate.control.redis.channel`과 `fluxgate.reload.pubsub.channel`을 같은 값으로 맞추세요.
다른 채널용으로 서명된 메시지는 무시됩니다.

롤아웃 중에는 아직 업그레이드하지 않은 발행자의 서명된 버전 1 메시지도 받아들입니다
(`fluxgate.reload.pubsub.accept-legacy-signed`, 기본값 `true`). 처음 받아들일 때 WARN을 한 번 남깁니다.
버전 1은 채널도 nonce도 묶지 않으므로, 모든 컨트롤 플레인이 버전 2를 발행하게 되면
`fluxgate.reload.pubsub.accept-legacy-signed=false`로 설정해 데이터 플레인이 버전 1 메시지를 무시하게 하세요.

메시지는 또한 Lettuce 이벤트 루프 밖의 단일 `fluxgate-pubsub-listener` 스레드에서 도착 순서대로
처리됩니다. 대기는 최대 10000건이며, 넘치면 이후 메시지를 WARN과 함께 버리고 변경은 백스톱 폴링이
반영합니다.

### 16.5 잘못된 설정은 기동을 실패시킵니다 (Breaking 38)

`FluxgateProperties`는 바인딩 중 스스로 검증하므로 Bean Validation 구현체가 필요 없고, YAML 룰 세트는
기동 시점에 즉시 만들어집니다. 예전에는 받아들여진 뒤 런타임에 오동작하던 설정들입니다:

| 설정 | 규칙 |
|------|------|
| `redis.timeout-ms`, `redis.max-bucket-ttl` | 양수여야 함 |
| `ratelimit.fallback.max-buckets`, `fallback.expire-after-access` | 양수여야 함 |
| `ratelimit.wait-for-refill.max-wait-time-ms` | 음수 불가. `max-concurrent-waits`는 양수여야 함 |
| `reload.cache.ttl`, `cache.max-size`, `polling.interval`, `pubsub.retry-interval`, `pubsub.max-message-age` | 양수여야 함 |
| `reload.cache.negative-ttl`, `polling.initial-delay`, `pubsub.backstop-polling-interval` | 0 허용("비활성"), 음수 불가 |
| YAML 대역 `zone-id` | 알 수 없는 id는 실패 (조용히 UTC로 폴백해 모든 달력 경계가 어긋났음) |
| YAML 규칙 `id`, 룰 세트 `id` | 한 룰 세트 안에서 규칙 id 중복, 룰 세트 id 중복, id 누락은 실패 |
| YAML 대역 `window`, `capacity` | 누락, 0, 음수는 실패 |

메시지에는 프로퍼티 이름이 들어갑니다. 예: `fluxgate.redis.timeout-ms must be > 0 (got 0)`. 테스트
환경에서 한 번 기동해 문제 설정을 찾으세요. 활성화된 `PER_API_KEY` YAML 규칙이 있는데
`identity.source`가 API 키 헤더를 전혀 읽지 않는 경우에도 WARN이 남습니다(그 규칙은 아무에게도 적용되지
않습니다). 스키마는 [YAML 룰 세트 가이드](../guides/yaml-rule-sets.ko.md)를 참고하세요.

### 16.6 직접 만든 컴포넌트와 프레임워크 배선 (Breaking 32, 37, 39, 40)

- **레거시 생성자가 기본적으로 안전합니다 (37).** `new FluxgateRateLimitFilter(handler, ruleSetId,
  include, exclude)`와 7·8개 인자 형태, `new RateLimitAspect(handler, customizer)`는 `X-Forwarded-For`를
  신뢰하고 fail-open이었습니다. 이제 전달 헤더를 무시하고 fail-closed입니다(리미터 실패는 503,
  16.2절). 예전 동작이 필요하면 `clientIpHeader`, `trustClientIpHeader`, `failOpenOnError`를 받는
  생성자를 쓰세요.
- **MVC 핸들러가 아닌 곳의 `@RateLimit`은 예외를 던집니다 (39).** 가로챈 메서드가 Spring MVC 핸들러
  (`@RequestMapping` 또는 단축 애너테이션, 구현한 인터페이스 쪽도 포함)이고 반환 타입이 프리미티브가
  아닐 때만 429를 씁니다. 서비스 메서드, 스케줄 작업, 메시지 리스너는 `RateLimitExceededException`을
  받습니다. 예전에는 현재 응답에 429를 쓰고 기대하지 않던 호출자에게 `null`을 반환했으며, 프리미티브
  반환형에서는 `AopInvocationException`으로 실패했습니다. 서블릿 애플리케이션에서는 Aspect가 활성일 때
  등록되는 `@RestControllerAdvice`인 `RateLimitExceededExceptionHandler`가 이 예외를 설정된
  `RateLimitResponseWriter`로 **429**와 `Retry-After`, `isServiceUnavailable()`이 true면 **503**으로
  응답합니다(예전에는 HTTP 500이 되었습니다). 이 advice의 순서는
  `RateLimitExceededExceptionHandler.ORDER`(`Ordered.HIGHEST_PRECEDENCE + 1000`)이므로, 직접 만든
  advice에 순서 없이 둔 catch-all `@ExceptionHandler(Exception.class)`가 거부를 500으로 바꾸지 않습니다.
  자체 형식을 쓰려면 이보다 먼저 오는 advice(`@Order(RateLimitExceededExceptionHandler.ORDER - 1)`,
  순서 없는 advice는 이 예외를 받지 못합니다)에서 처리하거나, `RateLimitExceededExceptionHandler` 빈을
  정의하거나, 예외를 잡으세요.
- **FluxGate의 MongoDB 클라이언트는 `MongoClient` 빈이 아닙니다 (40).** `FluxgateMongoClientHolder`에
  들어 있고 자동 구성은 Spring Boot의 `MongoAutoConfiguration` 뒤에 실행됩니다. 예전에는 Spring Data
  MongoDB가 애플리케이션 데이터를 FluxGate 클러스터에 쓰거나, `MongoClient` 주입이 모호해져 Boot 3
  컨텍스트가 실패할 수 있었습니다. FluxGate의 클라이언트를 주입했다면 `FluxgateMongoClientHolder`나
  `fluxgateMongoDatabase`를 주입하세요. 이름을 `fluxgateMongoClient`로 지은 `MongoClient` 빈은 계속
  FluxGate가 사용하며 닫지 않습니다.
- **중복 규칙 id와 `AccessControl` (32)** — 16.3절 참고.
- **전달 헤더 (동작 변경).** `trust-client-ip-header=true`이면 모든 헤더 줄을 읽고 홉을 오른쪽부터
  따라갑니다. `ip:port`, `[v6]`, `[v6]:port`는 주소로 줄이며, IP 리터럴이 아닌 홉은 사용하지 않고
  원격 주소로 폴백합니다.
- **대기.** 필터와 Aspect가 하나의 대기 허가(`Semaphore` 빈이 아닌 `FluxgateWaitPermits`)를 공유하므로
  `max-concurrent-waits`가 둘을 함께 제한합니다. `wait-for-refill.enabled=false`를 명시하면 `@RateLimit(waitForRefill = true)`의 대기도
  멈춥니다.

### 16.7 Redis 연결 실패는 단계(phase)를 갖습니다 (Breaking 33)

`org.fluxgate.core.exception.RedisConnectionException`에는 `Phase`가 있고, 재시도 정책은 첫 번째만
재시도합니다:

| Phase | 시점 | 재시도 |
|-------|------|--------|
| `CONNECT` | 명령을 보내기 전, standalone 또는 클러스터 연결을 맺는 중 | 예 (`fluxgate.resilience.retry.*`) |
| `COMMAND` | `EVALSHA` / `EVAL` 실행 중(소비, 확인, 환불)이나 리셋이 키를 스캔·UNLINK하는 중의 Lettuce 실패 | 아니오 — Redis가 이미 명령을 실행했을 수 있고, 소비를 재시도하면 한 요청이 두 번 차감될 수 있음 |
| `UNKNOWN` | Phase 없는 생성자 | 아니오 |

타임아웃은 `FluxgateTimeoutException`으로 보고되며 `retry.retry-on-timeout`이 제어합니다. 실제로
Redis 장애는 `CONNECT`에 해당해 재시도되고, 요청 도중 끊긴 연결은 한 번만 시도한 뒤
`failure-behavior=DENY`에서 503이 됩니다(16.2절). 커스텀 저장소나 래퍼에서
`RedisConnectionException`을 던지면서 재시도를 원한다면 `Phase.CONNECT`를 받는 생성자를 쓰세요. 리셋
실패는 예전에는 Lettuce 예외가 그대로 노출되었지만, 이제 `RedisConnectionException(COMMAND)` 또는
`FluxgateTimeoutException`입니다.

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
- [ ] 로드밸런서와 Kubernetes의 `/actuator/health`, `/actuator/health/readiness` 프로브가 `DEGRADED`를 의도대로 처리하는지 확인. 이제 이 경로에서도 집계되며 기본 503을 응답합니다 (13절)
- [ ] CI를 `./mvnw test`에서 `./mvnw verify`로 전환
- [ ] 11절의 예외·제거 메서드 변경에 맞춰 재컴파일
- [ ] 신뢰할 수 있는 프록시가 신원 헤더를 제공한다면 `identity.source=HEADERS` 또는 `PRINCIPAL_THEN_HEADERS` 설정 (15절)
- [ ] Pub/Sub 리로드를 쓴다면 데이터·컨트롤 플레인에 같은 `fluxgate.reload.pubsub.secret` / `fluxgate.control.secret` 설정 (15절)
- [ ] `ddl-auto=validate`로 기동하기 전에 MongoDB 고유 인덱스 `{ruleSetId: 1, id: 1}` 생성 (16.1절)
- [ ] 클라이언트·게이트웨이·경보에서 503을 "Rate Limit 사용 불가"로 취급. 429는 이제 "한도 초과"만 뜻함 (16.2절)
- [ ] 키 형태에 의존하는 도구와 `allowed-keys` / `denied-keys` 항목 수정 (16.3절)
- [ ] Pub/Sub 리로드는 데이터 플레인을 컨트롤 플레인보다 먼저 업그레이드하고 `max-message-age` 결정 (16.4절)
- [ ] 스테이징에서 한 번 기동해 이제 검증에 실패하는 설정 찾기 (16.5절)
- [ ] 직접 만든 필터·Aspect, 서비스의 `@RateLimit`, `MongoClient` 주입 점검 (16.6절)
- [ ] `RedisConnectionException`을 던지며 재시도를 기대하는 사용자 코드는 `Phase.CONNECT` 전달 (16.7절)

---

## 관련 문서

- [CHANGELOG](../../../CHANGELOG.md) - Breaking 절이 포함된 전체 목록
- [보안 정책](../../../SECURITY.md) - 보안 기본값과 신원 헤더 주의사항
- [Key Resolver](../customization/key-resolver.ko.md) - 키 형태 변경이 쿼터를 초기화하는 이유
- [YAML 룰 세트 가이드](../guides/yaml-rule-sets.ko.md) - `fluxgate.ratelimit.rule-sets` 스키마
- [문서 색인](../../README.ko.md)
