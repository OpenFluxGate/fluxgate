# Request Context

모든 Rate Limit 결정은 `RequestContext`에서 시작합니다. 코어가 요청에 대해 보는 것은 이것뿐이므로,
여기에 무엇이 담기는지가 **어느 버킷이 소모될지**를 결정합니다.

[< 문서 색인으로](../../README.ko.md) | [English](../../en/customization/request-context.md)

---

## 담기는 내용

`org.fluxgate.core.context.RequestContext`는 불변이며 빌더로 만듭니다.

| 필드 | 서블릿 필터가 채우는 출처 | 사용처 |
|------|--------------------------|--------|
| `clientIp` | `remoteAddr`, 또는 전달 헤더를 신뢰할 때 그 값 | `PER_IP` 스코프 |
| `userId` | 인증된 principal. `identity.source`가 헤더를 켠 경우에만 `X-User-Id` 헤더 | `PER_USER` 스코프 |
| `apiKey` | `identity.source`가 헤더를 켠 경우에만 `X-API-Key` 헤더 | `PER_API_KEY` 스코프 |
| `endpoint` | 정규화된 요청 경로 | 메트릭 `endpoint` 태그, 로깅 |
| `method` | HTTP 메서드 | 메트릭 `method` 태그, 로깅 |
| `headers` | `collect-headers=true`일 때만, 허용 목록에 있는 요청 헤더 | 사용자 코드, 메트릭 레코더 |
| `attributes` | 아무것도 없음 — 사용자 영역입니다 | `CUSTOM` 스코프, 사용자 코드 |

`path` 필드와 `ruleSetId` 필드는 **없습니다**. 경로는 `endpoint`에 담기고, 룰셋 ID는
`FluxgateRateLimitHandler.tryConsume(context, ruleSetId)`의 별도 인자입니다.

## 필터가 만드는 방식

`org.fluxgate.spring.filter.RequestContextFactory`가 컨텍스트를 만들고, 필터와 `@RateLimit`
애스펙트가 이를 **공유**합니다. 그래서 두 모드에서 룰셋이 동일하게 동작합니다.

```
HTTP 요청
   │
   ├─ clientIp  ← ClientIpExtractor.extract(request, clientIpHeader, trust, trustedProxies)
   ├─ userId    ← principal 이름 (identity.source=PRINCIPAL, 기본값), opt-in 시 헤더
   ├─ apiKey    ← opt-in(HEADERS, PRINCIPAL_THEN_HEADERS) 시에만 request.getHeader("X-API-Key")
   ├─ endpoint  ← RequestPathResolver (정규화, 컨텍스트 경로 독립)
   ├─ method    ← request.getMethod()
   ├─ headers   ← collect-headers=true일 때 허용 목록 헤더
   │
   └─ RequestContextCustomizer.customize(builder, request)   ← **마지막**에 실행. 모든 것을 덮어씁니다
          │
          └─ build()
```

커스터마이저가 마지막에 실행되는 것은 의도입니다. 커스터마이저가 설정한 값이 팩토리가 추출한 값을
이깁니다.

## `RequestContextCustomizer`

```java
package org.fluxgate.spring.filter;

@FunctionalInterface
public interface RequestContextCustomizer {
  RequestContext.Builder customize(RequestContext.Builder builder, HttpServletRequest request);
}
```

빈 하나만 등록하면 스타터가 자동으로 집어갑니다.

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

### 가장 중요한 용도: 인증된 신원

기본 설정에서 `userId`와 `apiKey`는 **클라이언트가 제어하는** 요청 헤더에서 옵니다. 따라서 호출자가
요청마다 다른 `X-User-Id`를 보내 사용자별 제한을 우회할 수 있고, 다른 사람의 식별자를 보내 그 사람의
쿼터를 소진시킬 수 있습니다.

인증 엣지가 그 헤더들을 재작성하는 게이트웨이 뒤에서는 올바른 동작입니다. 그 외 모든 경우에는
인증된 principal에서 값을 설정하세요:

```java
@Bean
public RequestContextCustomizer authenticatedIdentityCustomizer() {
    return (builder, request) -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {
            builder.userId(auth.getName());   // X-User-Id 헤더는 절대 쓰지 않습니다
            builder.apiKey(null);             // 클라이언트가 보낸 API 키는 버립니다
        }
        return builder;
    };
}
```

동작 조건 두 가지:

- FluxGate 필터가 Spring Security **이후**에 실행되어야 합니다. 그렇지 않으면 principal이 아직
  없습니다. 기본값 `fluxgate.ratelimit.filter-order=1`이 이미 이 조건을 만족합니다 — Spring
  Security 체인의 기본 순서는 `-100`입니다. 의도적으로 음수로 낮출 때만 깨집니다.
