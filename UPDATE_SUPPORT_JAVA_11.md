# FluxGate Java 11 지원 업데이트

이 문서는 FluxGate가 Java 11 + Spring Boot 2.7.x 환경을 지원하기 위해 수행된 변경 사항을 상세히 설명합니다.

## 배경

기존 FluxGate는 Java 17+ 및 Spring Boot 3.x만 지원했습니다. 하지만 많은 기업 환경에서 아직 Java 11을 사용하고 있어, Java 11 지원이 필요했습니다.

### 핵심 문제점

Spring Boot 3.x와 Spring Boot 2.x는 서블릿 API가 다릅니다:
- **Spring Boot 3.x (Java 17+)**: `jakarta.servlet.*` 패키지 사용
- **Spring Boot 2.x (Java 11+)**: `javax.servlet.*` 패키지 사용

이로 인해 동일한 스타터 모듈로 두 환경을 모두 지원할 수 없습니다.

---

## 모듈 구조 변경

### 1. Spring Boot Starter 분리

기존 `fluxgate-spring-boot-starter`를 두 개의 모듈로 분리:

| 기존 모듈 | 새 모듈 | 설명 |
|-----------|---------|------|
| `fluxgate-spring-boot-starter` | `fluxgate-spring-boot3-starter` | Spring Boot 3.x용 (Java 17+, jakarta.servlet) |
| (신규) | `fluxgate-spring-boot2-starter` | Spring Boot 2.x용 (Java 11+, javax.servlet) |

### 2. 샘플 애플리케이션 분리

기존 `fluxgate-sample-standalone`을 두 개로 분리:

| 기존 모듈 | 새 모듈 | 설명 |
|-----------|---------|------|
| `fluxgate-sample-standalone` | `fluxgate-sample-standalone-java21` | Java 21 + Spring Boot 3.x 샘플 |
| (신규) | `fluxgate-sample-standalone-java11` | Java 11 + Spring Boot 2.7.x 샘플 |

---

## 상세 변경 내용

### 1. fluxgate-spring-boot2-starter (신규)

`fluxgate-spring-boot3-starter`를 복사하여 생성하고, 다음 부분을 수정:

#### pom.xml 변경사항
```xml
<properties>
    <java.version>11</java.version>
    <spring-boot.version>2.7.18</spring-boot.version>
    <spring.version>5.3.31</spring.version>
</properties>

<dependencies>
    <!-- javax.servlet for Java 11 / Spring Boot 2.x -->
    <dependency>
        <groupId>javax.servlet</groupId>
        <artifactId>javax.servlet-api</artifactId>
        <version>4.0.1</version>
        <scope>provided</scope>
    </dependency>

    <!-- SLF4J 1.7.x for Spring Boot 2.x -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>1.7.36</version>
    </dependency>

    <!-- Caffeine 2.x for Java 11 -->
    <dependency>
        <groupId>com.github.ben-manes.caffeine</groupId>
        <artifactId>caffeine</artifactId>
        <version>2.9.3</version>
    </dependency>
</dependencies>
```

#### 서블릿 API 변경 (3개 파일)

**FluxgateRateLimitFilter.java**
```java
// 변경 전 (Spring Boot 3.x)
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 변경 후 (Spring Boot 2.x)
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
```

**RequestContextCustomizer.java**
```java
// 변경 전
import jakarta.servlet.http.HttpServletRequest;

// 변경 후
import javax.servlet.http.HttpServletRequest;
```

**FluxgateFilterAutoConfiguration.java**
```java
// 변경 전
@ConditionalOnClass(name = "jakarta.servlet.Filter")

// 변경 후
@ConditionalOnClass(name = "javax.servlet.Filter")
```

#### MongoDB 호환성 수정

**FluxgateMongoAutoConfiguration.java**
```java
// 변경 전 - MongoDB 5.x 전용 API
private boolean collectionExists(MongoDatabase database, String collectionName) {
    for (String name : database.listCollectionNames().into(new java.util.ArrayList<>())) {
        if (name.equals(collectionName)) {
            return true;
        }
    }
    return false;
}

// 변경 후 - MongoDB 4.x/5.x 모두 호환
private boolean collectionExists(MongoDatabase database, String collectionName) {
    for (String name : database.listCollectionNames()) {
        if (name.equals(collectionName)) {
            return true;
        }
    }
    return false;
}
```

---

### 2. fluxgate-sample-standalone-java11 (신규)

Java 11 + Spring Boot 2.7.x 환경에서 FluxGate를 사용하는 방법을 보여주는 샘플 애플리케이션.

#### pom.xml 주요 설정
```xml
<properties>
    <java.version>11</java.version>
    <spring-boot.version>2.7.18</spring-boot.version>
    <springdoc.version>1.8.0</springdoc.version>  <!-- 1.x for Spring Boot 2.x -->
    <!-- MongoDB 5.x로 오버라이드 (fluxgate-mongo-adapter 호환성) -->
    <mongodb.driver.version>5.2.1</mongodb.driver.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- MongoDB 드라이버 버전 오버라이드 -->
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>mongodb-driver-sync</artifactId>
            <version>${mongodb.driver.version}</version>
        </dependency>
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>mongodb-driver-core</artifactId>
            <version>${mongodb.driver.version}</version>
        </dependency>
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>bson</artifactId>
            <version>${mongodb.driver.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- FluxGate Spring Boot 2.x Starter -->
    <dependency>
        <groupId>io.github.openfluxgate</groupId>
        <artifactId>fluxgate-spring-boot2-starter</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- SpringDoc 1.x (Spring Boot 2.x용) -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-ui</artifactId>
        <version>${springdoc.version}</version>
    </dependency>
</dependencies>
```

