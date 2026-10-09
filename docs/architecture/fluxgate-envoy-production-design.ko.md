# FluxGate × Envoy Gateway: 운영 통합 설계안

2026-10-10 현재 `feature/envoy-gateway-authz`의 코드와 kind 실측을 기준으로 한다. 이 문서는 구현 완료 선언이 아닌 다음 개발의 계약이다. [브라우저 다이어그램](fluxgate-envoy-target.html)은 정상 요청 경로와 판정·저장소 경로를 분리해 보여준다.

## 목표와 범위

Java·Go·Rust 서비스가 FluxGate SDK를 설치하지 않아도 **Gateway를 통과하는 HTTP 요청**에 기존 FluxGate 규칙을 적용한다. FluxGate의 규칙 모델·ACL·키 해석·다중 규칙·Redis limiter·Mongo provider·지표·reload는 Java 판정 서비스에서 재사용한다. 서비스 간 내부 호출과 직접 Pod/Service 접근은 Gateway를 지나지 않으므로 별도의 네트워크 정책 또는 호출 측 적용이 필요하다.

```
사용자 ──> Envoy Gateway ──(extAuth Check)──> Java FluxGate
                 │                  ├──> MongoDB: 규칙 조회·판정 이벤트
                 │                  └──> Redis: 제한 카운터·변경 알림
                 └──(허용된 요청만)──> 실제 서비스 Pod
```

Envoy는 MongoDB·Redis의 클라이언트가 아니다. Java 판정 서비스는 요청을 프록시하지 않는다. `200/OK` 판정 뒤 Envoy가 원래 요청을 서비스 Pod로 전달한다. 거부·판정 장애 시 원 서비스는 호출하지 않는다. 제한 카운터는 **판정 허용 시점**에 소비되므로 원 서비스가 나중에 5xx를 반환하거나 연결이 끊겨도 자동 환급되지 않는다. 여러 규칙이 매칭될 때는 현재 엔진의 fail-fast 순서가 적용되며, 앞선 규칙의 소비 뒤 뒤쪽 규칙이 거부할 수 있다. 이를 전체 규칙 원자성으로 설명하지 않는다.

## 현재 확인된 상태

| 항목 | 2026-10-10 근거 | 남은 일 |
| --- | --- | --- |
| Envoy → Java → 실제 Pod | kind에서 200·200·429 | 실제 앱과 HTTPS 경로 검증 |
| Mongo 규칙 → 기존 엔진 → Redis | Mongo 규칙 2개, 이벤트 3개, Redis 키 2개 확인 | Studio 변경 반영과 운영 데이터 계약 |
| ACL·거부·Redis 장애 | 파일럿에서 403, 503 확인 | Mongo 장애·중복 요청·복수 Pod 실험 |
| 요청 문맥 | 현재 controller는 path·method·Envoy peer IP만 전달 | 신뢰된 client IP/identity와 헤더 allowlist |
| rule-set 선택 | 프로세스 전체에서 고정 ID 하나 | 노출할 route와 rule-set의 서버 소유 바인딩 |
| 규칙 변경 | reload 비활성화 | Studio 알림 + cache TTL/polling 검증 |
| 제한 헤더 | 429의 Retry-After만 전달 | 허용 응답 헤더 전파 방식 결정·검증 |

기존 HTML 설계 문서의 ‘단일 YAML 규칙’과 ‘중복 규칙 503’ 설명은 이전 파일럿 시점의 기록이다. 현재 Mongo·복수 규칙 실측을 설명하는 문서는 이 문서와 `deploy/envoy-gateway-local/README.md`다.

## 권장 통합 방식

**현 단계에서는 Envoy Gateway `SecurityPolicy.extAuth.http`를 유지한다.** 기존 kind 경로가 검증되어 있고 어댑터를 작게 유지할 수 있다. 단, HTTP extAuth의 성공 응답 헤더가 클라이언트에게 자동 전파되지 않는 문제를 운영 요구로 확정하면 **gRPC `Check` 서비스로 전환**한다. Envoy의 gRPC `OkHttpResponse.response_headers_to_add`는 허용 응답 헤더를 표현할 수 있다. 불안정하고 광범위한 xDS 변경 권한을 요구하는 `EnvoyPatchPolicy`를 기본 경로로 채택하지 않는다. HTTP→gRPC 전환 시에도 판정·규칙 엔진은 동일하고 전송 어댑터만 교체한다.

`BackendTrafficPolicy`의 자체 rate limit은 FluxGate 규칙과 Redis 키 의미를 그대로 실행하지 않으므로 이 목표의 대체 구현이 아니다. 자체 Gateway 기능을 선호하는 설치자는 FluxGate 판정 서비스를 선택하지 않아도 된다.

## 판정 계약

