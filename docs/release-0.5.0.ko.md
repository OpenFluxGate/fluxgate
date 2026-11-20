# FluxGate Core 0.5.0

[English](release-0.5.0.md)

## 주요 변경

FluxGate 0.5.0에서는 Spring Boot 2.7과 Spring Boot 3 애플리케이션, Envoy 뒤의 서비스가 같은 요청 제한 엔진을 사용할 수 있습니다. 애플리케이션에서 요청 제한기와 규칙 공급자를 제공하면 스타터가 기본 핸들러를 연결합니다. Envoy 외부 인가 서비스를 쓰면 Java SDK를 직접 넣지 않은 백엔드에도 같은 정책을 적용할 수 있습니다.

## Maven 모듈

그룹은 `io.github.openfluxgate`, 버전은 `0.5.0`입니다. 애플리케이션에서 사용하는 Spring Boot와 Java 버전에 맞춰 스타터를 선택하세요.

| 용도 | 아티팩트 | 형식 |
| --- | --- | --- |
| 부모 프로젝트 | `fluxgate` | POM |
| 요청 제한 엔진 | `fluxgate-core` | JAR |
| 규칙 저장소 | `fluxgate-mongo-adapter` | JAR |
| 분산 카운터 | `fluxgate-redis-ratelimiter` | JAR |
| Spring Boot 2.7, Java 11 이상 | `fluxgate-spring-boot2-starter` | JAR |
| Spring Boot 3, Java 17 이상 | `fluxgate-spring-boot3-starter` | JAR |
| 규칙 변경 알림 | `fluxgate-control-support` | JAR |
| Envoy 인가 서비스 | `fluxgate-envoy-extauth` | JAR |

Spring Boot 3 애플리케이션의 Maven 의존성 예시입니다.

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

## 0.3.x에서 올릴 때

직전 전체 모듈 릴리스는 0.3.7이고, 0.3.8은 Redis 모듈만 갱신했습니다. Redis 버전을 별도로 고정한 경우를 포함해 애플리케이션의 FluxGate 모듈을 0.5.0으로 맞추세요.

- **여러 인스턴스가 한도를 공유하면 점검 시간을 확보하세요.** Redis 키 형식과 토큰 버킷의 시간 단위가 바뀝니다. 구버전과 신버전이 동시에 같은 한도를 사용하면 각자 다른 버킷에서 요청을 허용합니다. 모든 쓰기를 차단하고 진행 중인 요청을 비운 뒤, 백업 복원과 기존 사용량 처리 방식을 확인하고 트래픽을 여세요. 롤백에도 쓰기 차단과 복구 계획이 필요합니다. [전환·롤백 절차](operations/shared-redis-legacy-migration.ko.md)
- **직접 구현한 Redis 공급자를 확인하세요.** 기본 공급자는 `SCAN`으로 키를 나누어 조회합니다. 카운터를 초기화하는 사용자 정의 공급자는 `scanKeys`를 구현해야 합니다. 목록을 반환하는 API는 일치하는 키를 메모리에 보관합니다. [호환성 변경](../CHANGELOG.md)
- **규칙 변경 시 카운터 처리 방식을 선택하세요.** 기본값은 `LEGACY_RESET`입니다. 실제 게시 정책을 읽는 호환 공급자에서 `PUBLISHED_EPOCH`를 선택하면 일반 정책 변경은 카운터를 유지하고 명시적으로 초기화할 때 새 epoch를 사용합니다. [설정과 전제 조건](operations/shared-redis-published-epochs.md)
- **애플리케이션의 Spring 의존성을 확인하세요.** 두 스타터 모두 테스트 범위에서 Spring MVC를 사용합니다. Boot 2 스타터는 서블릿 필터 연동을 위해 Spring Web 5.3.39도 선택적 의존성으로 선언합니다. 실제 배포에 포함되는 버전을 확인하세요. 테스트 범위 권고 7건의 예외는 2026-12-19 00:00 UTC에 만료됩니다. [Spring 의존성 안내와 확인 방법](release-0.5.0-readiness.ko.md)

## 검증

Core 병합 커밋은 Java 11·17·21 CI를 통과했습니다. 별도 게시 워크플로는 서명 태그가 가리키는 커밋을 확인하고 Redis Cluster 통합 테스트와 권고 예외 만료 검사를 수행한 뒤 Maven 아티팩트에 서명합니다. [병합 커밋 CI](https://github.com/OpenFluxGate/fluxgate/actions/runs/44647301397) · [릴리스 절차](../RELEASING.md)