#### 핸들러 수정

**StandaloneRateLimitHandler.java**
```java
// 변경 전 - 구체적인 구현체 사용
import org.fluxgate.redis.RedisRateLimiter;

private final RedisRateLimiter rateLimiter;

public StandaloneRateLimitHandler(
    RateLimitRuleSetProvider ruleSetProvider,
    RedisRateLimiter rateLimiter,
    FluxgateProperties properties) {

// 변경 후 - 인터페이스 사용 (auto-configuration 호환)
import org.fluxgate.core.ratelimiter.RateLimiter;

private final RateLimiter rateLimiter;

public StandaloneRateLimitHandler(
    RateLimitRuleSetProvider ruleSetProvider,
    RateLimiter rateLimiter,
    FluxgateProperties properties) {
```

#### 삭제된 파일

- `config/FluxgateConfig.java` - auto-configuration과 빈 충돌 방지를 위해 삭제

---

### 3. 부모 pom.xml 변경

```xml
<modules>
    <module>fluxgate-core</module>
    <module>fluxgate-mongo-adapter</module>
    <module>fluxgate-redis-ratelimiter</module>
    <module>fluxgate-spring-boot2-starter</module>  <!-- 신규 -->
    <module>fluxgate-spring-boot3-starter</module>  <!-- 이름 변경 -->
    <module>fluxgate-control-support</module>
    <module>fluxgate-testkit</module>
    <module>fluxgate-samples</module>
</modules>
```

### 4. samples/pom.xml 변경

```xml
<modules>
    <module>fluxgate-sample-mongo</module>
    <module>fluxgate-sample-redis</module>
    <module>fluxgate-sample-api</module>
    <module>fluxgate-sample-filter</module>
    <module>fluxgate-sample-standalone-java21</module>  <!-- 이름 변경 -->
    <module>fluxgate-sample-standalone-java11</module>  <!-- 신규 -->
</modules>
```

---

## 호환성 매트릭스

| 모듈 | Java 버전 | Spring Boot | Servlet API | MongoDB Driver |
|------|-----------|-------------|-------------|----------------|
| fluxgate-spring-boot3-starter | 17+ | 3.x | jakarta.servlet | 5.x |
| fluxgate-spring-boot2-starter | 11+ | 2.7.x | javax.servlet | 4.x/5.x |
| fluxgate-sample-standalone-java21 | 21 | 3.x | jakarta.servlet | 5.x |
| fluxgate-sample-standalone-java11 | 11+ | 2.7.x | javax.servlet | 5.x* |

> *주의: `fluxgate-mongo-adapter`가 MongoDB 5.x로 컴파일되어 있어, Java 11 샘플에서도 MongoDB 드라이버를 5.x로 오버라이드해야 합니다.

---

## 사용 방법

### Java 17+ / Spring Boot 3.x 프로젝트

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot3-starter</artifactId>
    <version>0.3.3</version>
</dependency>
```

### Java 11+ / Spring Boot 2.7.x 프로젝트

```xml
<dependency>
    <groupId>io.github.openfluxgate</groupId>
    <artifactId>fluxgate-spring-boot2-starter</artifactId>
    <version>0.3.3</version>
</dependency>

<!-- MongoDB 사용 시 드라이버 버전 오버라이드 필요 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.mongodb</groupId>
            <artifactId>mongodb-driver-sync</artifactId>
            <version>5.2.1</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 주의사항

1. **MongoDB 드라이버 버전**: `fluxgate-mongo-adapter`는 MongoDB 5.x API로 컴파일되어 있습니다. Spring Boot 2.7.x는 기본적으로 MongoDB 4.6.x를 사용하므로, `dependencyManagement`에서 MongoDB 드라이버 버전을 5.x로 오버라이드해야 합니다.

2. **SLF4J 버전**: Spring Boot 2.7.x는 SLF4J 1.7.x를 사용합니다. FluxGate 코어 모듈들이 SLF4J 2.x를 사용할 수 있으므로, 버전 충돌 시 exclusion이 필요할 수 있습니다.

3. **SpringDoc 버전**: Spring Boot 2.x는 `springdoc-openapi-ui` 1.x를, Spring Boot 3.x는 `springdoc-openapi-starter-webmvc-ui` 2.x를 사용합니다.

4. **Caffeine 버전**: Java 11은 Caffeine 2.x를, Java 17+는 Caffeine 3.x를 사용해야 합니다.

---

## 테스트 방법

### Java 11 샘플 실행
```bash
cd fluxgate-samples/fluxgate-sample-standalone-java11
../../mvnw spring-boot:run
```

### Java 21 샘플 실행
```bash
cd fluxgate-samples/fluxgate-sample-standalone-java21
../../mvnw spring-boot:run
```

### API 테스트
```bash
curl http://localhost:8085/api/test
```

---

## 파일 변경 요약

### 신규 생성
- `fluxgate-spring-boot2-starter/` (전체 디렉토리)
- `fluxgate-samples/fluxgate-sample-standalone-java11/` (전체 디렉토리)

### 이름 변경
- `fluxgate-spring-boot-starter/` → `fluxgate-spring-boot3-starter/`
- `fluxgate-sample-standalone/` → `fluxgate-sample-standalone-java21/`

### 수정
- `pom.xml` (부모) - 모듈 목록 업데이트
- `fluxgate-samples/pom.xml` - 모듈 목록 업데이트