- 미인증 요청이 IP 버킷으로 폴백해 인증 티어의 쿼터를 물려받으면 안 된다면
  `fluxgate.ratelimit.missing-key-behavior=REJECT`와 함께 쓰세요.

### 클라이언트 IP 재정의

CDN이 자체 헤더를 쓰거나, 전달 체인이 `trusted-proxies`로 표현할 수 없는 형태일 때:

```java
@Bean
public RequestContextCustomizer cloudflareIpCustomizer() {
    return (builder, request) -> {
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isEmpty()) {
            builder.clientIp(cfIp);
        }
        return builder;
    };
}
```

이 코드는 `CF-Connecting-IP`를 무조건 신뢰하므로, CDN 외에는 애플리케이션에 도달할 수 없을 때만
안전합니다. 가능하면 `fluxgate.ratelimit.trusted-proxies`를 쓰세요.

### `CUSTOM` 스코프용 복합 키

`CUSTOM` 규칙은 규칙의 `keyStrategyId`가 지정한 속성 **하나**를 읽습니다:

```java
@Bean
public RequestContextCustomizer compositeKeyCustomizer() {
    return (builder, request) -> {
        String userId = request.getHeader("X-User-Id");
        String clientIp = request.getRemoteAddr();

        // 각 구성 요소에 접두사를 붙여, 새니타이즈 후에도 모호해지지 않게 합니다.
        String composite = userId != null
            ? "ip:" + clientIp + ":user:" + userId
            : "ip:" + clientIp;

        builder.attribute("ipUser", composite);
        return builder;
    };
}
```

```java
RateLimitRule rule = RateLimitRule.builder("composite-rule")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();
```

멀티 테넌트 시스템에서는 `X-Tenant-Id`를 복사하기 전에 애플리케이션 경계에서 검증하고, 키에 테넌트와
주체를 모두 포함하세요(`tenantId + ":" + userId`). 그래야 한 테넌트가 다른 테넌트의 버킷을 소모하거나
초기화할 수 없습니다.

## 헤더 수집

`RequestContext.getHeaders()`는 옵트인하지 않으면 **비어 있습니다**:

```yaml
fluxgate:
  ratelimit:
    collect-headers: true
    header-allowlist:
      - X-Tenant-Id
      - X-Request-Id
```

매칭은 대소문자를 구분하지 않습니다. 다음 헤더는 허용 목록에 넣어도 **절대** 복사되지 않습니다:

`Authorization`, `Cookie`, `Set-Cookie`, `Proxy-Authorization`, `X-API-Key`

`X-API-Key`는 여전히 `RequestContext.getApiKey()`를 채웁니다. 헤더 맵에만 들어가지 않습니다.

기본값이 꺼짐인 이유는 컨텍스트가 메트릭 레코더로 넘어가 **영속화**될 수 있기 때문입니다 —
`MongoRateLimitMetricsRecorder`는 이를 MongoDB에 씁니다. 허용 목록에 넣는 헤더는 "저장하기로
선택한 데이터"로 취급하세요.

## HTTP 요청이 없는 호출

스케줄러나 메시지 리스너에서 호출된 `@RateLimit` 메서드에는 요청이 없습니다. 이때 애스펙트는
`RequestContextFactory.createForInvocation(endpoint, method)`로 최소 컨텍스트를 만들며,
`endpoint`(보통 `Type.method`)와 `method`(예: `INTERNAL`)만 설정됩니다.

신원 스코프는 해석할 값이 없으므로 `fluxgate.ratelimit.missing-key-behavior`가 결정합니다.
`FALLBACK_TO_IP`는 `ip:unknown` 키를 만들어 **모든 내부 호출이 하나의 버킷을 공유**하게 하고,
`REJECT`는 거부합니다. 내부 호출에는 보통 `GLOBAL` 규칙이 실제로 원하는 것입니다.

## 직접 만들기

테스트나 핸들러 직접 호출에서:

```java
RequestContext context = RequestContext.builder()
    .clientIp("10.0.0.1")
    .userId("user-123")
    .apiKey("key-abc")
    .endpoint("/api/orders")
    .method("POST")
    .attribute("tenantId", "acme")
    .build();
```

`fluxgate-testkit`이 이것을 인메모리 핸들러와 함께 묶어 제공합니다.
[fluxgate-testkit/README.md](../../../fluxgate-testkit/README.md)를 참고하세요.

---

## 관련 문서

- [Key Resolver](key-resolver.ko.md) - 컨텍스트가 버킷 키가 되는 과정
- [Filter Layer](../architecture/deep-dive/filter-layer.ko.md)
- [보안 정책](../../../SECURITY.md) - 신원 헤더 주의사항 전문
- [문서 색인](../../README.ko.md)
