# Key Resolver

`KeyResolver`는 `RequestContext`와 규칙을 받아 **키 값**을 만듭니다. 키 값은 "누가" 제한되는지를
식별하는, 버킷 키의 한 조각입니다.

[< 문서 색인으로](../../README.ko.md) | [English](../../en/customization/key-resolver.md)

---

## 인터페이스

```java
package org.fluxgate.core.key;

public interface KeyResolver {
  RateLimitKey resolve(RequestContext context, RateLimitRule rule);
}
```

인자 순서에 주의하세요. **컨텍스트가 먼저, 규칙이 나중**입니다.

리졸버는 버킷 키 전체가 아니라 키 값 하나를 반환합니다. 룰셋 ID·규칙 ID·대역 라벨은 Rate Limiter가
붙입니다:

```
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
                 └ ruleSetId ┘└ ruleId ┘└─ 키 값 ──────┘  └ 대역 라벨 ┘
                └────────── 해시 태그 (클러스터 슬롯 고정) ──────────┘
```

리졸버는 Limiter가 아니라 **룰셋**에 속합니다. `RateLimitRuleSet.Builder.keyResolver(...)`는 필수이며,
`RedisRateLimiter` / `Bucket4jRateLimiter`가 규칙마다 `ruleSet.getKeyResolver()`를 호출합니다.
따라서 한 애플리케이션 안에서도 룰셋마다 다른 키 해석을 쓸 수 있습니다.

## 기본 구현 `LimitScopeKeyResolver`

`org.fluxgate.core.key.LimitScopeKeyResolver`는 규칙의 `LimitScope`로 해석합니다:

| LimitScope | 키 출처 | 해석된 키 값 |
|------------|---------|-------------|
| `GLOBAL` | 상수 | `global` |
| `PER_IP` | `context.getClientIp()` | `ip:192.168.1.100` |
| `PER_USER` | `context.getUserId()` | `user:user-123` |
| `PER_API_KEY` | `context.getApiKey()` | `key:abc123` |
| `CUSTOM` | `context.getAttributes().get(rule.getKeyStrategyId())` | `custom:<value>` |
| _(스코프가 null)_ | `PER_IP`로 폴백 | `ip:…` |

### 스코프 접두사가 필요한 이유

접두사가 없으면 `userId`가 `10.0.0.5`인 사용자와 실제 클라이언트 IP가 `10.0.0.5`인 요청이 같은 버킷을
공유합니다. 인증된 사용자와 익명 호출자가 서로의 쿼터를 소모하게 되는 것입니다. 접두사는 이
네임스페이스를 분리합니다.

또한 접두사는 **값의 실제 출처**를 나타냅니다. `PER_USER` 규칙이 클라이언트 IP로 폴백하면 키는
`user:…`가 아니라 `ip:…`입니다. 의도된 동작입니다 — 키는 실제로 무엇을 측정했는지를 말해야 합니다.

### 값이 없을 때

```yaml
fluxgate:
  ratelimit:
    missing-key-behavior: FALLBACK_TO_IP   # 또는 REJECT
```

| 동작 | 스코프가 요구하는 값이 없을 때 |
|------|------------------------------|
| `FALLBACK_TO_IP` (기본) | 클라이언트 IP로 폴백, 키에 `ip:` 접두사. IP도 없으면 `ip:unknown` |
| `REJECT` | `MissingRateLimitKeyException`을 던집니다. Limiter가 잡아서 대기 시간 0의 거부 결과로 변환합니다 |

`FALLBACK_TO_IP`에는 분명히 말해둘 결과가 있습니다. 규칙의 **제한값은 그대로 적용**되므로 익명
호출자가 인증 티어에 설정된 쿼터를 물려받습니다. `PER_USER`가 로그인 사용자에게 시간당 10,000회를
허용한다면, 미인증 호출자도 IP당 시간당 10,000회를 받습니다. 그것이 허용되지 않는다면 `REJECT`를 쓰거나
익명 트래픽용 룰셋을 따로 두세요.

직접 생성:

```java
KeyResolver resolver = new LimitScopeKeyResolver();                          // FALLBACK_TO_IP
KeyResolver strict   = new LimitScopeKeyResolver(MissingKeyBehavior.REJECT);
```

Spring Boot 애플리케이션에서는 스타터가 `fluxgate.ratelimit.missing-key-behavior`로 이 빈을
만들어 주므로 직접 만들 필요가 없습니다.

