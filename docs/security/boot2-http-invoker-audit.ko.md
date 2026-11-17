# Boot 2 HTTP Invoker 노출 검증

이 검사는 취약점 수정, 보안 게이트 예외 또는 모든 고객 서비스의 안전 보장이 아닙니다. 포함된 Spring Web 아티팩트의 GHSA-4wrc-f8pq-fpqp 탐지는 그대로 남습니다.

Boot 2.7 / Spring 5.3 servlet 컨텍스트에서 스타터 등록 자동설정 9개와 MVC를 활성화하고 Mongo/Redis/reload는 비활성화합니다. HttpInvokerServiceExporter 빈 부재를 확인하며, 검출 대조군에서는 애플리케이션이 직접 등록한 exporter 및 HandlerMapping의 테스트 엔드포인트 연결을 검출합니다. 직렬화 공격 입력을 전송하거나 네트워크 서버를 열지 않습니다. 클래스패스 검사로 취약 클래스를 감추어 성공시키지 않았음을 확인합니다.

빌드 JDK 17 이상과 테스트용 Java 11을 사용합니다.

```sh
BOOT2_JAVA_HOME=/path/to/jdk11 bash scripts/security/audit-boot2-http-invoker.sh
```

스크립트는 Maven 의존 트리를 target/security-evidence에 기록합니다. 버전, scope, allowlist, dependency-review 게이트는 변경하지 않습니다. 예상 의존 계열을 확인할 뿐 취약점 0건을 검증하는 것은 아닙니다.

한계: mock servlet 컨텍스트이며 실제 네트워크 서버 배포 검사가 아닙니다. 임의 URL 전체, 외부 인프라 활성화, 고객의 부모/자식·지연/동적 컨텍스트, 커스텀 역직렬화, 프록시 및 운영 라우팅은 검증하지 않습니다. 고객의 실제 구성과 서비스 목록에서 별도 점검해야 합니다. Boot3에서는 해당 HttpInvoker 클래스가 제거되어 동일 import 기반 테스트를 적용하지 않으며, 독립 Boot3 호환성·보안 검증을 대체하지 않습니다.

근거: https://github.com/advisories/GHSA-4wrc-f8pq-fpqp 및 https://github.com/spring-projects/spring-framework/issues/24434 .

스크립트는 Python 3을 요구하며 Maven 실행 전에 Java11이 아닌 BOOT2_JAVA_HOME을 거부합니다. 이전 감사 XML을 제거하고 새 보고서의 JVM, 필수 테스트 이름, 실패·오류·스킵 0건을 검증합니다.
