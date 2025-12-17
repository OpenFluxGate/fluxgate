# FluxGate 기여 가이드

FluxGate에 기여해 주셔서 감사합니다! 이 문서는 기여를 위한 가이드라인과 지침을 제공합니다.

## 목차

- [행동 강령](#행동-강령)
- [시작하기](#시작하기)
- [개발 환경 설정](#개발-환경-설정)
- [변경 사항 만들기](#변경-사항-만들기)
- [코딩 표준](#코딩-표준)
- [테스트](#테스트)
- [변경 사항 제출](#변경-사항-제출)
- [리뷰 프로세스](#리뷰-프로세스)

## 행동 강령

이 프로젝트에 참여함으로써 존중하고 포용적인 환경을 유지하는 데 동의합니다:

- 토론에서 존중하고 건설적으로 대화하기
- 새로운 참여자를 환영하고 시작을 도와주기
- 커뮤니티에 가장 좋은 것에 집중하기
- 다른 커뮤니티 구성원에게 공감 보여주기

## 시작하기

### 사전 요구 사항

- **Java 11+** - 프로젝트 빌드 및 실행에 필요
- **Maven 3.8+** - 빌드 도구
- **Docker** - 개발 중 Redis 및 MongoDB 실행용
- **Git** - 버전 관리

### Fork 및 Clone

1. GitHub에서 저장소를 Fork합니다
2. Fork한 저장소를 로컬에 Clone합니다:

```bash
git clone https://github.com/YOUR-USERNAME/fluxgate.git
cd fluxgate
```

3. upstream 저장소를 추가합니다:

```bash
git remote add upstream https://github.com/OpenFluxGate/fluxgate.git
```

## 개발 환경 설정

### 1. 인프라 시작

`docker/` 디렉토리에 로컬 개발용 Docker Compose 파일을 제공합니다:

| 파일                            | 설명                                        |
|-------------------------------|-------------------------------------------|
| `docker/full.yml`             | 모든 서비스 (Redis, MongoDB, ELK) - **개발용 권장** |
| `docker/redis-standalone.yml` | Redis 단독                                  |
| `docker/redis-cluster.yml`    | Redis 클러스터 (3노드)                          |
| `docker/mongo.yml`            | MongoDB만                                  |
| `docker/elk.yml`              | Elasticsearch, Logstash, Kibana           |

```bash
# 모든 서비스 시작 (권장)
docker compose -f docker/full.yml up -d

# 서비스 실행 확인
docker compose -f docker/full.yml ps

# 서비스 중지
docker compose -f docker/full.yml down
```

### 2. 프로젝트 빌드

```bash
# 모든 모듈 빌드
./mvnw clean install

# 테스트 없이 빌드 (더 빠름)
./mvnw clean install -DskipTests
```

### 3. 테스트 실행

```bash
# 모든 테스트 및 검증 실행 (PR 전 필수)
./mvnw clean verify

# 테스트만 실행
./mvnw test

# 특정 모듈 테스트 실행
./mvnw test -pl fluxgate-core
```

### 4. IDE 설정

#### IntelliJ IDEA (권장)

1. Maven 프로젝트로 열기
2. 어노테이션 처리 활성화: `Settings > Build > Compiler > Annotation Processors`
3. 코드 스타일 가져오기: `Settings > Editor > Code Style > Import Scheme`

#### VS Code

1. "Extension Pack for Java" 설치
2. 프로젝트 폴더 열기
3. Maven import 완료 대기

## 변경 사항 만들기

### 브랜치 명명 규칙

`main`에서 설명적인 이름으로 브랜치를 생성합니다:

- `feature/` - 새 기능 (예: `feature/sliding-window-algorithm`)
- `development/` - 새 모듈 (예: `development/fluxgate-sample-something`)
- `fix/` - 버그 수정 (예: `fix/redis-connection-timeout`)
- `docs/` - 문서 변경 (예: `docs/api-reference`)
- `refactor/` - 코드 리팩토링 (예: `refactor/cleanup-handlers`)
- `test/` - 테스트 추가 또는 수정 (예: `test/redis-integration`)

```bash
# 새 브랜치 생성
git checkout -b feature/your-feature-name

# 브랜치 최신 상태 유지
git fetch upstream
git rebase upstream/main
```

### 커밋 메시지

[Conventional Commits](https://www.conventionalcommits.org/) 규격을 따릅니다:

```
<type>(<scope>): <description>

[선택적 본문]

[선택적 푸터]
```

**타입:**

- `feat`: 새 기능
- `fix`: 버그 수정
- `docs`: 문서 변경
- `style`: 코드 스타일 변경 (포맷팅 등)
- `refactor`: 코드 리팩토링
- `test`: 테스트 추가 또는 수정
- `chore`: 빌드 프로세스 또는 보조 도구 변경

**예시:**

```bash
feat(redis): add connection pool monitoring

fix(core): handle null key resolver gracefully

docs(readme): add quick start guide

test(mongo): add integration tests for rule store
```

## 코딩 표준

### Java 스타일 가이드

일부 수정 사항과 함께 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)를 따릅니다:

1. **들여쓰기**: 4 스페이스 (탭 아님)
2. **줄 길이**: 최대 120자
3. **중괄호**: 제어문에 항상 중괄호 사용

### 코드 품질

- 명확한 변수/메서드 이름으로 자체 문서화 코드 작성
- 모든 public 클래스와 메서드에 Javadoc 추가
- 메서드를 집중적이고 작게 유지 (30줄 미만 권장)
- SOLID 원칙 준수
- 코드 중복 방지

### Javadoc 요구 사항

모든 public API에 Javadoc이 있어야 합니다:

```java
/**
 * Rate Limiter에서 토큰을 소비하려고 시도합니다.
 *
 * @param context 클라이언트 정보를 포함하는 요청 컨텍스트
 * @param ruleSet 적용할 Rate Limit 규칙
 * @param tokens 소비할 토큰 수
 * @return Rate Limit 검사 결과
 * @throws IllegalArgumentException tokens가 1 미만인 경우
 */
public RateLimitResult tryConsume(RequestContext context, RateLimitRuleSet ruleSet, long tokens);
```

### 패키지 구조

```
org.fluxgate
├── core                 # 핵심 추상화 및 인터페이스
│   ├── config          # 설정 클래스
│   ├── context         # 요청 컨텍스트
│   ├── handler         # Rate Limit 핸들러
│   ├── key             # 키 해석
│   └── ratelimiter     # Rate Limiter 구현체
├── redis               # Redis 전용 구현
├── adapter.mongo       # MongoDB 어댑터
└── spring              # Spring Boot 통합
```

## 테스트

### 테스트 카테고리

1. **단위 테스트** - 개별 클래스를 격리하여 테스트
2. **통합 테스트** - 컴포넌트 상호작용 테스트
3. **End-to-End 테스트** - 완전한 흐름 테스트

### 테스트 작성

- 설명적인 테스트 메서드 이름 사용
- Arrange-Act-Assert 패턴 따르기
- 단위 테스트에서 외부 의존성 Mock 처리
- 테스트 분류를 위해 `@Tag` 사용

```java

@Test
@DisplayName("Rate Limit 초과 시 요청을 거부해야 함")
void shouldRejectWhenRateLimitExceeded() {
    // Arrange
    RateLimiter limiter = createLimiter(10);
    consumeTokens(limiter, 10);

    // Act
    RateLimitResult result = limiter.tryConsume(context, ruleSet, 1);

    // Assert
    assertThat(result.isAllowed()).isFalse();
    assertThat(result.getRemainingTokens()).isZero();
}
```

### 테스트 커버리지

- 최소 80% 코드 커버리지 목표
- 비즈니스 로직과 엣지 케이스 테스트에 집중
- 단순한 getter/setter는 테스트하지 않음

## 변경 사항 제출

### 제출 전 확인 사항

1. **인프라 시작** (실행 중이 아닌 경우):
   ```bash
   docker compose -f docker/full.yml up -d
   ```

2. **브랜치 업데이트**:
   ```bash
   git fetch upstream
   git rebase upstream/main
   ```

3. **코드 포맷팅 적용**:
   ```bash
   ./mvnw spotless:apply
   ```

4. **모든 테스트 및 검증 실행**:
   ```bash
   ./mvnw clean verify
   ```

   이 명령은 다음을 실행합니다:
    - 코드 컴파일
    - 단위 테스트
    - 통합 테스트
    - 코드 커버리지 검사 (JaCoCo)
    - 코드 포맷팅 검사 (Spotless)

### Pull Request 생성

1. 브랜치를 Fork한 저장소에 Push:
   ```bash
   git push origin feature/your-feature-name
   ```

2. GitHub에서 Pull Request 열기

3. PR 템플릿 작성:
    - 변경 사항에 대한 명확한 설명
    - 관련 이슈 번호
    - 수행한 테스트
    - 스크린샷 (UI 변경 시)

### PR 체크리스트

- [ ] 코드가 프로젝트 스타일 가이드를 따름
- [ ] 모든 테스트가 로컬에서 통과
- [ ] 새 코드에 적절한 테스트 커버리지 있음
- [ ] 새 public API에 Javadoc 추가됨
- [ ] 필요시 README 업데이트됨
- [ ] 주요 변경 사항에 대해 CHANGELOG 업데이트됨

## 리뷰 프로세스

### 기대할 수 있는 것

1. **자동화된 검사** 먼저 실행 (CI/CD)
2. 메인테이너의 **코드 리뷰**
3. 코멘트로 **피드백** 제공
4. 모든 요구 사항 충족 시 **승인**
5. 메인테이너에 의한 **머지**

### 피드백에 응답하기

- 제안에 열린 마음 갖기
- 피드백이 불명확하면 질문하기
- 요청된 변경 사항 신속히 처리하기
- 처리된 대화는 해결됨으로 표시하기

### 머지 후

- 기능 브랜치 삭제
- 최신 변경 사항 Pull:
  ```bash
  git checkout main
  git pull upstream main
  ```

## 도움 받기

- **질문/버그**: [GitHub Issue](https://github.com/OpenFluxGate/fluxgate/issues) 열기
- **보안**: security@openfluxgate.org로 이메일 (공개 이슈 열지 마세요)

## 인정

기여자는 다음에서 인정받습니다:

- 릴리스 노트
- GitHub 기여자 목록
- 프로젝트 문서

FluxGate에 기여해 주셔서 감사합니다!☺️