### 로깅

해석된 키 값은 **DEBUG**에서만, 앞 4자 + `***`로 마스킹되어 남습니다. 사용자 ID나 API 키가 평문으로
로그에 들어가지 않습니다. 폴백 메시지도 DEBUG입니다 — 예전에는 WARN이어서 핫 패스에서 요청마다
로그 한 줄이 나왔습니다.

## 키 값 새니타이즈

`org.fluxgate.core.key.KeyValueSanitizer`가 해석된 모든 값에 적용되고, `RateLimitKey` 생성자에서
한 번 더 적용됩니다. 그래서 커스텀 리졸버도 같은 보호를 받습니다.

| 규칙 | 효과 |
|------|------|
| 문자셋 | `[A-Za-z0-9._:@-]` 외의 문자는 `_`로 치환 |
| 길이 | 256자 초과 → SHA-256 16진 해시로 대체 (64자, JVM 간 안정적) |
| 멱등성 | 이미 새니타이즈된 값을 다시 넣으면 그대로 반환 |

`:`는 **허용됩니다**. 복합 `CUSTOM` 키가 이를 구분자로 쓰기 때문입니다. 새니타이즈가 막는 것은 저장소
메타문자입니다. `{`·`}`는 클러스터 해시 태그를 탈출할 수 없고, `*`·`?`·`[`는 `SCAN` 패턴에 글롭을
밀어넣을 수 없습니다. `:`가 살아남으므로, **규칙 ID와 룰셋 ID에는 `:`를 쓰지 마세요**. 그것들은 여러분이
정하는 식별자이고, `:`를 쓰지 않으면 버킷 키를 모호함 없이 파싱할 수 있습니다.

## 직접 구현하기

인터페이스를 구현하고 등록하면 됩니다. Spring Boot에서는 `KeyResolver` 빈으로, 또는 룰셋에 직접
지정합니다:

```java
public class TenantAwareKeyResolver implements KeyResolver {

  private final KeyResolver delegate = new LimitScopeKeyResolver();

  @Override
  public RateLimitKey resolve(RequestContext context, RateLimitRule rule) {
    Object tenant = context.getAttributes().get("tenantId");
    if (tenant == null) {
      return delegate.resolve(context, rule);
    }
    // 모든 구성 요소에 접두사를 붙이고, 새니타이즈는 RateLimitKey에 맡깁니다.
    return RateLimitKey.of("tenant:" + tenant + ":" + delegate.resolve(context, rule).value());
  }
}
```

```java
@Bean
public KeyResolver keyResolver() {
    return new TenantAwareKeyResolver();
}
```

```java
// 또는 직접 만든 룰셋에 지정
RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("api-limits")
    .rules(rules)
    .keyResolver(new TenantAwareKeyResolver())
    .build();
```

커스텀 리졸버의 세 가지 규칙:

1. **절대 `null`을 반환하지 마세요.** Limiter는 키를 요구하며, 계약을 깨면 요청이 시끄럽게 실패합니다.
2. **조립하는 모든 구성 요소에 접두사를 붙이세요.** 그래야 `ip:10.0.0.1:user:u-1`이 콜론이 들어간
   사용자 ID와 혼동되지 않습니다.
3. **가볍고 부작용이 없게 유지하세요.** 요청당 규칙마다 핫 패스에서 실행됩니다. I/O 금지, 블로킹 금지,
   원시 값 로깅 금지.

## 키 형태를 바꾸면 쿼터가 초기화됩니다

키 값은 버킷 키의 일부이므로, 해석 방식을 바꾸면 모든 버킷이 이동합니다. 호출자들은 한 번 가득 찬
쿼터를 받고, 옛 키는 자신의 TTL로 소멸합니다. 0.3.x → 0.4 업그레이드에서 스코프 접두사와 새니타이즈가
도입될 때 바로 이 일이 일어났습니다.
[0.4 마이그레이션](../operations/migration-0.4.ko.md)을 참고하세요.

---

## 관련 문서

- [Request Context](request-context.ko.md) - 리졸버가 읽는 값
- [Engine Layer](../architecture/deep-dive/engine-layer.ko.md)
- [Redis Rate Limiter](../../../fluxgate-redis-ratelimiter/README.md) - 버킷 키 포맷 전문
- [보안 정책](../../../SECURITY.md) - 키 새니타이즈와 테넌트 격리
- [문서 색인](../../README.ko.md)
