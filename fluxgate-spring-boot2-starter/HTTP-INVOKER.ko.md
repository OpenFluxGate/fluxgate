# Boot 2의 HTTP Invoker 운영 검토

Boot 2.7 스타터는 Java 11 이상과 `javax.servlet`을 지원합니다. 공개 Spring Web 5.3.39에는 GHSA-4wrc-f8pq-fpqp가 지목한 HTTP Invoker 클래스가 들어 있습니다. 응용 프로그램이 HTTP Invoker 서비스를 등록하고 신뢰할 수 없는 호출자가 그 경로에 접근할 수 있으면 Java 역직렬화에 도달할 수 있습니다. 배포한 앱의 servlet 매핑, Spring 컨텍스트, XML·Java 설정과 외부 라이브러리 등록을 점검하세요. 사용하지 않는 경로를 제거하고, 사용하는 경로는 서비스 접근을 제한한 뒤 Java 직렬화를 사용하지 않는 프로토콜로 이전하세요. [Spring 유지관리자 설명](https://github.com/spring-projects/spring-framework/issues/24434) · [보안 권고](https://github.com/advisories/GHSA-4wrc-f8pq-fpqp)

독립 실행 샘플은 실행 중인 컨텍스트에서 exporter 빈의 메타데이터를 점검합니다. 실제 서비스가 지연 등록, 동적 매핑, 별도 컨텍스트를 사용한다면 해당 서비스에서 따로 확인해야 합니다. 의존성 경고는 남아 있습니다. Java 17과 Jakarta Servlet을 지원하는 앱에는 HTTP Invoker가 제거된 Spring Framework 6 기반 Boot 3 스타터를 권장합니다. 전환 후에는 최종 의존성 트리와 앱의 필터 동작을 검증하세요. [Spring Framework 6 릴리스 노트](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-6.0-Release-Notes)

WSO2는 자사 지정 제품용 포크에서 관련 클래스를 제거했습니다. HeroDevs는 별도의 상용 NES `no-remoting` 아티팩트를 제공하며 체험판은 시연 전용으로 안내합니다. 두 대안 모두 FluxGate에서 호환성을 확인하지 않았습니다. 도입 전에는 Spring 모듈 간 버전 정합성, 바이너리 호환성, 저장소 접근과 운영 재배포 권리, 실제 견적과 지원 조건을 확인해야 합니다. [WSO2 보안 공지](https://security.docs.wso2.com/en/latest/security-announcements/cve-justifications/2025/CVE-2016-1000027/) · [HeroDevs 릴리스 노트](https://docs.herodevs.com/spring/release-notes/spring-framework-5-3)
