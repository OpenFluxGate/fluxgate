# Envoy에서 기존 FluxGate를 실행하는 서비스

## 목표

Envoy는 HTTP 요청을 검사 서비스에 전달하고, 검사는 기존 FluxGate의 `RateLimitEngine`, `RateLimitRuleSetProvider`, `RedisRateLimiter`, ACL, key resolver, metrics/reload 경로에서 수행한다. 서비스 Pod는 Envoy의 허용 결과 뒤에만 요청을 받는다. 판정 로직이나 규칙 모델을 Envoy 어댑터 안에 복제하지 않는다.

## 기존 파일럿과의 차이

기존 `fluxgate-envoy-extauth`는 이 흐름의 네트워크 연결만 검증했다. YAML의 `gateway-pilot` 전역 규칙 하나를 사용했고, 정확히 한 규칙만 매칭되도록 제한했으며, 요청 컨텍스트에는 path·method·Envoy peer IP만 전달했다. Mongo/Studio 규칙, 복수 규칙, 신뢰된 사용자/API 키 식별, 성공 응답의 제한 헤더, rule reload는 아직 연결되지 않았다. 따라서 파일럿 결과를 전체 FluxGate 통합 완료로 간주하지 않는다.

## 구현 계약

1. 기존 Spring Boot starter의 Mongo provider가 Studio와 같은 `fluxgate.rate_limit_rules`를 읽는다. YAML 규칙은 로컬 파일럿 프로필에만 둔다. Redis 제한기와 resilience 설정은 기존 자동설정을 사용한다.
2. Envoy HTTP extAuth adapter는 서버 설정의 route binding에서 rule-set ID를 선택한다. 클라이언트 헤더가 rule-set ID나 permit 수를 선택할 수 없다.
3. 엔진을 한 번 호출한다. 매칭 규칙이 0개 또는 여러 개인 경우의 의미, ACL 우선순위, 여러 band·scope·matcher, fail-fast 평가와 지표 기록은 기존 엔진/limiter에 맡긴다. 존재하지 않는 rule set 및 backend failure는 차단한다.
4. 컨텍스트는 기존 `RequestContextFactory`를 재사용하되 Envoy가 전달한 원래 path로 보정한다. 헤더 수집은 명시적인 allowlist만 허용하고 credential은 기록하지 않는다. 사용자/API 키 헤더와 client IP 헤더는 Gateway에서 검증·정화한 경우에만 신뢰한다. 신뢰된 공급원이 없으면 필요한 키의 규칙은 `missing-key-behavior=REJECT`로 거부한다.
5. 거부 응답에는 원인을 구분해 ACL 403, quota 429, 저장소/규칙 조회 오류 503을 반환한다. `MISSING_KEY`는 quota로 오인하지 않는다. 기존 `RateLimitHeaderWriter`가 생성하는 응답 헤더를 재사용하고, 허용 응답의 헤더를 Envoy가 사용자에게 전달하는 설정을 검증한다.
6. `WAIT_FOR_REFILL`은 기존 서버 정책과 Envoy 검사 timeout·동시 대기 상한을 함께 적용해야 한다. 검증되지 않은 상태에서는 대기 없이 429로 처리한다고 명시한다.
7. Mongo 규칙 변경의 cache/reload는 기존 provider와 Studio 알림 채널을 사용한다. 변경 전후 규칙 적용을 실제 요청으로 검증한다.

## 검증 기준

- Studio와 동일 컬렉션에 저장된 규칙이 Envoy 요청에 적용되고, 규칙 수정 후 reload 경로를 통해 반영된다.
- 복수 매칭 규칙, 0개 매칭 규칙, ACL, 사용자·API 키 누락, Redis 장애, OPTIONS가 기존 의미대로 처리된다.
- 허용/거부 응답의 제한 헤더와 원래 요청 path·method·허용된 헤더 매칭을 Gateway 경유로 확인한다.
- 직접 Pod 접근, 프록시 및 신원 신뢰 경계, 5xx, latency/HA는 별도 배포 검증 항목으로 남긴다.