1. Envoy는 보호할 `HTTPRoute`에 `SecurityPolicy`를 부착한다. 모든 의도된 route의 정책 적용 상태를 배포 검사로 확인한다. 더 구체적인 route 정책이 부모 정책을 덮을 수 있으므로 Gateway 수준 정책만 보고 커버리지를 추정하지 않는다.
2. 판정 요청은 원 method·path·host 및 **명시된 헤더만** 전달한다. 본문은 사용하지 않는다. URL rewrite·인코딩·쿼리 파라미터의 의미는 Envoy에서 authz와 upstream이 보는 값이 같은지 계약 테스트로 고정한다. 매칭에 필요한 헤더를 `headersToExtAuth`에 열거한다.
3. 서버가 route→rule-set 바인딩과 permits(기본 1)를 정한다. HTTP 방식에서는 정책별 auth path prefix의 binding ID를 사용할 수 있다. 그 ID는 서버 설정의 allowlist에서만 해석하며 원래 path를 복원한다. 클라이언트 헤더의 rule-set ID·permits는 무시한다. 바인딩 부재·중복·미존재 rule set은 503/배포 실패로 처리한다. 여러 규칙 매칭은 `RateLimitEngine.check` 한 번으로 기존 의미를 따른다.
4. 컨텍스트는 기존 `RequestContextFactory`를 사용한다. 기본값에서 `PER_USER`·`PER_API_KEY`에 필요한 신원이 없으면 `MISSING_KEY`로 거부한다. 사용자 ID·API key·tenant를 클라이언트가 보낸 헤더만으로 신뢰하지 않는다. 선택지는 (a) Gateway의 검증된 JWT/인증 결과, (b) authz 내부 검증, (c) 신뢰된 프록시가 기존 헤더를 제거하고 재설정하는 방식이다. 공급원 선택 전에는 해당 scope의 출시를 막는다. Credential 원문은 Mongo 이벤트·로그에 저장하지 않는다.
5. 실제 client IP는 Envoy와 앞단 로드밸런서의 신뢰 프록시 체인을 설정하고 테스트해 결정한다. Java가 보는 socket peer는 Envoy Pod일 수 있다. 임의 `X-Forwarded-For`를 그대로 키로 쓰면 IP별 제한을 우회할 수 있다.
6. 정상 `200/OK`는 Envoy만 소비하고 백엔드는 원 요청을 받는다. ACL 거부와 신원 누락은 403, quota 초과는 429 + `Retry-After`, 규칙 조회·Redis·판정 서비스 실패는 5xx로 닫는다. 정확한 오류 코드는 Envoy의 경로별 동작을 실측해 확정한다. 기존 `RateLimitHeaderWriter`의 성공 헤더까지 필수라면 gRPC 전환을 완료해야 한다.
7. 기존 `WAIT_FOR_REFILL`은 요청을 붙잡으므로 기본 비활성화한다. 활성화 시 대기 동시성 상한, 최대 대기시간, extAuth timeout, 클라이언트 timeout을 함께 설정하고, timeout 직전 재소비·중복 소비를 검사한다.

## 저장소·변경 반영

Studio와 판정 서비스는 같은 `fluxgate.rate_limit_rules` 모델을 사용한다. 변경은 Studio의 저장 완료 이후에만 `fluxgate:rule-reload` 알림을 발행한다. 판정 Pod들은 Pub/Sub 메시지에서 rule-set ID를 검증하고 해당 cache를 무효화한다. Redis Pub/Sub은 영속 전달이 아니므로 제한된 TTL/주기적 polling을 보조 경로로 둔다. 버전이 낮은 이벤트는 무시하고 재연결 뒤 전체 재조회한다. **토큰 버킷 삭제 여부**는 규칙 변경 시 quota를 리셋하는 제품 의미이므로 기존 reload 구현을 점검하고 별도 결정한다. 알림 비밀값은 Studio와 판정 서비스가 일치해야 하며 배포용 Secret으로 제공한다.

Mongo 장애 시 무조건 마지막 cache를 신뢰하는 것은 보안 결정을 바꾼다. 우선 짧은 유효 TTL 내의 마지막 검증 규칙만 사용할지, 즉시 503으로 닫을지 정책으로 명시한다. 기본은 503이며, 운영 관찰 뒤 예외를 제한적으로 도입한다. 샘플의 Mongo `emptyDir`와 무인증 Redis는 로컬 데모 전용이다.

## Kubernetes 배치와 신뢰 경계

