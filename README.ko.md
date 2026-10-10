# FluxGate

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.x%20%7C%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Build](https://github.com/OpenFluxGate/fluxgate/actions/workflows/maven-ci.yml/badge.svg)](https://github.com/OpenFluxGate/fluxgate/actions)
[![Benchmark](https://img.shields.io/badge/Benchmark-Results-blueviolet.svg)](https://openfluxgate.github.io/fluxgate/benchmark/)
[![Admin UI](https://img.shields.io/badge/Admin%20UI-FluxGate%20Studio-orange.svg)](https://github.com/OpenFluxGate/fluxgate-studio)

[English](README.md) | 한국어

> **🚀 라이브 데모** - 설치 없이 바로 체험:
>
> | 데모 | 설명 | 링크 |
> |------|------|------|
> | **FluxGate Studio** | Rate Limit 규칙 관리를 위한 어드민 UI | [데모 열기](http://13.124.192.116:3000/) |
> | **FluxGate API** | Swagger UI가 포함된 Rate Limiting API | [Swagger 열기](http://13.124.192.116:8080/swagger-ui/index.html) |

**FluxGate**는 Java 애플리케이션을 위한 분산 Rate Limiting 프레임워크입니다. [Bucket4j](https://github.com/bucket4j/bucket4j)를 기반으로
구축되었으며, Redis 기반 분산 Rate Limiting, MongoDB 규칙 관리, Spring Boot 통합을 제공합니다.

## 주요 기능

- **분산 Rate Limiting** - 원자적 Lua 스크립트를 사용한 Redis 기반 토큰 버킷 알고리즘
- **다중 대역 지원** - 여러 Rate Limit 계층 (예: 100/초 + 1000/분 + 10000/시간). 한 규칙의 모든 대역은 전부-또는-전무로 평가됩니다
- **세 가지 알고리즘** - `TOKEN_BUCKET`(기본), `SLIDING_WINDOW`, `FIXED_WINDOW`. 마지막은 임의의 시간대에서 달력 기준 `DAILY` / `WEEKLY` / `MONTHLY` 쿼터를 지원합니다
- **규칙 매칭** - 경로 패턴, HTTP 메서드, 헤더 조건으로 규칙이 적용될 요청을 고릅니다. MongoDB와 YAML(`fluxgate.ratelimit.rule-sets`) 모두 지원합니다
- **동적 규칙 관리** - 재시작 없이 MongoDB에서 규칙 저장 및 업데이트
- **Spring Boot 자동 설정** - 바로 동작합니다. Rate Limit 핸들러를 스타터가 직접 제공합니다
- **인메모리 모드** - `fluxgate.ratelimit.mode=IN_MEMORY`로 인프라 없이 단일 인스턴스 Limiter 사용
- **LimitScope 기반 키 해석** - IP, 사용자 ID, API 키 또는 복합 키로 Rate Limit 적용
- **복합 키 지원** - 여러 식별자 조합 (예: IP + 사용자 ID) 으로 세밀한 제어 가능
- **WAIT_FOR_REFILL 정책** - 즉시 거부 대신 토큰 리필 대기
- **RequestContext 커스터마이징** - Rate Limiting 전에 클라이언트 IP 재정의, 커스텀 속성 추가
- **다중 필터 지원** - Java Config를 통해 다양한 우선순위의 여러 필터 구성
- **복원력** - 재시도, 서킷 브레이커, Redis 장애 시 인메모리 폴백
- **표준 Rate Limit 헤더** - 레거시 `X-RateLimit-*`과 IETF `RateLimit-*` 계열, RFC 9457 problem 응답
- **신뢰 프록시 처리** - 전달된 클라이언트 IP 헤더는 설정된 프록시에서 온 경우에만 신뢰합니다
- **프로덕션 안전 설계** - Redis 서버 시간 사용 (클럭 드리프트 없음), fail-closed 기본값, 상한이 있는 캐시
- **플러그인 아키텍처** - 커스텀 핸들러, 응답 Writer, 규칙 Provider로 쉽게 확장 가능
- **구조화된 로깅** - ELK/Splunk 통합을 위한 상관관계 ID가 포함된 JSON 로깅
- **Prometheus 메트릭** - 내장 Micrometer 통합, Grafana 대시보드와 알림 규칙 동봉

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
│                      │  │        FluxgateRateLimitHandler         │  │  │
│                      │  │  ┌─────────────┐  ┌──────────────────┐  │  │  │
│                      │  │  │ Engine      │  │   사용자 정의     │  │  │  │
│                      │  │  │ Backed      │  │   핸들러          │  │  │  │
│                      │  │  │ (기본값)     │  │   (HTTP, custom) │  │  │  │
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

결정 경로는 항상 동일합니다. 필터(또는 `@RateLimit` 애스펙트)가 `RequestContext`를 만들어
`FluxgateRateLimitHandler`에 넘기고, 라이브러리 기본 핸들러가 `RateLimitEngine`에 위임하면
엔진이 룰셋을 해석해 `RateLimiter`를 호출합니다. 전체 흐름은
[docs/ko/architecture/README.ko.md](docs/ko/architecture/README.ko.md)를 참고하세요.

## 모듈

| 모듈 | 설명 |
|------|------|
| **fluxgate-core** | 핵심 Rate Limiting 엔진, SPI, Bucket4j 인메모리 Limiter |
| **fluxgate-redis-ratelimiter** | Lua 스크립트를 사용한 Redis 기반 분산 Rate Limiter |
| **fluxgate-mongo-adapter** | 동적 규칙 관리를 위한 MongoDB 어댑터 |
| **fluxgate-spring-boot3-starter** | Spring Boot 3.x 자동 설정 (Java 17+, jakarta.servlet) |
| **fluxgate-spring-boot2-starter** | Spring Boot 2.7.x 자동 설정 (Java 11+, `javax.servlet`). 기능은 동일하며 **Spring Boot 2.7.x가 필요합니다** ([README](fluxgate-spring-boot2-starter/README.md)) |
| **fluxgate-control-support** | 컨트롤 플레인 보조: `@NotifyRuleChange` / `@NotifyFullReload` 와 Redis 규칙 변경 통지기 |
| **fluxgate-testkit** | 통합 테스트 유틸리티 |
| **fluxgate-benchmarks** | JMH 벤치마크 (배포되지 않음) |
| **fluxgate-samples** | 다양한 사용 사례를 보여주는 샘플 애플리케이션 |

## 빠른 시작

두 가지 경로로 시작할 수 있습니다. **경로 A**는 Java와 Maven만 있으면 됩니다 — 인프라 없이
실행되는 인메모리 Limiter로, 첫 데모나 단일 인스턴스 서비스에 딱 맞습니다. **경로 B**는 Redis를
추가해 다중 인스턴스 분산 Rate Limiting을 구현하고, 선택적으로 MongoDB를 더해 재시작 없는
동적 규칙 관리까지 확장합니다.

### 사전 요구 사항

- Java 17+ 및 Maven 3.8+ (경로 A)
- Java 11 / Spring Boot 2.7도 `fluxgate-spring-boot2-starter`로 지원
- Docker (경로 B, Redis/MongoDB 시작용)

---

### 경로 A — 인메모리 (인프라 불필요)

Java와 Maven만 있으면 됩니다. 아래 네 단계를 복사하면 앱이 시작되고 클라이언트 IP별로
분당 5건 제한이 적용됩니다.

#### A-1. 의존성 추가

```xml
<!-- Spring Boot 3.x (Java 17+) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

#### A-2. `application.yml` 설정

```yaml
# application.yml
fluxgate:
  redis:
    enabled: false          # Redis 불필요
  ratelimit:
    mode: IN_MEMORY         # 인스턴스별 토큰 버킷 (단일 프로세스에 적합)
    default-rule-set-id: api-limits
    include-patterns:
      - /api/**
    exclude-patterns:
      - /actuator/**
  reload:
    enabled: false          # 이 모드에서는 핫 리로드 불필요
```

#### A-3. 필터 활성화 및 규칙 정의

```java
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.annotation.EnableFluxgateFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableFluxgateFilter                          // Rate Limit 필터 활성화
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    public RateLimitRuleSetProvider ruleSetProvider() {
        RateLimitRule rule = RateLimitRule.builder("per-ip")
            .scope(LimitScope.PER_IP)
            .ruleSetId("api-limits")
            .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 5)
                .label("5-per-60s")
                .build())
            .build();

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("api-limits")
            .rules(List.of(rule))
            .keyResolver(new LimitScopeKeyResolver())  // 필수
            .build();

        return id -> "api-limits".equals(id) ? Optional.of(ruleSet) : Optional.empty();
    }
}
```

#### A-4. 동작 확인

앱을 시작한 뒤 실행:

```bash
for i in $(seq 1 7); do
    printf "Request %d: " $i
    curl -s -w "HTTP %{http_code}\n" -o /tmp/body_$i.txt http://localhost:8080/api/hello
done

# 예상 출력 (분당 5건 제한):
# Request 1: HTTP 200
# Request 2: HTTP 200
# Request 3: HTTP 200
# Request 4: HTTP 200
# Request 5: HTTP 200
# Request 6: HTTP 429     ← Rate Limited
# Request 7: HTTP 429
```

429 응답에는 표준 Rate Limit 헤더와 RFC 9457 problem body가 포함됩니다:

```
HTTP/1.1 429
X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0
X-RateLimit-Reset: <epoch-seconds>
RateLimit-Limit: 5
RateLimit-Remaining: 0
RateLimit-Reset: 60
RateLimit-Policy: 5;w=60
Retry-After: 12
Content-Type: application/problem+json;charset=UTF-8

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 12 seconds","retryAfterMillis":11923}
```

> **검증 완료** — 위 출력은 0.4 개발 브랜치(`feature/review-overhaul`)로 빌드한 샘플 앱을 실제로
> 실행해 캡처했습니다. 실제로 보게 되는 수치는 규칙에 따라 달라집니다.

---

### 경로 B — Redis (분산, 프로덕션용)

#### B-1. Redis 시작

```bash
# docker/redis-standalone.yml이 프로젝트에 포함되어 있습니다 (포트는 127.0.0.1에만 바인딩)
docker compose -f docker/redis-standalone.yml up -d
```

#### B-2. 의존성 추가

```xml
<!-- Boot 3 스타터 -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.4.0</version>
</dependency>
<!-- Redis Rate Limiter (Lua 기반, 원자적) -->
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-redis-ratelimiter</artifactId>
    <version>0.4.0</version>
</dependency>
```

#### B-3. `application.yml` 설정

경로 A-3의 프로그래매틱 `ruleSetProvider` 빈을 그대로 유지하거나, MongoDB로 동적 규칙을 사용하세요 (아래 참고). yml에서 Redis만 활성화하면 됩니다:

```yaml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379
  ratelimit:
    mode: AUTO              # Redis 활성화 시 Redis 사용, 아니면 인메모리 폴백
    default-rule-set-id: api-limits
    failure-behavior: DENY
    missing-rule-behavior: DENY
    include-patterns:
      - /api/**
    exclude-patterns:
      - /actuator/**
```

#### B-4 (선택) — MongoDB 동적 규칙

프로그래매틱 `ruleSetProvider` 빈으로 충분하면 이 단계는 건너뛰세요.

```bash
# MongoDB 시작 (credentials는 docker/mongo.yml 기준)
docker compose -f docker/mongo.yml up -d
```

Maven 의존성 추가:

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-mongo-adapter</artifactId>
    <version>0.4.0</version>
</dependency>
```

`application.yml`에 MongoDB 설정 추가:

```yaml
fluxgate:
  mongo:
    enabled: true
    # docker/mongo.yml 자격증명 — 실제 배포에서는 변경하세요
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    ddl-auto: create        # 최초 시작 시 인덱스 생성
```

**규칙 시딩** (매칭 규칙이 없으면 아무것도 제한되지 않습니다):

```bash
# mongosh 사용 (https://www.mongodb.com/try/download/shell 에서 설치)
mongosh "mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin" --eval '
db.rate_limit_rules.insertOne({
  id: "per-ip",
  name: "Default IP rule",
  enabled: true,
  ruleSetId: "api-limits",
  scope: "PER_IP",
  onLimitExceedPolicy: "REJECT_REQUEST",
  bands: [{ capacity: NumberLong(10), windowMs: NumberLong(60000) }]
})
'
```

또는 `fluxgate-samples/fluxgate-sample-mongo`의 프로그래매틱 시더를 사용하세요
([samples/README](fluxgate-samples/README.md) 참고).

#### B-5. 필터 활성화

경로 A-3의 `@EnableFluxgateFilter` 애노테이션 그대로 사용. MongoDB 어댑터가 클래스패스에 있고
`fluxgate.mongo.enabled=true`이면 스타터가 `MongoRuleSetProvider`를 자동 등록합니다 — 별도
`ruleSetProvider` 빈 불필요.

#### B-6. 테스트

경로 A-4의 `curl` 루프와 동일. 차이점은 이제 동일한 Redis에 연결된 모든 인스턴스가 제한을 공유한다는 것입니다.

---

### `include-patterns` 및 핫 리로드 참고사항

`application.yml`의 `include-patterns`, `exclude-patterns`, `filter-order`, `default-rule-set-id`는
대응하는 `@EnableFluxgateFilter` 애트리뷰트보다 **우선**합니다. `/*`는 경로 세그먼트 **하나**만
매칭한다는 점에 주의하세요. 중첩 경로에는 `/**`를 사용해야 합니다.

FluxGate 자동 설정은 기본이 fail-closed입니다(`failure-behavior: DENY`, `missing-rule-behavior: DENY`).
Limiter 실패나 룰셋 없음은 클라이언트가 너무 많은 요청을 보낸 것이 아니므로 429가 아니라
**503 Service Unavailable**로 응답합니다.
전달된 클라이언트 IP 헤더는 `fluxgate.ratelimit.trust-client-ip-header=true`를 설정하지 않는 한 무시됩니다.
이때 `fluxgate.ratelimit.trusted-proxies`에 신뢰 프록시 CIDR도 함께 지정하세요.

테스트에서는 `fluxgate-testkit`이 인메모리 배선을 `FluxgateInMemoryExtension`과 `FluxgateTestRules`로
포장해 제공합니다. [fluxgate-testkit/README.md](fluxgate-testkit/README.md)를 참고하세요.

## 배포 패턴

### 패턴 1: Redis 직접 연결

각 애플리케이션 인스턴스가 Redis에 직접 연결하는 단순한 배포에 적합합니다.

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

전용 Rate Limiting 서비스를 두는 마이크로서비스 아키텍처에 적합합니다. FluxGate는 HTTP 핸들러를
기본 제공하지 않습니다. 클라이언트 측은 직접 작성하는 `FluxgateRateLimitHandler`이며,
`fluxgate-samples/fluxgate-sample-filter`의 `HttpRateLimitHandler`가 그 예시입니다.

```
┌─────────────┐     ┌─────────────────┐     ┌─────────────┐
│   App #1    │────▶│                 │     │             │
├─────────────┤     │  Rate Limit     │────▶│    Redis    │
│   App #2    │────▶│  Service (8082) │     │             │
├─────────────┤     │                 │     │             │
│   App #N    │────▶│                 │     │             │
└─────────────┘     └─────────────────┘     └─────────────┘
```

```java
@SpringBootApplication
@EnableFluxgateFilter(handler = HttpRateLimitHandler.class)
public class ClientApplication { }
```

```yaml
# 클라이언트 애플리케이션 설정. fluxgate.api.url은 샘플 핸들러가 읽는 값이며
# 라이브러리가 읽는 프로퍼티가 아닙니다.
fluxgate:
  api:
    url: http://rate-limit-service:8082
```

## 샘플 애플리케이션

| 샘플 | 포트 | 설명 |
|------|------|------|
| **fluxgate-sample-standalone-java21** | 8085 | MongoDB + Redis 직접 통합 풀스택. `@RateLimit` 애스펙트 포함 |
| **fluxgate-sample-standalone-java11** | 8085 | 동일한 스택의 Java 11 / Spring Boot 2.7 버전 |
| **fluxgate-sample-redis** | 8082 | Redis 백엔드 Rate Limit 서비스 |
| **fluxgate-sample-mongo** | 8081 | MongoDB 규칙 관리 |
| **fluxgate-sample-filter** | 8083 | HTTP로 Rate Limit을 조회하는 자동 필터 클라이언트 앱 |
| **fluxgate-sample-api** | 8080 | Rate Limit 조회용 REST API |

### 샘플 실행

샘플은 FluxGate 모듈의 현재 `SNAPSHOT`에 의존합니다. 갓 클론한 저장소의 로컬 Maven 저장소에는 아직
이 버전이 없으므로, 처음 `spring-boot:run`을 실행하기 전에 한 번 설치하세요. 프로젝트 전체를
설치하거나(JDK 21 필요), 샘플마다 의존하는 모듈만 설치합니다(`-pl <sample> -am`). 샘플별로 필요한
JDK는 [Build Once](fluxgate-samples/README.md#build-once)를 참고하세요.

```bash
# 아래 두 샘플에 필요한 모듈 설치 (JDK 17 이상)
./mvnw -B install -DskipTests -pl fluxgate-samples/fluxgate-sample-redis,fluxgate-samples/fluxgate-sample-filter -am

# 인프라 시작 (로컬 개발 전용. 포트는 127.0.0.1에만 바인딩됩니다)
docker compose -f docker/redis-standalone.yml -f docker/mongo.yml up -d

# Rate Limit 서비스 시작
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-redis

# 클라이언트 애플리케이션 시작 (다른 터미널에서)
./mvnw spring-boot:run -pl fluxgate-samples/fluxgate-sample-filter

# Rate Limiting 테스트
curl http://localhost:8083/api/hello
```

## 설정 레퍼런스

아래 기본값은 모두 `FluxgateProperties`와 `FluxgateResilienceProperties`의 실제 값입니다.
각 키의 상세 설명은 Spring Boot 3 스타터
[README](fluxgate-spring-boot3-starter/README.md)에 있습니다.

### Redis / MongoDB

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `fluxgate.redis.enabled` | `false` | Redis Rate Limiter 활성화 |
| `fluxgate.redis.uri` | `redis://localhost:6379` | Redis 연결 URI (클러스터는 쉼표로 호스트 구분) |
| `fluxgate.redis.mode` | `auto` | `standalone`, `cluster`, `auto` (자동 감지) |
| `fluxgate.redis.timeout-ms` | `5000` | 커맨드 타임아웃 |
| `fluxgate.redis.fail-fast` | `false` | Redis 연결 실패 시 기동 실패. 기본값은 지연 연결 + 백그라운드 재연결 |
| `fluxgate.redis.max-bucket-ttl` | `7d` | 버킷 TTL 상한. 긴 윈도에서 위조 가능한 신원 키가 점유할 수 있는 Redis 메모리를 제한합니다 |
| `fluxgate.mongo.enabled` | `false` | MongoDB 어댑터 활성화 |
| `fluxgate.mongo.uri` | `mongodb://localhost:27017/fluxgate` | MongoDB 연결 URI. 인증이 필요한 경우 `mongodb://user:pass@host:27017/db?authSource=admin` 형식 사용 (빠른 시작의 docker/mongo.yml 자격증명 참고) |
| `fluxgate.mongo.database` | `fluxgate` | MongoDB 데이터베이스명 |
| `fluxgate.mongo.rule-collection` | `rate_limit_rules` | 규칙 컬렉션명 |
| `fluxgate.mongo.event-collection` | _(미설정)_ | 이벤트 컬렉션명 (선택사항) |
| `fluxgate.mongo.ddl-auto` | `validate` | `validate` 또는 `create` (아래 참고) |
| `fluxgate.mongo.event-retention` | `30d` | 이벤트 컬렉션의 TTL 인덱스. `0`이면 인덱스를 만들지 않고 보존을 직접 관리합니다 |

### Rate Limiting

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `fluxgate.ratelimit.enabled` | `true` | 마스터 스위치. `false`면 필터와 애스펙트 모두 등록되지 않습니다 |
| `fluxgate.ratelimit.mode` | `AUTO` | `AUTO`(Redis 활성 시 Redis, 아니면 인메모리), `REDIS`, `IN_MEMORY` |
| `fluxgate.ratelimit.default-rule-set-id` | _(미설정)_ | 다른 선택이 없을 때 적용할 룰셋. 기본값 없음 |
| `fluxgate.ratelimit.failure-behavior` | `DENY` | Limiter 자체 실패 시 `DENY`(503) 또는 `ALLOW`. 만들 수 없는 룰 세트는 설정 오류이므로 두 값 모두에서 503 |
| `fluxgate.ratelimit.missing-rule-behavior` | `DENY` | 룰셋을 찾지 못할 때 `DENY` 또는 `ALLOW` |
| `fluxgate.ratelimit.missing-key-behavior` | `FALLBACK_TO_IP` | 스코프 값이 없을 때 `FALLBACK_TO_IP` 또는 `REJECT` |
| `fluxgate.ratelimit.identity.source` | `PRINCIPAL` | `PRINCIPAL`, `HEADERS`, `PRINCIPAL_THEN_HEADERS`. 헤더를 쓰는 두 값은 신뢰할 수 있는 프록시를 위한 명시적 opt-in이며 기동 시 WARN을 남깁니다. 실효값은 기동 시 1회 로깅됩니다 |
| `fluxgate.ratelimit.identity.user-id-header` | `X-User-Id` | 사용자 ID를 담는 신뢰 헤더. source가 헤더를 쓸 때만 읽습니다 |
| `fluxgate.ratelimit.identity.api-key-header` | `X-API-Key` | API 키를 담는 신뢰 헤더. source가 헤더를 쓸 때만 읽습니다 |
| `fluxgate.ratelimit.include-patterns` | _(미설정, 실효값 `/**`)_ | Rate Limit 대상 경로. `@EnableFluxgateFilter#includePatterns()` → 전체 경로 순으로 폴백 |
| `fluxgate.ratelimit.exclude-patterns` | _(미설정)_ | 제외 경로. `@EnableFluxgateFilter#excludePatterns()`로 폴백 |
| `fluxgate.ratelimit.case-sensitive-patterns` | `true` | include/exclude 매칭의 대소문자 구분 여부 |
| `fluxgate.ratelimit.filter-order` | _(미설정, 실효값 `1`)_ | 필터 순서. `@EnableFluxgateFilter#filterOrder()`(=`1`)로 폴백 |
| `fluxgate.ratelimit.client-ip-header` | `X-Forwarded-For` | 신뢰할 때 조회하는 전달 헤더 |
| `fluxgate.ratelimit.trust-client-ip-header` | `false` | 전달 헤더가 `remoteAddr`를 덮어쓸 수 있는지 |
| `fluxgate.ratelimit.trusted-proxies` | `[]` | 전달 헤더를 설정할 수 있는 IP 또는 CIDR. `trust-client-ip-header=true`라면 반드시 설정하세요. 목록이 비어 있으면 최우측 유효 홉(최좌측 클라이언트 제공 값보다 위조가 어렵습니다)으로 폴백하며 기동 시 WARN 1회를 남깁니다 |
| `fluxgate.ratelimit.collect-headers` | `false` | 허용 목록에 있는 요청 헤더를 `RequestContext`에 복사 |
| `fluxgate.ratelimit.header-allowlist` | `[]` | 복사 허용 헤더명 (대소문자 무시) |
| `fluxgate.ratelimit.log-query-string` | `false` | 원본 쿼리 스트링을 로깅 MDC에 넣을지 |
| `fluxgate.ratelimit.cost-header` | _(미설정)_ | 요청 비용(permits)을 담는 헤더. 일치하는 밴드의 용량보다 큰 비용은 429(`Retry-After` 없음)로 응답하며 서킷 브레이커에 집계되지 않음 |
| `fluxgate.ratelimit.max-cost` | `1000` | `cost-header` 값 상한. `cost-header`를 설정했다면 반드시 **0보다 커야** 합니다. 예전에는 `0`이 "무제한"을 의미했습니다 |
| `fluxgate.ratelimit.rule-sets` | `[]` | YAML로 정의하는 룰셋. 먼저 조회되며, YAML에 없는 id는 MongoDB(또는 직접 만든 provider)로 폴백합니다. 스키마: [YAML 룰 세트 가이드](docs/ko/guides/yaml-rule-sets.ko.md) |
| `fluxgate.ratelimit.fail-on-missing-handler` | `false` | `true`면 제한 없이 기동하는 대신 기동을 실패시킵니다(`MissingConfigurationException`). 리미터에 룰셋 Provider가 없을 때(Redis 활성화 또는 `fluxgate.ratelimit.mode` 설정 시), 그리고 필터나 애스펙트에 `FluxgateRateLimitHandler`가 전혀 없을 때(룰 소스가 없는 기본 구성 포함)가 해당합니다. `false`면 ERROR 또는 WARN을 남기고 `failure-behavior`를 적용합니다 |
| `fluxgate.ratelimit.include-headers` | `true` | 두 헤더 계열 전체의 마스터 스위치 |
| `fluxgate.ratelimit.response.include-legacy-headers` | `true` | `X-RateLimit-Limit/Remaining/Reset` 기록 |
| `fluxgate.ratelimit.response.include-standard-headers` | `true` | `RateLimit-Limit/Remaining/Reset/Policy` 기록 |
| `fluxgate.ratelimit.response.content-type` | `application/problem+json` | 429 콘텐츠 타입 (`;charset=UTF-8` 자동 부착) |
| `fluxgate.ratelimit.response.body-template` | _(미설정)_ | problem 문서를 대체. 치환자: `{status}`, `{retryAfterSeconds}`, `{retryAfterMillis}`, `{remaining}`, `{limit}`. `unavailable-body-template`이 없으면 503에도 쓰임(`{status}` = 503, 모르는 값은 `-1`) |
| `fluxgate.ratelimit.response.unavailable-body-template` | _(미설정: `body-template`)_ | 레이트 리미팅을 쓸 수 없거나 설정이 없을 때 보내는 503의 본문. 치환자는 동일 |
| `fluxgate.ratelimit.fallback.mode` | `NONE` | `IN_MEMORY`면 Redis 장애 중에도 인스턴스별 제한 유지 |
| `fluxgate.ratelimit.fallback.max-buckets` | `100000` | 폴백 버킷 캐시 크기 |
| `fluxgate.ratelimit.fallback.expire-after-access` | `1h` | 폴백 버킷 유휴 만료 |
| `fluxgate.ratelimit.wait-for-refill.enabled` | 미설정 (`null`) | 규칙의 WAIT_FOR_REFILL 정책은 이 값이 `true`일 때만 대기합니다. `@RateLimit(waitForRefill = true)`는 이 값이 명시적으로 `false`가 아니면 대기합니다 |
| `fluxgate.ratelimit.wait-for-refill.max-wait-time-ms` | `5000` | 요청당 최대 대기 시간. `@RateLimit(maxWaitTimeMs)`의 상한이기도 합니다. 대기는 워커 스레드를 차단하므로 429 + `Retry-After`를 권장합니다 |
| `fluxgate.ratelimit.wait-for-refill.max-concurrent-waits` | `50` | 애플리케이션 전체에서 동시에 대기 가능한 요청 수 |
| `fluxgate.ratelimit.filter-enabled` | `false` | **Deprecated, 동작하지 않습니다.** `fluxgate.ratelimit.enabled`를 사용하세요 |

### 메트릭 / 헬스 / 핫 리로드

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `fluxgate.metrics.enabled` | `true` | Micrometer 메트릭 활성화 |
| `fluxgate.metrics.include-endpoint` | `true` | `endpoint` 태그 추가 |
| `fluxgate.metrics.endpoint-normalization` | `true` | 숫자/UUID/24-hex 경로 세그먼트를 `{id}`로 치환 |
| `fluxgate.metrics.max-endpoint-tags` | `1000` | `endpoint` 태그 값 개수 상한 |
| `fluxgate.actuator.health.enabled` | `true` | `fluxgate` 헬스 인디케이터 등록 |
| `fluxgate.actuator.health.degraded-http-status` | `503` | 커스텀 `DEGRADED` 헬스 상태의 HTTP 상태 코드. `0`이나 음수면 매핑을 추가하지 않습니다(Spring Boot는 200 응답). Spring Boot 자체 `DOWN` / `OUT_OF_SERVICE` = 503 기본값은 유지되며, 직접 지정한 `management.endpoint.health.status.http-mapping` 항목이 우선합니다 |
| `fluxgate.actuator.health.include-endpoint-details` | `false` | 헬스 응답에 `host:port`, 클러스터 노드 수, 실패 메시지를 포함합니다. `show-details=when_authorized` 뒤에서만 켜세요 |
| `fluxgate.reload.enabled` | `true` | 룰셋 핫 리로드 활성화 |
| `fluxgate.reload.strategy` | `AUTO` | `AUTO`, `POLLING`, `PUBSUB`, `NONE` |
| `fluxgate.reload.cache.ttl` | `5m` | 규칙 캐시 TTL |
| `fluxgate.reload.cache.max-size` | `1000` | 규칙 캐시 크기 |
| `fluxgate.reload.cache.negative-ttl` | `5s` | "룰셋 없음" 결과를 캐시하는 시간 (`0`이면 비활성) |
| `fluxgate.reload.polling.interval` | `30s` | 폴링 주기 |
| `fluxgate.reload.polling.initial-delay` | `10s` | 첫 폴링까지의 지연 |
| `fluxgate.reload.pubsub.channel` | `fluxgate:rule-reload` | Pub/Sub 채널 |
| `fluxgate.reload.pubsub.retry-on-failure` | `true` | Pub/Sub 구독이 실패하면 재시도 |
| `fluxgate.reload.pubsub.retry-interval` | `5s` | 구독 재시도 간격 |
| `fluxgate.reload.pubsub.backstop-polling-interval` | `60s` | Pub/Sub 뒤에서 도는 폴링 백스톱 (`0`이면 비활성) |
| `fluxgate.reload.pubsub.secret` | _(미설정)_ | 공유 HMAC-SHA256 시크릿. Pub/Sub에 필수입니다. 없으면 `AUTO`는 폴링으로 대체(WARN)되고 명시적 `PUBSUB`는 기동이 실패합니다. 서명이 유효하지 않은 모든 리로드 메시지(레거시 `"*"` 포함)는 WARN 후 무시됩니다. 컨트롤 플레인의 `fluxgate.control.secret`과 동일해야 합니다 |
| `fluxgate.reload.pubsub.allow-unsigned` | `false` | 개발용 탈출구. `secret` 없이 기동하고 서명 없는 메시지를 따릅니다(기동 시 WARN). `secret`이 있으면 무시됩니다 |
| `fluxgate.reload.pubsub.accept-legacy-signed` | `true` | 0.4 이전 발행자가 보낸 서명된 스키마 버전 1 메시지(nonce·채널 바인딩 없음)를 받아들입니다. 처음 받아들일 때 WARN을 한 번 남깁니다. 모든 발행자가 버전 2로 서명하면 `false`로 바꾸세요. `secret`을 설정할 때만 적용됩니다 |
| `fluxgate.reload.pubsub.max-message-age` | `60s` | 서명된 메시지가 무시되기까지의 최대 경과 시간(리플레이 윈도). 윈도 안에서 이미 본 nonce도 무시됩니다. `secret`을 설정할 때만 적용됩니다. 0.4.0 이전에는 프로퍼티 기본값이 `5m`이었습니다 |

### 복원력

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `fluxgate.resilience.retry.enabled` | `true` | 실패한 Limiter 호출 재시도 |
| `fluxgate.resilience.retry.max-attempts` | `3` | 총 시도 횟수 |
| `fluxgate.resilience.retry.initial-backoff` | `100ms` | 최초 백오프 |
| `fluxgate.resilience.retry.multiplier` | `2.0` | 백오프 배수 |
| `fluxgate.resilience.retry.max-backoff` | `2s` | 백오프 상한 |
| `fluxgate.resilience.retry.jitter-factor` | `0.2` | 상한 적용 후 ±20% 지터 |
| `fluxgate.resilience.retry.retry-on-timeout` | `false` | 타임아웃을 재시도할지 |
| `fluxgate.resilience.circuit-breaker.enabled` | `true` | 서킷 브레이커 활성화. 재시도만 켜져 있으면 장애 중인 의존성에 부하를 3배로 키우면서 멈출 장치가 없습니다 |
| `fluxgate.resilience.circuit-breaker.sliding-window-size` | `20` | 실패율 윈도의 호출 수 |
| `fluxgate.resilience.circuit-breaker.failure-rate-threshold` | `50` | 서킷을 여는 실패율(%) |
| `fluxgate.resilience.circuit-breaker.minimum-number-of-calls` | `10` | 실패율 평가에 필요한 최소 호출 수 |
| `fluxgate.resilience.circuit-breaker.failure-threshold` | _(미설정)_ | 레거시 연속 실패 임계값. 명시적으로 설정할 때만 적용 |
| `fluxgate.resilience.circuit-breaker.wait-duration-in-open-state` | `30s` | HALF_OPEN 시도까지의 대기 시간 |
| `fluxgate.resilience.circuit-breaker.permitted-calls-in-half-open-state` | `3` | 허용되는 동시 시험 호출 수 |
| `fluxgate.resilience.circuit-breaker.fallback` | `FAIL_OPEN` | **Deprecated, 동작하지 않습니다.** 동작은 전달하는 fallback이 결정합니다 |

### 컨트롤 플레인 (`fluxgate-control-support`)

룰 변경 알림을 발행하는 모듈이 읽습니다. `fluxgate.control.redis.uri`를 명시적으로 설정해야만 notifier가
생성됩니다.

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `fluxgate.control.redis.uri` | `redis://localhost:6379` | 알림을 발행할 Redis (클러스터는 쉼표로 구분). 설정하면 notifier가 활성화됩니다 |
| `fluxgate.control.redis.channel` | `fluxgate:rule-reload` | Pub/Sub 채널. 데이터 플레인의 `fluxgate.reload.pubsub.channel`과 같아야 합니다. 다른 채널용으로 서명된 메시지는 무시됩니다 |
| `fluxgate.control.redis.timeout` | `5s` | 연결 타임아웃 |
| `fluxgate.control.source` | `fluxgate-control` | 각 메시지에 담기는 발신 식별자 |
| `fluxgate.control.secret` | _(미설정)_ | HMAC-SHA256 서명 시크릿. 필수이며 없으면 notifier가 기동을 거부합니다. 데이터 플레인의 `fluxgate.reload.pubsub.secret`과 같아야 합니다 |
| `fluxgate.control.allow-unsigned` | `false` | 개발용 탈출구. `secret`이 없을 때 서명 없이 발행합니다(WARN). `secret`이 있으면 무시됩니다 |

메시지는 감싸고 있는 트랜잭션이 커밋된 뒤에 발행되며 롤백되면 발행되지 않습니다. 데이터 플레인을
컨트롤 플레인보다 먼저 롤아웃하세요. 메시지는 스키마 버전 2이며, 버전 1만 아는 데이터 플레인은 폴링 백스톱이
따라잡을 때까지 이를 버립니다.

단독(standalone) Redis에서는 notifier가 메시지마다 수신한 구독자 수를 보고하고, 아무도 받지 않으면(대개
채널 불일치) WARN을 한 번 남깁니다. Redis Cluster에서는 `PUBLISH` 응답이 명령을 처리한 노드의 구독자만
세므로 수신자 수를 알 수 없음(`-1`)으로 보고하고 그 WARN도 남기지 않습니다. 대신 채널 이름과 데이터
플레인의 리로드 메트릭을 확인하세요.

### YAML 룰 세트

`fluxgate.ratelimit.rule-sets`는 `application.yml`에서 룰 세트를 정의합니다. 경로 / 메서드 / 헤더
매칭, 세 가지 알고리즘, 달력 쿼터, 접근 제어를 지원하며 데이터베이스가 필요 없습니다:

```yaml
fluxgate:
  ratelimit:
    default-rule-set-id: api-limits
    rule-sets:
      - id: api-limits
        rules:
          - id: per-ip-100rpm
            scope: PER_IP
            matcher:
              path-patterns: [/api/**]
              methods: [GET, POST]
            bands:
              - capacity: 100
                window: 60s
```

모든 룰 세트는 기동 시점에 만들어지고 검증됩니다. 모든 키는
[YAML 룰 세트 가이드](docs/ko/guides/yaml-rule-sets.ko.md)를 참고하세요.

### MongoDB DDL Auto 모드

`fluxgate.mongo.ddl-auto` 프로퍼티는 FluxGate가 MongoDB 컬렉션을 어떻게 다루는지 제어합니다:

| 모드 | 설명 |
|------|------|
| `validate` | (기본값) 컬렉션이 존재하고 `{ruleSetId: 1, id: 1}`에 대한 유니크 인덱스(이름은 무관)가 있는지 **함께** 검증합니다. 그 인덱스는 `sparse`가 아니고 `partialFilterExpression`이 없으며 `simple` 외의 콜레이션이 없어야 합니다. 없으면 `createIndex` 명령과 함께 기동이 실패합니다. |
| `create` | 컬렉션이 없으면 생성하고 `MongoRateLimitRuleRepository#ensureIndexes()`로 `ruleSetId_1_id_1_unique`(유니크)와 `id_1` 인덱스를 만듭니다. 중복 `(ruleSetId, id)` 쌍이나 충돌하는 인덱스가 있으면 해당 쌍을 나열하며 **기동이 실패**합니다. |

`validate`를 쓰는 기존 배포를 업그레이드하기 전에 인덱스를 직접 만드세요(자세한 내용은
[마이그레이션 가이드](docs/ko/operations/migration-0.4.ko.md#161-mongodb-고유-룰-인덱스와-룰-식별-breaking-28-29)):

```javascript
db.rate_limit_rules.createIndex({ ruleSetId: 1, id: 1 }, { unique: true, name: "ruleSetId_1_id_1_unique" })
```

**설정 예시:**

```yaml
fluxgate:
  mongo:
    enabled: true
    # docker/mongo.yml 자격증명 — 실제 배포에서는 변경하세요
    uri: mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
    database: fluxgate
    rule-collection: my_rate_limit_rules    # 커스텀 컬렉션명
    event-collection: my_rate_limit_events  # 선택: 이벤트 로깅 활성화
    ddl-auto: create                        # 컬렉션과 인덱스 자동 생성
```

### Rate Limit 규칙 설정

```java
RateLimitRule rule = RateLimitRule.builder("api-rule")
    .name("API Rate Limit")
    .enabled(true)
    .scope(LimitScope.PER_IP)  // GLOBAL, PER_IP, PER_USER, PER_API_KEY 또는 CUSTOM
    .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)  // 또는 WAIT_FOR_REFILL
    .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10)
        .label("10-per-second")
        .build())
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 100)
        .label("100-per-minute")
        .build())
    .ruleSetId("api-limits")
    .attribute("tier", "standard")  // 추적용 커스텀 속성
    .build();
```

대역 라벨은 선택사항입니다. 생략하면 FluxGate가 `<capacity>-per-<windowSeconds>s`(예: `100-per-60s`),
1초 미만 윈도에서는 `<capacity>-per-<millis>ms`(예: `500-per-250ms`)를 유도해 버킷 키 세그먼트로
사용하므로, 같은 규칙의 두 대역이 하나의 버킷을 공유하는 일이 없습니다.
유도된 라벨이 충돌하는 두 대역은 `build()`에서 `InvalidRuleConfigException`으로 거부됩니다.
라벨을 바꾸면 해당 대역의 버킷이 이동하므로 한 번 초기화됩니다.

### LimitScope 옵션

| LimitScope | 키 출처 | 해석된 키 |
|------------|---------|-----------|
| `GLOBAL` | 상수 | `global` |
| `PER_IP` | `RequestContext.clientIp` | `ip:192.168.1.100` |
| `PER_USER` | `RequestContext.userId` | `user:user-123` |
| `PER_API_KEY` | `RequestContext.apiKey` | `key:abc123` |
| `CUSTOM` | `attributes.get(keyStrategyId)` | `custom:<value>` |

키 값은 단사(injective)로 새니타이즈됩니다. `[A-Za-z0-9._:@-]` 문자만으로 된 256자 이하이고 `h:`로
시작하지 않는 값은 그대로 두고, 그 밖의 값은 `h:<제한된 값>:<SHA-256 앞 16자리 16진수>`가 되며
(`a+1`은 `h:a_1:<16 hex>`), 이 형태가 256자를 넘으면(237자를 넘는 값) `h:<64 hex>`가 됩니다. 스코프 접두사는 해시 바깥에 남아(`user:h:<64 hex>`) 서로 다른 두 신원이 버킷을
공유하는 일이 없습니다. 스코프가 요구하는 값이 없을 때는
`fluxgate.ratelimit.missing-key-behavior`가 클라이언트 IP 폴백(이때 키는 실제 출처를 반영해 `ip:`
접두사를 갖습니다)과 요청 거부 중 하나를 결정합니다.

### 복합 키 예시 (IP + 사용자)

IP와 사용자 조합으로 세밀하게 Rate Limit을 적용하려면:

```java
// CUSTOM 스코프 규칙
RateLimitRule rule = RateLimitRule.builder("composite-rule")
    .name("IP+User Rate Limit")
    .scope(LimitScope.CUSTOM)
    .keyStrategyId("ipUser")  // context.attributes.get("ipUser") 조회
    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
    .build();

// RequestContextCustomizer가 복합 키를 만듭니다
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        String userId = request.getHeader("X-User-Id");
        String clientIp = request.getRemoteAddr();

        // 각 구성 요소에 접두사를 붙여 모호성을 없앱니다: "ip:192.168.1.100:user:user-123"
        String compositeKey = userId != null
            ? "ip:" + clientIp + ":user:" + userId
            : "ip:" + clientIp;
        builder.attribute("ipUser", compositeKey);

        return builder;
    };
}
```

멀티 테넌트 시스템에서는 `X-Tenant-Id`를 `RequestContext`에 복사하기 전에 애플리케이션 경계에서
검증하세요. 안전한 커스텀 키는 테넌트와 주체를 모두 포함해야 하며(예: `tenantId + ":" + userId`),
그래야 한 테넌트가 다른 테넌트의 버킷을 소모하거나 초기화할 수 없습니다.

### RequestContext 커스터마이징

```java
@Bean
public RequestContextCustomizer requestContextCustomizer() {
    return (builder, request) -> {
        // PER_USER 스코프용 userId 설정
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            builder.userId(userId);
        }

        // PER_API_KEY 스코프용 apiKey 설정
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null) {
            builder.apiKey(apiKey);
        }

        // Cloudflare 헤더로 클라이언트 IP 재정의
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null) {
            builder.clientIp(cfIp);
        }

        // keyStrategyId="tenantId"인 CUSTOM 스코프용 테넌트 정보 추가
        builder.attribute("tenantId", request.getHeader("X-Tenant-Id"));
        return builder;
    };
}
```

> **보안 주의.** `PER_USER`와 `PER_API_KEY`는 `RequestContext.userId` / `apiKey`를 읽고,
> `HEADERS` 모드에서는 그 값이 클라이언트가 제어하는 `X-User-Id`, `X-API-Key` 요청 헤더에서
> 옵니다. 즉 호출자가 어떤 버킷을 소모할지 직접 고를 수 있습니다. 0.4부터
> `fluxgate.ratelimit.identity.source` 기본값은 `PRINCIPAL`이며, 인증된 principal만 쓰고 헤더는
> 무시합니다. `HEADERS`와 `PRINCIPAL_THEN_HEADERS`는 신뢰할 수 있는 프록시가 헤더를 설정·제거하는
> 배포를 위한 명시적 opt-in이고 기동 시 WARN을 남깁니다. 더 복잡한 경우에는
> `RequestContextCustomizer`가 탈출구입니다.
> [docs/ko/customization/request-context.ko.md](docs/ko/customization/request-context.ko.md)와
> [SECURITY.md](SECURITY.md)를 참고하세요.

## 응답 헤더

FluxGate는 허용된 응답에도 Rate Limit 헤더를 기록합니다. 그래야 잘 구현된 클라이언트가 429를 받은
뒤에야 제한을 알게 되는 대신 스스로 속도를 조절할 수 있습니다. Limiter가 "알 수 없음"(`-1`)으로
보고한 값은 오해를 낳는 숫자로 쓰는 대신 헤더 자체를 생략합니다.

| 헤더 | 계열 | 의미 |
|------|------|------|
| `X-RateLimit-Limit` | 레거시 | 결정을 만든 대역의 용량 |
| `X-RateLimit-Remaining` | 레거시 | 해당 대역의 남은 토큰 |
| `X-RateLimit-Reset` | 레거시 | 해당 대역이 리셋되는 epoch **초**. TOKEN_BUCKET은 다시 가득 찰 때, SLIDING_WINDOW는 세어진 요청이 모두 윈도를 떠날 때, FIXED_WINDOW는 윈도 끝 |
| `RateLimit-Limit` | IETF | 결정을 만든 대역의 용량 |
| `RateLimit-Remaining` | IETF | 해당 대역의 남은 토큰 |
| `RateLimit-Reset` | IETF | 해당 대역이 리셋되기까지의 **잔여 초** (알고리즘별 의미는 위와 같음) |
| `RateLimit-Policy` | IETF | 쿼터 정책 (예: `100;w=60`). 윈도가 불명확하거나 1초 미만이면 생략 |
| `Retry-After` | 항상 | 거부 시에만. 두 계열을 모두 꺼도 기록됩니다. 초 단위 올림, 절대 `0`이 되지 않습니다 |

두 계열은 `fluxgate.ratelimit.response.include-legacy-headers`와
`response.include-standard-headers`로 독립적으로 켜고 끌 수 있으며,
`fluxgate.ratelimit.include-headers=false`로 둘 다 끌 수 있습니다.

거부된 요청은 RFC 9457 problem 문서를 받습니다:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json;charset=UTF-8
RateLimit-Limit: 100
RateLimit-Remaining: 0
RateLimit-Reset: 27
RateLimit-Policy: 100;w=60
Retry-After: 27

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit exceeded, retry after 27 seconds","retryAfterMillis":26340}
```

본문은 `fluxgate.ratelimit.response.body-template`로 바꿀 수 있고,
`org.fluxgate.spring.filter.RateLimitResponseWriter` 빈으로 완전히 대체할 수 있습니다.

## 관측성

FluxGate는 별도 설정 없이 관측성 기능을 제공합니다.

### 구조화된 로깅

FluxGate는 ELK Stack이나 Splunk 같은 로그 집계 시스템과 쉽게 통합할 수 있도록 상관관계 ID가 포함된
MDC를 채웁니다.

```json
{
  "timestamp": "2025-01-15T10:30:45.123Z",
  "level": "DEBUG",
  "logger": "org.fluxgate.spring.filter.FluxgateRateLimitFilter",
  "message": "Request completed",
  "traceId": "abc123-def456",
  "ruleSetId": "api-limits",
  "endpoint": "/api/test",
  "method": "GET",
  "clientIp": "192.168.1.100",
  "rateLimitAllowed": "true",
  "remainingTokens": "9",
  "statusCode": "200",
  "durationMs": "3"
}
```

필터가 채우는 SLF4J MDC 키는 다음과 같습니다(상수는
`org.fluxgate.core.constants.FluxgateConstants.MdcKeys`): `traceId`, `ruleSetId`, `method`,
`endpoint`, `clientIp`, `protocol`, `serverPort`, `userAgent`, `referer`, `userId`,
`apiKey`(마스킹), `rateLimitAllowed`, `remainingTokens`, `retryAfterMs`, `statusCode`,
`durationMs`, `error`, `errorMessage`, 그리고 `fluxgate.ratelimit.log-query-string=true`일 때
`queryString`.

FluxGate는 logback 설정을 동봉하지 **않습니다**. MDC를 JSON으로 만들려면 직접 인코더를 지정하세요.
예를 들어 [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder):

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>
```

요청 헤더에서 유도한 값은 MDC에 들어가기 전에 제어 문자가 제거되고 길이가 제한되며, API 키는
마스킹됩니다. 요청이 끝나면 FluxGate가 진입 시점에 발견한 MDC 항목이 복원됩니다.

### Prometheus 메트릭

클래스패스에 `spring-boot-starter-actuator`가 있으면 FluxGate가 Micrometer 기반 메트릭을 자동
노출합니다.

Limiter 실패는 `rule_set`, `endpoint`, `action`, `exception` 태그와 함께
`fluxgate.limiter.failures`로 노출됩니다. 프로덕션에서는 `action=fail_open`이 0이 아닌 경우를
경보하고, `action=fail_closed`는 의존성 장애 신호로, `action=fallback_in_memory`는 "지금 제한이
인스턴스별로 적용되고 있다"는 신호로 추적하세요.

**제공 메트릭:**

| 메트릭 | 타입 | 설명 |
|--------|------|------|
| `fluxgate_requests_total` | Counter | Rate Limit 결정. `result=allowed\|rejected` 태그와 `rule_set`, `endpoint`, `method` 포함 |
| `fluxgate_requests_duration_seconds` | Timer | 결정에 소요된 시간 |
| `fluxgate_limiter_failures_total` | Counter | `action`, `exception`별 Limiter 의존성 실패 |
| `fluxgate_tokens_remaining` | Gauge | 버킷의 남은 토큰 |
| `fluxgate_limiter_bucket_evictions_total` | Counter | 크기나 유휴 만료로 축출된 인메모리 버킷. 축출될 때마다 해당 키의 쿼터가 가득 찬 상태로 초기화됩니다 |

미터 이름은 `fluxgate.requests`이며 Prometheus가 Counter에 `_total`을 붙입니다. 이전 버전이 함께
등록했던 태그 없는 `fluxgate.requests.total` 미터는 **제거**되었습니다. Prometheus 이름이 같아
충돌했기 때문입니다. 대신 `result` 태그로 합산하세요.

**Prometheus 출력 예시:**

```
# HELP fluxgate_requests_total FluxGate rate limit counter
# TYPE fluxgate_requests_total counter
fluxgate_requests_total{endpoint="/api/test",method="GET",result="allowed",rule_set="api-limits"} 42.0
fluxgate_requests_total{endpoint="/api/test",method="GET",result="rejected",rule_set="api-limits"} 3.0

# HELP fluxgate_tokens_remaining
# TYPE fluxgate_tokens_remaining gauge
fluxgate_tokens_remaining{endpoint="/api/test",rule_set="api-limits"} 8.0
```

`endpoint` 값은 정규화되고(`/api/users/42` → `/api/users/{id}`), 서로 다른 값의 개수는
`fluxgate.metrics.max-endpoint-tags`로 제한되므로 경로 파라미터가 많은 API가 미터 레지스트리를
무한히 키우지 못합니다.

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

**동봉된 대시보드와 알림:**

| 자산 | 위치 |
|------|------|
| Grafana 대시보드 (10개 패널) | [`docker/grafana/fluxgate-dashboard.json`](docker/grafana/fluxgate-dashboard.json) |
| Prometheus recording rule + 알림 | [`docker/prometheus/fluxgate-alerts.yml`](docker/prometheus/fluxgate-alerts.yml) |

대시보드 JSON을 Grafana에 임포트하고 `datasource` 변수를 Prometheus로 지정하세요. 알림 파일은
`prometheus.yml`의 `rule_files`로 로드합니다.

### 헬스

`fluxgate` 헬스 인디케이터는 Redis / MongoDB 의존성 상태, 설정된 `failureBehavior` /
`missingRuleBehavior`, 그리고 필터·애스펙트 빈이 실제로 존재하는지를 보고합니다.

의존성에 문제가 있으면 커스텀 상태 **`DEGRADED`**를 보고하며, FluxGate는 이를 기본적으로
**HTTP 503**으로 매핑하므로 이 엔드포인트를 검사하는 로드밸런서가 인스턴스를 로테이션에서 뺍니다.
Spring Boot의 기본 상태 순서는 `DEGRADED`를 모르기 때문에 집계 결과를 `UP`으로 만들므로, FluxGate는
`management.endpoint.health.status.order=down,out-of-service,degraded,up,unknown`도 함께 추가합니다.
그래서 루트 `/actuator/health`와 (`fluxgate`를 포함한) readiness 그룹도 `DEGRADED`를 보고하고 503을
응답합니다. `fluxgate.actuator.health.degraded-http-status`로 코드를 바꿀 수 있습니다(`0`이면 매핑을
Spring Boot에 맡기며 200을 응답합니다). FluxGate는 Spring Boot 자체 `DOWN`, `OUT_OF_SERVICE` = 503 옆에
최저 우선순위 프로퍼티 기본값으로 순서와 매핑을 추가하므로, 직접 지정한
`management.endpoint.health.status.order`나 `http-mapping` 항목이 여전히 우선합니다. 직접 지정한 순서에
`degraded`가 없으면 기동 시 WARN으로 알려 줍니다. `cluster_state`가 `ok`가 아니거나 실패한 슬롯이 있거나
토폴로지를 읽을 수 없는 Redis Cluster는 `DOWN`입니다.

Rate Limit 자체를 사용할 수 없을 때 필터는 429가 아니라 **503**으로 응답합니다(`Retry-After`는 대기
시간을 알 때만). `failure-behavior`와 `missing-rule-behavior`를 참고하세요.

`/actuator/health/fluxgate`에서 `DEGRADED`와 `dependencyIssues=true`를 모니터링하세요.

## 소스에서 빌드하기

```bash
# 저장소 클론
git clone https://github.com/OpenFluxGate/fluxgate.git
cd fluxgate

# 전체 모듈 빌드 (JDK 21 필요: Boot 3 스타터는 Java 17,
# fluxgate-sample-standalone-java21은 Java 21을 대상으로 합니다)
./mvnw clean install

# 테스트 없이 빌드
./mvnw clean install -DskipTests
```

### 테스트 계층

FluxGate는 테스트를 두 계층으로 나눕니다. 그래서 인프라 없이도 클린 체크아웃이 완전히 테스트됩니다:

```bash
# 단위 테스트만 - Docker, Redis, MongoDB 불필요
./mvnw test

# 단위 테스트 + 통합 테스트 (surefire + failsafe)
./mvnw verify

# 통합 테스트 명시적 생략
./mvnw verify -DskipITs

# Redis Cluster 통합 테스트 (옵트인 프로파일)
./mvnw -pl fluxgate-redis-ratelimiter -am -Predis-cluster-it verify
```

통합 테스트는 `*IntegrationTest` 또는 `*IT`로 끝나는 클래스입니다. Redis와 MongoDB를 다음 순서로
확보합니다:

1. 환경변수로 공급된 URI,
2. 일회용 [Testcontainers](https://testcontainers.com/) 컨테이너,
3. **건너뜀** — 둘 다 없으면 JUnit assumption으로 중단되며, 실패하지 않습니다.

| 변수 | 용도 |
|------|------|
| `FLUXGATE_REDIS_URI` | Testcontainers 대신 기존 Redis 사용 (예: `redis://localhost:6379`) |
| `FLUXGATE_MONGO_URI` | 기존 MongoDB 사용 (예: `mongodb://user:pass@localhost:27017/fluxgate?authSource=admin`) |
| `FLUXGATE_MONGO_DB` | `FLUXGATE_MONGO_URI`와 함께 사용할 데이터베이스명 |

통합 테스트는 만드는 모든 키와 컬렉션에 JVM별 run id를 붙이고 자기가 만든 데이터만 정리하므로,
공유 개발 서버를 가리켜도 안전합니다.

## 한계

FluxGate가 아직 하지 못하는 것을 솔직히 적습니다:

- **규칙 간 소비는 한 Redis 슬롯 안에서만 원자적입니다.** 단독 Redis이거나 일치하는 규칙들의 키가 모두
  한 클러스터 슬롯에 있으면 모든 규칙을 하나의 Lua 호출에서 전부-또는-전무로 평가합니다. 그렇지 않으면
  규칙을 하나씩 차감하고 뒤의 규칙이 거부하면 앞선 규칙을 환불합니다. 차감과 환불 사이에는 동시 요청이
  앞선 규칙을 한 개 적게 보고, 환불이 실행되지 못하면 토큰은 소비된 채로 남습니다. 클러스터에서 엄격한
  원자성이 필요하면 여러 대역을 가진 하나의 규칙을 사용하세요. 인메모리 리미터는 규칙 간에도
  전부-또는-전무입니다.
- **서블릿 전용입니다.** WebFlux/리액티브 지원도, Spring Cloud Gateway 필터도 없습니다.
  `WAIT_FOR_REFILL`은 워커 스레드를 블로킹하므로 리액티브 스택에서는 재설계가 필요합니다.
- **버킷 조회/수동 초기화 API가 없습니다.** 특정 호출자의 버킷을 읽거나 비우는 엔드포인트는 없습니다.
  룰셋 리로드가 해당 룰셋의 버킷을 초기화합니다.

## 문서

- [FluxGate Core](fluxgate-core/README.md) - 핵심 Rate Limiting 개념과 API
- [Redis Rate Limiter](fluxgate-redis-ratelimiter/README.md) - Redis 분산 Rate Limiting, Lua 계약, 키 포맷
- [MongoDB Adapter](fluxgate-mongo-adapter/README.md) - 동적 규칙 관리
- [Spring Boot 3 Starter](fluxgate-spring-boot3-starter/README.md) - 자동 설정과 전체 프로퍼티 레퍼런스
- [Testkit](fluxgate-testkit/README.md) - 인메모리 핸들러, 규칙 빌더, JUnit 5 확장
- [문서 색인](docs/README.ko.md) - 아키텍처 Deep Dive, 커스터마이징 가이드, 마이그레이션 노트
- [@RateLimit 애노테이션 가이드](docs/ko/guides/annotation.ko.md) - `@EnableFluxgateAspect`, 전체 애노테이션 속성, `throwOnReject`, `@RestControllerAdvice` 예제, 비웹 사용, 필터 vs. Aspect 비교
- [YAML 룰 세트 가이드](docs/ko/guides/yaml-rule-sets.ko.md) - `fluxgate.ratelimit.rule-sets`: 매처, 알고리즘, 달력 쿼터, 접근 제어
- [0.4 마이그레이션](docs/ko/operations/migration-0.4.ko.md) - 0.3.x에서 올라올 때의 영향
- [CHANGELOG](CHANGELOG.md) - 변경 내역과 Breaking 목록
- [보안 정책](SECURITY.md) - 신고 절차, 보안 기본값, 테넌트 격리
- [기여 가이드](CONTRIBUTING.ko.md) - 기여 방법

## 기여

기여를 환영합니다! 자세한 내용은 [기여 가이드](CONTRIBUTING.ko.md)를 참조하세요.

1. 저장소 포크
2. 기능 브랜치 생성 (`git checkout -b feature/amazing-feature`)
3. 변경사항 커밋 (`git commit -m 'Add amazing feature'`)
4. 브랜치에 푸시 (`git push origin feature/amazing-feature`)
5. Pull Request 열기

## 관련 프로젝트

| 프로젝트 | 설명 |
|----------|------|
| [FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio) | Rate Limit 규칙 관리를 위한 웹 기반 어드민 UI |

## 로드맵

- [x] Prometheus 메트릭 통합
- [x] Redis Cluster 지원
- [x] 상관관계 ID가 포함된 구조화된 JSON 로깅
- [x] Rate Limit 쿼터 관리 UI ([FluxGate Studio](https://github.com/OpenFluxGate/fluxgate-studio))
- [x] 서킷 브레이커 / 재시도 통합 (`ResilientRateLimiter`로 배선 완료)
- [x] 인메모리 Limiter와 Redis 장애 시 인메모리 폴백
- [x] IETF 표준 `RateLimit-*` 응답 헤더와 RFC 9457 problem 응답
- [x] 모듈화
- [x] Sliding window와 fixed window 알고리즘
- [x] 달력 기준 쿼터 (일 / 주 / 월)
- [x] 경로 / 메서드 / 헤더 기반 규칙 매칭
- [x] 스코프 키 접두사, `missing-key-behavior: REJECT`, 신뢰 프록시, `RateLimit-Policy`와 응답 본문 템플릿
- [x] 가중치 `tryConsume(permits)`와 규칙 간 소비 (Lua 단일 호출, Redis Cluster에서는 보상 처리)
- [ ] WebFlux / 리액티브 지원과 Spring Cloud Gateway 필터
- [ ] gRPC API 지원
- [ ] 버킷 조회 및 수동 초기화 API

## 라이선스

이 프로젝트는 MIT 라이선스로 배포됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

## 감사의 말

- [Bucket4j](https://github.com/bucket4j/bucket4j) - 기반 Rate Limiting 라이브러리
- [Lettuce](https://lettuce.io/) - Java용 Redis 클라이언트
- [Spring Boot](https://spring.io/projects/spring-boot) - 애플리케이션 프레임워크

---

**FluxGate** - 분산 Rate Limiting을 간단하게
