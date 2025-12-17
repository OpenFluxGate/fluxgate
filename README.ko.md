# FluxGate

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x%20%7C%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/OpenFluxGate/fluxgate/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/OpenFluxGate/fluxgate/actions)
[![Admin UI](https://img.shields.io/badge/Admin%20UI-FluxGate%20Studio-orange.svg)](https://github.com/OpenFluxGate/fluxgate-studio)

[English](README.md) | 한국어

**FluxGate**는 Java 애플리케이션을 위한 프로덕션 수준의 분산 Rate Limiting 프레임워크입니다. [Bucket4j](https://github.com/bucket4j/bucket4j)를 기반으로
구축되었으며, Redis 기반 분산 Rate Limiting, MongoDB 규칙 관리, Spring Boot 자동 설정 등 엔터프라이즈급 기능을 제공합니다.

## 주요 기능

- **분산 Rate Limiting** - 원자적 Lua 스크립트를 사용한 Redis 기반 토큰 버킷 알고리즘
- **다중 대역 지원** - 여러 Rate Limit 계층 지원 (예: 100/초 + 1000/분 + 10000/시간)
- **동적 규칙 관리** - 재시작 없이 MongoDB에서 규칙 저장 및 업데이트
- **Spring Boot 자동 설정** - 합리적인 기본값으로 무설정 시작 가능
- **LimitScope 기반 키 해석** - IP, 사용자 ID, API 키 또는 복합 키로 Rate Limit 적용
- **복합 키 지원** - 여러 식별자 조합 (예: IP + 사용자 ID) 으로 세밀한 제어 가능
- **WAIT_FOR_REFILL 정책** - 즉시 거부 대신 토큰 리필 대기
- **RequestContext 커스터마이징** - Rate Limiting 전에 클라이언트 IP 재정의, 커스텀 속성 추가
- **다중 필터 지원** - Java Config를 통해 다양한 우선순위의 여러 필터 구성
- **프로덕션 안전 설계** - Redis 서버 시간 사용 (클럭 드리프트 없음), 정수 연산만 사용
- **HTTP API 모드** - REST API를 통한 중앙 집중식 Rate Limiting 서비스
- **플러그인 아키텍처** - 커스텀 핸들러 및 저장소로 쉽게 확장 가능
- **구조화된 로깅** - ELK/Splunk 통합을 위한 상관관계 ID가 포함된 JSON 로깅
- **Prometheus 메트릭** - 모니터링 및 알림을 위한 내장 Micrometer 통합

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         FluxGate Architecture                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐   │
│  │   Client     │───▶│ Spring Boot  │───▶│   FluxGate Filter        │   │
│  │  Application │    │  Application │    │  (Auto Rate Limiting)    │   │
│  └──────────────┘    └──────────────┘    └───────────┬──────────────┘   │
│                                                      │                  │
│                      ┌───────────────────────────────┼───────────────┐  │
│                      │                               ▼               │  │
│                      │  ┌─────────────────────────────────────────┐  │  │
│                      │  │            RateLimitHandler             │  │  │
│                      │  │  ┌─────────────┐  ┌──────────────────┐  │  │  │
│                      │  │  │   Direct    │  │    HTTP API      │  │  │  │
│                      │  │  │   Redis     │  │    (REST Call)   │  │  │  │
│                      │  │  └──────┬──────┘  └────────┬─────────┘  │  │  │
│                      │  └─────────┼──────────────────┼────────────┘  │  │
│                      │            │                  │               │  │
│                      └────────────┼──────────────────┼───────────────┘  │
│                                   │                  │                  │
│                                   ▼                  ▼                  │
│  ┌────────────────────────────────────┐    ┌────────────────────────┐   │
│  │             Redis                  │    │  Rate Limit Service    │  │
│  │  ┌──────────────────────────────┐  │    │  (fluxgate-sample-     │  │
│  │  │   Token Bucket State         │  │    │   redis on port 8082)  │  │
│  │  │   (Lua Script - Atomic)      │  │◀───│                        │  │
│  │  └──────────────────────────────┘  │    └────────────────────────┘  │
│  └────────────────────────────────────┘                                 │
│                                                                         │
│  ┌────────────────────────────────────┐                                 │
│  │           MongoDB                  │                                 │
│  │  ┌──────────────────────────────┐  │                                 │
│  │  │   Rate Limit Rules           │  │                                 │
│  │  │   (Dynamic Configuration)    │  │                                 │
│  │  └──────────────────────────────┘  │                                 │
│  └────────────────────────────────────┘                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## 모듈

| 모듈                               | 설명                                     |
|----------------------------------|----------------------------------------|
| **fluxgate-core**                | Bucket4j 통합을 포함한 핵심 Rate Limiting 엔진   |
| **fluxgate-redis-ratelimiter**   | Lua 스크립트를 사용한 Redis 기반 분산 Rate Limiter |
| **fluxgate-mongo-adapter**       | 동적 규칙 관리를 위한 MongoDB 어댑터               |
| **fluxgate-spring-boot3-starter** | Spring Boot 3.x 자동 설정 (Java 17+, jakarta.servlet) |
| **fluxgate-spring-boot2-starter** | Spring Boot 2.x 자동 설정 (Java 11+, javax.servlet) |
| **fluxgate-testkit**             | 통합 테스트 유틸리티                            |
| **fluxgate-samples**             | 다양한 사용 사례를 보여주는 샘플 애플리케이션              |

## 빠른 시작

### 사전 요구 사항

- Java 11+
- Maven 3.8+
- Redis 6.0+ (분산 Rate Limiting용)
- MongoDB 4.4+ (선택사항, 규칙 관리용)

### 1. 의존성 추가

```xml
<!-- Spring Boot 3.x (Java 17+) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.3.2</version>
</dependency>

<!-- Spring Boot 2.x (Java 11+) -->
<!--
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot2-starter</artifactId>
    <version>0.3.2</version>
</dependency>
-->

<!-- For Redis-backed rate limiting -->
<dependency>
<groupId>io.github.openfluxgate</groupId>
<artifactId>fluxgate-redis-ratelimiter</artifactId>
<version>0.3.2</version>
</dependency>

<!-- For MongoDB rule management (optional) -->
<dependency>
<groupId>io.github.openfluxgate</groupId>
<artifactId>fluxgate-mongo-adapter</artifactId>
<version>0.3.2</version>
</dependency>
```

### 2. 애플리케이션 설정

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    filter-enabled: true
    default-rule-set-id: api-limits
    include-patterns:
      - /api/*
    exclude-patterns:
      - /health
      - /actuator/*
```

### 3. Rate Limiting 필터 활성화

```java

@SpringBootApplication
@EnableFluxgateFilter(handler = HttpRateLimitHandler.class)
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 4. Rate Limiting 테스트

```bash
# 12개 요청 전송 (10 req/min 제한 시)
for i in {1..12}; do
  curl -s -o /dev/null -w "요청 $i: %{http_code}\n" http://localhost:8080/api/hello
done

# 예상 결과:
# 요청 1-10: 200
# 요청 11-12: 429 (Too Many Requests)
```

## 배포 패턴

### 패턴 1: 직접 Redis 연결

각 애플리케이션 인스턴스가 Redis에 직접 연결하는 간단한 배포에 적합합니다.

```
┌─────────────┐     ┌─────────────┐
│   App #1    │────▶│             │
├─────────────┤     │    Redis    │
│   App #2    │────▶│             │
├─────────────┤     │             │
│   App #N    │────▶│             │
└─────────────┘     └─────────────┘
```

### 패턴 2: HTTP API 모드 (중앙 집중식)

전용 Rate Limiting 서비스가 필요한 마이크로서비스 아키텍처에 적합합니다.

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────┐
│   App #1    │────▶│                 │     │             │
├─────────────┤     │  Rate Limit     │────▶│    Redis    │
│   App #2    │────▶│  Service (8082) │     │             │
├─────────────┤     │                 │     │             │
│   App #N    │────▶│                 │     │             │
└─────────────┘     └─────────────────┘     └─────────────┘
```

```yaml
# 클라이언트 애플리케이션 설정
fluxgate:
  api:
    url: http://rate-limit-service:8082
  ratelimit:
    filter-enabled: true
```

## 샘플 애플리케이션

| 샘플                             | 포트   | 설명                                |
|--------------------------------|------|-----------------------------------|
| **fluxgate-sample-standalone** | 8085 | MongoDB + Redis 직접 통합을 포함한 풀스택    |
| **fluxgate-sample-redis**      | 8082 | Redis 백엔드를 사용한 Rate Limit 서비스     |
| **fluxgate-sample-mongo**      | 8081 | MongoDB를 사용한 규칙 관리                |
| **fluxgate-sample-filter**     | 8083 | 자동 Rate Limiting 필터를 사용하는 클라이언트 앱 |
| **fluxgate-sample-api**        | 8084 | Rate Limit 확인용 REST API           |

### 샘플 실행

```bash
# 인프라 시작
docker-compose up -d redis mongodb

# Rate Limit 서비스 시작
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis

# 클라이언트 애플리케이션 시작 (다른 터미널에서)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter

# Rate Limiting 테스트
curl http://localhost:8083/api/hello
```

## 설정 참조

### FluxGate 속성

| 속성                                       | 기본값                                    | 설명                                         |
|------------------------------------------|----------------------------------------|--------------------------------------------|
| `fluxgate.redis.enabled`                 | `false`                                | Redis Rate Limiter 활성화                     |
| `fluxgate.redis.uri`                     | `redis://localhost:6379`               | Redis 연결 URI                               |
| `fluxgate.redis.mode`                    | `auto`                                 | Redis 모드: `standalone`, `cluster`, `auto` |
| `fluxgate.mongo.enabled`                 | `false`                                | MongoDB 어댑터 활성화                            |
| `fluxgate.mongo.uri`                     | `mongodb://localhost:27017/fluxgate`   | MongoDB 연결 URI                             |
| `fluxgate.mongo.database`                | `fluxgate`                             | MongoDB 데이터베이스 이름                          |
| `fluxgate.mongo.rule-collection`         | `rate_limit_rules`                     | Rate Limit 규칙 컬렉션 이름                       |
| `fluxgate.mongo.event-collection`        | -                                      | 이벤트 컬렉션 이름 (선택사항)                          |
| `fluxgate.mongo.ddl-auto`                | `validate`                             | DDL 모드: `validate` 또는 `create`             |
| `fluxgate.ratelimit.filter-enabled`      | `false`                                | Rate Limit 필터 활성화                          |
| `fluxgate.ratelimit.default-rule-set-id` | `default`                              | 기본 규칙 세트 ID                                |
| `fluxgate.ratelimit.include-patterns`    | `[/api/*]`                             | Rate Limit을 적용할 URL 패턴                     |
| `fluxgate.ratelimit.exclude-patterns`    | `[]`                                   | 제외할 URL 패턴                                 |
| `fluxgate.ratelimit.wait-for-refill.enabled` | `false`                            | WAIT_FOR_REFILL 정책 활성화                     |
| `fluxgate.ratelimit.wait-for-refill.max-wait-time-ms` | `5000`                   | 최대 대기 시간 (밀리초)                             |
| `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` | `100`               | 최대 동시 대기 요청 수                              |
| `fluxgate.api.url`                       | -                                      | 외부 Rate Limit API URL                      |
| `fluxgate.metrics.enabled`               | `true`                                 | Prometheus/Micrometer 메트릭 활성화             |

### MongoDB DDL Auto 모드

`fluxgate.mongo.ddl-auto` 속성은 FluxGate가 MongoDB 컬렉션을 처리하는 방식을 제어합니다:

| 모드         | 설명                                      |
|------------|----------------------------------------|
| `validate` | (기본값) 컬렉션이 존재하는지 검증합니다. 없으면 에러를 발생시킵니다. |
| `create`   | 컬렉션이 없으면 자동으로 생성합니다.                    |

**설정 예시:**

```yaml
fluxgate:
  mongo:
    enabled: true
    uri: mongodb://localhost:27017/fluxgate
    database: fluxgate
    rule-collection: my_rate_limit_rules    # 사용자 정의 컬렉션 이름
    event-collection: my_rate_limit_events  # 선택사항: 이벤트 로깅 활성화
    ddl-auto: create                        # 컬렉션 자동 생성
```

### Rate Limit 규칙 설정

```java
RateLimitRule rule = RateLimitRule.builder("api-rule")
        .name("API Rate Limit")
        .enabled(true)
        .scope(LimitScope.PER_IP)  // GLOBAL, PER_IP, PER_USER, PER_API_KEY, CUSTOM
        .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)  // 또는 WAIT_FOR_REFILL
        .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
                .label("초당 10회")
                .build())
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
                .label("분당 100회")
                .build())
        .ruleSetId("api-limits")
        .attribute("tier", "standard")  // 추적용 커스텀 속성
        .build();
```

### LimitScope 옵션

| LimitScope | 키 소스 | 설명 |
|------------|--------|------|
| `GLOBAL` | `"global"` | 모든 요청이 단일 버킷 공유 |
| `PER_IP` | `RequestContext.clientIp` | IP 주소별 버킷 |
| `PER_USER` | `RequestContext.userId` | 사용자별 버킷 (헤더로 설정) |
| `PER_API_KEY` | `RequestContext.apiKey` | API 키별 버킷 |
| `CUSTOM` | `attributes.get(keyStrategyId)` | RequestContext 속성에서 커스텀 키 |

### 복합 키 예제 (IP + 사용자)

IP와 사용자 조합으로 세밀한 Rate Limiting:

```java
// CUSTOM scope 규칙
RateLimitRule rule = RateLimitRule.builder("composite-rule")
    .name("IP+User Rate Limit")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")  // context.attributes.get("ipUser") 조회
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();

// RequestContextCustomizer로 복합 키 생성
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        String userId = request.getHeader("X-User-Id");
        String clientIp = request.getRemoteAddr();

        // 복합 키 생성: "192.168.1.100:user-123"
        String compositeKey = userId != null ? clientIp + ":" + userId : clientIp;
        builder.attribute("ipUser", compositeKey);

        return builder;
    };
}
```

### RequestContext 커스터마이징

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        // PER_USER scope용 userId 설정
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            builder.userId(userId);
        }

        // PER_API_KEY scope용 apiKey 설정
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }

        // 프록시 헤더에서 클라이언트 IP 재정의
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null) {
            builder.clientIp(realIp);
        }

        // keyStrategyId="tenantId"인 CUSTOM scope용 테넌트 정보
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

## 관측성 (Observability)

FluxGate는 즉시 사용 가능한 포괄적인 관측성 기능을 제공합니다.

### 구조화된 로깅

FluxGate는 ELK Stack이나 Splunk와 같은 로그 집계 시스템과 쉽게 통합할 수 있도록 상관관계 ID가 포함된 JSON 형식의 로그를 출력합니다.

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "level": "INFO",
  "logger": "org.fluxgate.spring.filter.FluxgateRateLimitFilter",
  "message": "Request completed",
  "fluxgate.rule_set": "api-limits",
  "fluxgate.rule_id": "rate-limit-rule-1",
  "fluxgate.allowed": true,
  "fluxgate.remaining_tokens": 9,
  "fluxgate.client_ip": "192.168.1.100",
  "correlation_id": "abc123-def456"
}
```

애플리케이션에서 `logback-spring.xml`을 포함하여 구조화된 로깅을 활성화합니다:

```xml
<include resource="org/fluxgate/spring/logback-spring.xml"/>
```

### Prometheus 메트릭

FluxGate는 `spring-boot-starter-actuator`가 클래스패스에 있을 때 자동으로 Micrometer 기반 메트릭을 노출합니다.

**사용 가능한 메트릭:**

| 메트릭 | 타입 | 설명 |
|--------|------|-------------|
| `fluxgate_requests_total` | Counter | 엔드포인트, 메서드, rule_set별 총 Rate Limit 요청 수 |
| `fluxgate_tokens_remaining` | Gauge | 버킷에 남은 토큰 수 |

**Prometheus 출력 예시:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/test",method="GET",rule_set="api-limits"} 42.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/test",rule_set="api-limits"} 8.0
```

**설정:**

```yaml
fluxgate:
  metrics:
    enabled: true  # 기본값: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
```

## 소스에서 빌드

```bash
# 저장소 클론
git clone https://github.com/OpenFluxGate/fluxgate.git
cd fluxgate

# 모든 모듈 빌드
./mvnw clean install

# 테스트 실행
./mvnw test

# 테스트 없이 빌드
./mvnw clean install -DskipTests
```

## 문서

- [FluxGate Core](fluxgate-core/README.md) - 핵심 Rate Limiting 개념 및 API
- [Redis Rate Limiter](fluxgate-redis-ratelimiter/README.md) - Redis를 사용한 분산 Rate Limiting
- [MongoDB Adapter](fluxgate-mongo-adapter/README.md) - 동적 규칙 관리
- [Spring Boot Starter](fluxgate-spring-boot-starter/README.md) - 자동 설정 가이드
- [FluxGate 확장](HOW_TO_EXTEND_RATELIMITER.md) - 커스텀 구현
- [기여 가이드](CONTRIBUTING.ko.md) - 기여 가이드

## 기여하기

기여를 환영합니다! 자세한 내용은 [기여 가이드](CONTRIBUTING.md)를 참조하세요.

1. 저장소 포크
2. 기능 브랜치 생성 (`git checkout -b feature/amazing-feature`)
3. 변경 사항 커밋 (`git commit -m 'Add amazing feature'`)
4. 브랜치에 푸시 (`git push origin feature/amazing-feature`)
5. Pull Request 열기

## 관련 프로젝트

| 프로젝트 | 설명 |
|---------|-------------|
| [FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio) | Rate Limit 규칙 관리를 위한 웹 기반 어드민 UI |

## 로드맵

- [ ] 슬라이딩 윈도우 Rate Limiting 알고리즘
- [x] Prometheus 메트릭 통합
- [x] Redis Cluster 지원
- [x] 상관관계 ID가 포함된 구조화된 JSON 로깅
- [ ] gRPC API 지원
- [x] Rate Limit 할당량 관리 UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
- [ ] Circuit Breaker 통합

## 라이선스

이 프로젝트는 MIT 라이선스 하에 라이선스가 부여됩니다 - 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 감사의 글

- [Bucket4j](https://github.com/bucket4j/bucket4j) - 기반 Rate Limiting 라이브러리
- [Lettuce](https://lettuce.io/) - Java용 Redis 클라이언트
- [Spring Boot](https://spring.io/projects/spring-boot) - 애플리케이션 프레임워크

---

**FluxGate** - 간편한 분산 Rate Limiting