- Envoy Proxy → 판정 서비스만 extAuth 포트 접근을 허용하고, 일반 워크로드와 외부 클라이언트의 접근은 NetworkPolicy와 서비스 인증으로 제한한다. kind 기본 CNI만으로는 이 보장을 주장하지 않는다.
- 판정 서비스 → MongoDB·Redis의 최소 연결만 허용한다. 운영 자격증명, TLS/mTLS, Secret 회전, namespace 분리를 배포 계약에 포함한다.
- 실제 서비스 Pod는 Envoy 경로 외의 접근을 막지 않으면 Gateway 제한을 우회할 수 있다. 내부 호출을 포함할지 제품 범위로 정의하고 정책을 따로 적용한다.
- 판정 서비스는 무상태로 여러 복제본을 운영한다. readiness는 애플리케이션 프로세스뿐 아니라 필수 규칙 공급원과 정책 유효성을 반영하고, Envoy timeout보다 짧은 Redis/Mongo timeout을 둔다. 장애 시 `failOpen=false`; 반환 코드는 500/503 모두 허용 차단으로 분류한다.

## 구현 순서와 완료 기준

| 단계 | 구현 | 완료 증거 |
| --- | --- | --- |
| 1. 계약 고정 | route 바인딩·응답 상태·신원 공급원 정의 | 잘못된 binding/헤더 위조/미존재 규칙 거부 테스트 |
| 2. 문맥 연결 | `RequestContextFactory` 재사용, IP·header allowlist, 신뢰된 identity | IP·user·API key·header matcher가 Gateway 경유로 독립 작동 |
| 3. 변경 반영 | Mongo cache + Studio Pub/Sub + polling | Studio 변경 전후 여러 authz Pod에서 규칙 전환 확인 |
| 4. 응답·정책 | 헤더 계약, 필요한 경우 gRPC, 선택적 WAIT | 허용/거부 응답 헤더·timeout·중복 소비 테스트 |
| 5. 배포·운영 | Helm/Kustomize 샘플, NetworkPolicy, TLS, metrics, SLO | Pod 직접 접근 차단, 장애 주입, latency 부하, HA 검증 |

최소 회귀 시나리오는 `GET/POST/OPTIONS`, URL rewrite, 0·1·복수 매칭 규칙, ACL deny/bypass, 403/429/5xx, Redis/Mongo 중단, rule reload, 프록시 헤더 위조, 사용자별 독립 버킷, backend 5xx 뒤 카운터 소비다. 실제 운영 트래픽에 붙이기 전 core·Mongo·Redis 관련 이전 리뷰의 최신 상태도 재검증한다.

## 코드량 추정

이미 구현된 파일럿 위에서 작업하는 **손작성 코드/테스트/배포 설정의 변경량**이다. 생성된 protobuf 코드는 제외하며, 상세 API가 정해지면 달라진다.

| 범위 | 대략적인 변경량 | 주된 비용 |
| --- | ---: | --- |
| HTTP extAuth의 문맥·route 바인딩·안전한 거부 | 제품 코드 300–600줄 + 테스트 400–700줄 | 신뢰 경계와 매칭 회귀 |
| Studio reload·cache·다중 Pod 검증 | 제품 코드/설정 150–350줄 + 테스트 250–500줄 | 기존 자동설정 결합과 데이터 정합성 |
| 성공 헤더·완전한 extAuth 계약을 위한 gRPC 전환 | 추가 제품 코드 350–700줄 + 테스트 350–700줄 | proto 매핑, Envoy Gateway 설정, 호환성 |
| 배포·보안·운영 예제와 문서 | YAML/문서 300–700줄 | 환경별 정책과 장애 실험 |

따라서 **HTTP 기반의 안전한 첫 운영 후보는 약 1,400–2,800줄 변경**, 성공 헤더까지 포함한 gRPC 기반 전체 범위는 **약 2,100–4,200줄 변경**으로 본다. 절반 이상은 테스트·배포·문서이며, 기존 제한 알고리즘을 새로 작성하는 규모는 아니다. 개발 시간은 환경·신원 제공 방식에 크게 좌우되므로 줄 수를 일정으로 환산하지 않는다. 헤더·인증 경계를 확정하지 않은 채 ‘모든 FluxGate 기능 지원’이라고 선언하지 않는다.

## 공식 계약 참고

- Envoy Gateway v1.9 [External Authorization](https://gateway.envoyproxy.io/v1.9/tasks/security/ext-auth/)와 [Extension API](https://gateway.envoyproxy.io/v1.9/api/extension_types/): SecurityPolicy와 HTTP path prefix.
- Envoy [ext_authz filter](https://www.envoyproxy.io/docs/envoy/latest/api-v3/extensions/filters/http/ext_authz/v3/ext_authz.proto.html): HTTP 성공 응답 헤더 전달 기본값과 실패 처리.
- Envoy [Authorization service proto](https://www.envoyproxy.io/docs/envoy/latest/api-v3/service/auth/v3/external_auth.proto): gRPC `CheckResponse`, `OkHttpResponse.response_headers_to_add`.
- Envoy Gateway [EnvoyPatchPolicy](https://gateway.envoyproxy.io/v1.9/tasks/extensibility/envoy-patch-policy/): 불안정성과 광범위한 프록시 구성 변경 위험.
