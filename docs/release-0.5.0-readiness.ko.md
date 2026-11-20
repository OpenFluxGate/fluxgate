# 0.5.0 출시 준비 상태

[English](release-0.5.0-readiness.md)

0.5.0은 소스 준비 단계입니다. Spring Boot 2·3 지원, Redis `KEYS`를 `SCAN`으로
교체하는 변경, 별도로 출시하는 Studio UI 수정이 포함됩니다. 두 스타터 소스,
Boot 2 Java 11 샘플, Maven 리액터 항목, Java 11 / Boot 2 CI를 `2423129`에서 복원했습니다.

## Spring 의존성 안내

Dependency Review는 두 스타터의 POM에서 MVC 항목 7건을 보고했습니다. 두 스타터 모두
요청 제한 예외 매핑을 MockMvc로 검증하기 위해 `spring-webmvc`를 Maven `test` 스코프로
선언합니다. 스타터만 추가하면 이 MVC 의존성은 애플리케이션 런타임 클래스패스에
포함되지 않습니다.

| 스타터 | 테스트 의존성 | GitHub 권고 | Spring 공지 |
| --- | --- | --- | --- |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-3chg-m5w7-qfv5](https://github.com/advisories/GHSA-3chg-m5w7-qfv5) | [CVE-2026-41845](https://spring.io/security/cve-2026-41845/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-cx7f-g6mp-7hqm](https://github.com/advisories/GHSA-cx7f-g6mp-7hqm) | [CVE-2024-38816](https://spring.io/security/cve-2024-38816/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-g5vr-rgqm-vf78](https://github.com/advisories/GHSA-g5vr-rgqm-vf78) | [CVE-2024-38819](https://spring.io/security/cve-2024-38819/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) | [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) |
| Boot 2 | `org.springframework:spring-webmvc:5.3.39` | [GHSA-x23c-287f-qqv5](https://github.com/advisories/GHSA-x23c-287f-qqv5) | [CVE-2026-41842](https://spring.io/security/cve-2026-41842/) |
| Boot 3 | `org.springframework:spring-webmvc:6.2.19` | [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) | [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) |
| Boot 3 | `org.springframework:spring-webmvc:6.2.19` | [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) | [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) |

GHSA-pc63-qcmh-9cmg는 두 Spring Framework 계열에 모두 표시되므로 권고 6개에 해당하는
항목이 7건입니다. [릴리스 담당자](../MAINTAINERS.md)가 관리하는 테스트 7건의 CI 예외는
**2026-12-19 00:00 UTC**에 만료됩니다. PR 검사를 실행할 때마다 의존성 변경을 확인합니다.
만료일 전에 PR이 통과했더라도 릴리스 워크플로는 게시 직전에 두 스타터의 실제 Maven
의존성 모델(effective POM)을 다시 확인합니다. 의존성 버전이나 스코프, 스타터의 사용 방식,
권고의 적용 조건 또는 패치 제공 여부가 바뀌면 만료 전에도 재검토합니다. 스타터를 쓰는
애플리케이션은 자체 런타임 의존성을 별도로 확인해야 합니다.

Boot 2 스타터는 서블릿 필터 연동을 위해 선택 의존성
`org.springframework:spring-web:5.3.39`를 별도로 선언합니다. 이 선언으로 인해 Spring Web이
사용자 애플리케이션에 전이 의존성으로 추가되지는 않습니다. Dependency Review는 이를
런타임 항목으로 분류하고
[GHSA-4wrc-f8pq-fpqp (CVE-2016-1000027)](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp)를
보고합니다. 별도 예외의 근거는 스타터가 Spring Web을 필터 연동에 사용하며 HTTP Invoker를
사용하지 않는다는 점입니다. Spring Web을 직접 가져오는 애플리케이션은 HTTP Invoker와
Java 역직렬화 사용 여부를 별도로 확인해야 합니다. Spring Framework 6에서는 영향을 받는
HTTP Invoker 구성 요소가 제거됐습니다.

### 출시 책임자 수용 기록 (2026-11-20)

[릴리스 담당자](../MAINTAINERS.md)가 2026-11-20에 위 8건을 0.5.0 출시 조건으로 수용했습니다.

| 대상 | 건수 | 수용 근거 | 재검토 기한 |
| --- | --- | --- | --- |
| Boot 2 테스트 의존성 `spring-webmvc:5.3.39` | 5 | MockMvc 테스트 전용. 스타터 사용자에게 전이되지 않음 | 2026-12-19 00:00 UTC (CI 예외 만료) |
| Boot 3 테스트 의존성 `spring-webmvc:6.2.19` | 2 | MockMvc 테스트 전용. 스타터 사용자에게 전이되지 않음 | 2026-12-19 00:00 UTC (CI 예외 만료) |
| Boot 2 선택 의존성 `spring-web:5.3.39` (GHSA-4wrc-f8pq-fpqp) | 1 | 필터 연동용 선택 의존성. HTTP Invoker 미사용 | 2026-12-19 (코드상 만료 없음) |

수용 범위는 다음 검사로 고정합니다.

- PR의 `Enforce scoped advisory policy` 검사는 다음 경우에 실패합니다.
  - `check_dependency_advisories.py`의 예외 7건이나 만료일이 바뀐 경우
  - 두 스타터 POM에서 `spring-webmvc`가 테스트 스코프를 벗어나거나 버전이 예외와 달라진 경우
  - 다른 게시 모듈이 `spring-webmvc`를 선언한 경우
- Java 21 CI는 두 스타터의 런타임 의존성 트리(선택 의존성 포함)에 `spring-webmvc`가 직접 또는
  전이로 들어오면 실패합니다.
- 릴리스 워크플로는 게시 직전에 effective POM의 테스트 스코프와 만료일을 다시 확인합니다.

### 애플리케이션 런타임 확인

배포 빌드에 쓰는 프로필과 의존성 설정으로 애플리케이션 모듈에서 빌드 도구에 맞는
명령을 실행합니다.

```bash
./mvnw dependency:tree -Dscope=runtime '-Dincludes=org.springframework:spring-web,org.springframework:spring-webmvc'
```

```bash
./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.springframework:spring-web
./gradlew dependencyInsight --configuration runtimeClasspath --dependency org.springframework:spring-webmvc
```

실제로 선택된 버전, 해당 의존성이 들어오는 경로, 배포 산출물에 패키징되거나 배포
환경에서 제공되는 버전을 확인합니다. 웹 애플리케이션은 `spring-boot-starter-web`이나
다른 의존성에서 MVC를 가져올 수 있습니다. 별도로 포함된 Spring WebFlux 의존성까지
확인하고, 실제 버전과 활성화된 기능을 위 Spring 공지와 대조합니다. Boot 2에서는
애플리케이션과 그 의존성이 신뢰할 수 없는 입력을 받는 HTTP Invoker를 활성화하는지도
확인합니다.

표의 MVC 항목에 해당하는 같은 Framework 계열의 수정은 Boot 2에서 5.3.50까지, Boot 3에서
6.2.20까지 엔터프라이즈 지원으로 제공됩니다. 엔터프라이즈 패치를 쓰는 경우 지원받는
Boot BOM에 Spring Framework 모듈 버전을 맞춘 뒤 애플리케이션을 다시 검증합니다.
표의 최근 권고에 대응하는 공개 수정 버전은 Spring Framework 7.0.9이며, 호환되는 Spring
Boot로 이전하는 경로에서 사용할 수 있습니다. 이 경우 대상 Boot와 Java 버전에서
애플리케이션을 검증합니다. 권고별 수정 버전과 적용 조건은 표의 Spring 공지를 참조하세요.

## 남은 출시 검증

main 브랜치는 Java 11 / Boot 2, Java 17 / Boot 3, Java 21 / 전체 모듈 및 Dependency
Review 통과를 요구합니다. 릴리스 후보 커밋에서 이 네 검사 결과와 CodeQL 지적사항을
확인합니다. 릴리스 워크플로는 게시 전에 스타터 예외 만료일도 확인합니다.

게시 대상 Maven 산출물은 부모 POM, 코어, Redis, Mongo, 두 스타터, control-support,
Envoy 총 8개입니다. 샘플, testkit, 벤치마크는 게시 대상에서 제외됩니다. 함께 출시하기
전에 Studio UI의 준비 상태는 해당 저장소에서 확인합니다.
