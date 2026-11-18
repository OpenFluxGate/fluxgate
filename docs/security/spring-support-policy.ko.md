# Spring 통합 지원정책과 소비자 보안 확인 목록

[English](spring-support-policy.md)

## 0.5.0 방침과 배포 경계

신규 통합에는 Boot 2보다 Boot 3 경로를 권장합니다. Boot 2는 기존 Java 11 /
javax 애플리케이션의 호환성 경로로 유지합니다. 이 권고는 현재 고정된 Boot 3
버전의 업스트림 보안지원 지속이나 취약점 부재를 의미하지 않습니다.
정확한 버전과 상태는 [호환성 표](../../GOVERNANCE.md#supported-spring-boot-and-java-matrix)를 확인하세요.

호환성은 보안지원 보장이 아닙니다. FluxGate가 업스트림 OSS 지원기간을 연장하거나
상용 패치를 포함하거나 확정된 LTS 계약을 제공하는 것은 아닙니다. 두 통합 경로는
모두 0.5.0 범위에 남으며 Boot 2를 0.5.1로 미루지 않습니다. 기존 보안 발견사항과
필수 검사를 유지합니다. 이 문서는 CVE 수정, 예외 승인 또는 배포 승인이 아닙니다.


## Boot 3 권장 이유와 Boot 2 유지 배경

Boot 3는 Java 17 / Jakarta 플랫폼을 사용하고, Spring 6에서
CVE-2016-1000027과 관련된 레거시 HTTP Invoker 기능이 제거되어 신규 통합의 방향으로
권장합니다. 이는 아키텍처 선택의 이유이지 모든 Boot 3 버전이 현재 지원되거나
모든 취약점이 해결됐다는 뜻이 아닙니다. 현재 고정 버전의 다른 잔여 이슈도 검토합니다.

Boot 2는 Java 11 / javax 애플리케이션을 즉시 이전하기 어려운 소비자를 위해
유지합니다. FluxGate 업데이트와 무관한 플랫폼 이전을 강제하지 않는 대신,
레거시 의존성의 잔여 위험, 애플리케이션별 노출 검토와 이전 계획이 필요합니다.
Boot 3와 동일한 업스트림 보안지원을 약속하는 선택이 아닙니다.

## 다른 제품의 공개 대응: 참고 사례와 적용 한계

| 제품 | 공개된 실제 조치 | 해석할 때의 경계 |
| --- | --- | --- |
| Atlassian Bitbucket Data Center 8.19.7 / 9.0 | 문제 클래스 미사용을 확인하고 Spring을 포크하여 remoting 패키지를 제거한 수정본을 적용했습니다. | 제거된 패키지를 사용하는 플러그인은 수정이 필요하다고 명시했습니다. 무조건적인 호환 보장은 아닙니다. |
| WSO2 API Manager / Identity Server 등 공식 목록의 제품 | Spring 5.3.31~5.3.39 포크에서 취약 클래스를 제거했습니다. 공식 문서는 5.3.31-wso2v1 이후 수정 계열을 설명합니다. | 영향 없음 판정은 해당 번들 포크와 제품 사용 방식에 대한 것으로, 원본 Spring 5.3.39의 안전 판정이 아닙니다. |
| IBM OpenPages for Cloud Pak for Data | 미사용 RCE 코드를 제거한 spring-web-5.3.39.2504.jar / 5.3.39.2410.jar를 제공하며 Cloud Pak for Data 4.7 이상, OpenPages 서비스 8.302.2 이상을 권고했습니다. | IBM의 수정된 제품 산출물이며 공개 원본 5.3.39로 해결됐다는 뜻이 아닙니다. |

공식 근거:
[Bitbucket 8.19 릴리스 노트](https://confluence.atlassian.com/bitbucketserver/bitbucket-data-center-8-19-release-notes-1354498386.html),
[Bitbucket 9.0 릴리스 노트](https://confluence.atlassian.com/bitbucketserver/bitbucket-data-center-9-0-release-notes-1369277827.html),
[WSO2 CVE 판정](https://security.docs.wso2.com/en/latest/security-announcements/cve-justifications/2025/CVE-2016-1000027/),
[WSO2 제거 패치](https://github.com/wso2/wso2-spring-framework/pull/1/files),
[IBM OpenPages 보안 공지](https://www.ibm.com/support/pages/security-bulletin-ibm-openpages-ibm-cloud-pak-data-vulnerable-spring-web-unsafe-deserialization-cve-2016-1000027-0).

벤더의 공개 평가를 요약한 것이며 제품 전체에 대한 독립 침투시험 결과는 아닙니다.
공통점은 제품별 사용처 검토에 더해 문제 코드를 제거했다는 점입니다.
단순 버전명 변경이나 검사 무시와는 다릅니다.

완제품 벤더는 자신이 배포하는 앱과 의존성 묶음을 통제합니다. FluxGate는 통제할
수 없는 소비자 앱에도 포함되는 라이브러리입니다. 고객의 Spring을 몰래 교체하면
다른 기능이나 Spring 모듈 간 정합성, 기존 지원 계약에 영향을 줄 수 있습니다.
따라서 벤더 포크를 자동 대체하지 않습니다. 별도 보안강화 배포판이나 벤더 패치
선택지는 명시적 선택, 출처·라이선스/재배포 권리·유지보수 검토 및 전체 앱의
호환성 검증이 필요합니다. 상용지원 계약만으로 해당 CVE가 수정됐다고 볼 수 없습니다.

## 알려진 경계

- Boot 2에는 원본 Spring Web 5.3.39가 포함되며 GHSA-4wrc-f8pq-fpqp /
  CVE-2016-1000027 탐지가 남아 있습니다. HTTP Invoker로 신뢰할 수 없는 Java
  객체를 역직렬화하면 원격 코드 실행으로 이어질 수 있습니다. Spring Web의
  존재만으로 실제 공격 가능성이 확정되지는 않지만, 한 컨텍스트에서 exporter가
  없다는 사실만으로 모든 소비자가 안전해지는 것도 아닙니다.
- Spring 6는 해당 HTTP Invoker 기능을 제거했습니다. 그러나 Java / javax /
  Jakarta 호환성을 무시하고 Boot 2에 Spring 6만 강제로 덮어쓰지 마세요.
- Boot 3도 전체 보안 통과를 뜻하지 않습니다. 현재 readiness 기록에는 Spring
  Web MVC 6.2.19의 GHSA-pc63-qcmh-9cmg 및 GHSA-j9f9-w8pj-32f8이 남아 있습니다.
  최종 배포 의존성을 기준으로 계속 평가하며, 이 방침으로 해결됐다고 선언하지 않습니다.
- 레거시 샘플에는 Spring Data, SnakeYAML, 로깅 의존성 등의 추가 발견사항이
  존재할 수 있습니다. SDK 일부의 검사 통과는 전체 앱이나 샘플의 통과가 아닙니다.
- [Draft PR #83](https://github.com/OpenFluxGate/fluxgate/pull/83)의 Boot 2 검사는
  mock servlet 컨텍스트와 양성 탐지 대조군을 사용합니다. 실제 소비자 배포 검사가
  아니며, 제안된 증거이지 머지된 런타임 보호 기능이나 보안 통과 판정이 아닙니다.

남은 배포 조건은 [SECURITY.md](../../SECURITY.md)와
[릴리스 준비 현황](../release-0.5.0-readiness.md)을 함께 확인하세요.

## 소비자 확인 목록

통합 경로 도입 또는 업그레이드 전:

- [ ] 실제 실행 JDK, Boot/Spring 버전, 활성 프로파일, 전이 의존성을 포함한 전체
  앱 의존성/SBOM을 기록합니다. SDK·서버·샘플은 구분하여 검토합니다.
- [ ] `HttpInvokerServiceExporter`, `SimpleHttpInvokerServiceExporter`,
  애플리케이션 정의 exporter 및 사용자 정의 Java 역직렬화 코드를 조사합니다.
  FluxGate 소스 문자열 검색만으로 판정하지 않습니다.
- [ ] Java 설정, XML, web.xml, 직접 Servlet 등록, 외부 라이브러리 자동 구성,
  부모/자식 컨텍스트와 lazy/dynamic 등록을 확인합니다.
- [ ] 실제 배포 설정의 bean, handler/servlet mapping과 ingress 경로를 확인합니다.
  기본 mock 컨텍스트 검사는 소비자 정의 endpoint의 부재를 보장하지 않습니다.
- [ ] 신뢰할 수 없는 입력이 Java 역직렬화 endpoint에 도달하지 않도록 합니다.
  인증이나 사내망만으로 안전하다고 판정하지 않습니다. 미사용 HTTP Invoker
  endpoint를 제거하고, 필요한 원격 호출은 JSON 등 Java 직렬화가 아닌 방식으로
  전환을 계획합니다.
- [ ] 해당 Boot 2 / Java 11 및 Boot 3 / Java 17 이상 환경에서 기능·기동·통합
  검사를 수행합니다. 호환되지 않는 Spring 메이저 버전을 혼합하지 않습니다.
- [ ] 실제 배포 의존성 전체와 활성 기능의 다른 취약점도 검토합니다.
  이 CVE에 대한 판정이 전체 앱의 보안 통과는 아닙니다.
- [ ] 배포별 근거, 검토 담당자, 잔여 위험과 재검토일을 기록합니다.
  확인하지 못한 조건은 '미검증'으로 남기고 '영향 없음'으로 표시하지 않습니다.
- [ ] 의존성·프로파일·endpoint·routing·앱 설정 변경 후 재평가합니다.
  업스트림 유지보수 플랫폼으로 이전하거나, 출처·호환성·재배포 권리를 확인한
  지원 벤더 패치를 별도로 검토합니다.

## 검증 증거와 보안 게이트

호환성 테스트 통과, 제한된 노출 검사 통과, 의존성 보안 검사 통과, 배포 승인은
서로 다른 사실입니다. 각 결과에 커밋·범위·한계를 기록하세요. 원본의 문제 기능이
배포물에 남아 있는데 패키지 CVE가 해결됐다고 선언하지 않습니다.

문서 변경만으로 현재 Dependency Review 실패가 통과로 바뀌지 않습니다.
이 방침의 일부로 심각도 기준이나 검사 범위를 낮추거나, 필수 검사를 제거하거나,
의존성을 숨기거나, advisory를 예외 처리하지 않습니다. 향후 예외가 필요하면
정확한 advisory·artifact 범위, 근거, 담당자, 재검토/만료 조건과 잔여 위험을
명시한 별도 승인이 필요합니다. 이 문서는 그러한 예외를 승인하지 않습니다.

머지·배포 전에 최종 커밋에서 최신 준비 기록과 검사 결과를 대조해야 합니다.
검사가 없는 stacked PR은 필수 릴리스 검사를 통과했다는 근거가 아닙니다.

## 근거

- [GitHub advisory: 원본 Spring Web 6.0.0 미만 영향](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp)
- [Spring 유지보수자: HTTP Invoker 노출과 역직렬화 위험](https://github.com/spring-projects/spring-framework/issues/24434)
- [Spring Boot 2.7.18 및 OSS 지원 종료](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/)
