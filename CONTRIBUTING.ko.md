# FluxGate 기여 가이드

FluxGate에 관심을 가져주셔서 감사합니다! 이 문서는 개발 환경 설정, 코딩 표준,
변경 사항 제출에 대한 모든 내용을 다룹니다.

## 목차

- [행동 강령](#행동-강령)
- [시작하기](#시작하기)
- [개발 환경 설정](#개발-환경-설정)
- [변경 사항 만들기](#변경-사항-만들기)
- [코딩 표준](#코딩-표준)
- [테스트](#테스트)
- [변경 사항 제출](#변경-사항-제출)
- [리뷰 프로세스](#리뷰-프로세스)
- [좋은 첫 번째 이슈](#좋은-첫-번째-이슈)

## 행동 강령

모든 참여자는 [기여자 약속 행동 강령](CODE_OF_CONDUCT.md)을 준수해야 합니다.
위반 사항은 GitHub의 **Security → "Report a vulnerability"** 양식을 통해
비공개로 신고하세요. 이 채널은 관리자에게 암호화된 경로를 제공합니다.

## 시작하기

### 필수 조건

| 도구 | 최소 버전 |
|---|---|
| Java | 11 이상 (core + boot2 스타터 빌드); boot3 스타터는 17; 샘플은 21 |
| Maven | 3.8 이상 (제공된 `./mvnw` 래퍼 사용 — 시스템 Maven 사용 금지) |
| Docker | 24 이상 (통합 테스트용; 단위 테스트는 Docker 없이 실행 가능) |
| Git | 2.x |

### 포크 및 클론

```bash
git clone https://github.com/YOUR-USERNAME/fluxgate.git
cd fluxgate
git remote add upstream https://github.com/OpenFluxGate/fluxgate.git
```

## 개발 환경 설정

### 인프라 시작

`docker/` 디렉토리에 Docker Compose 파일이 있습니다:

| 파일 | 서비스 |
|---|---|
| `docker/full.yml` | Redis 독립형, MongoDB, ELK — 권장 |
| `docker/redis-standalone.yml` | Redis 독립형만 |
| `docker/redis-cluster.yml` | Redis 클러스터 (노드 3개, 포트 7100-7105) |
| `docker/mongo.yml` | MongoDB만 |

```bash
docker compose -f docker/full.yml up -d
docker compose -f docker/full.yml ps
```

### 환경 변수

통합 테스트는 환경 변수를 읽거나 없으면 Testcontainers로 폴백합니다:

```bash
export FLUXGATE_REDIS_URI=redis://localhost:6379
export FLUXGATE_MONGO_URI=mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin
export FLUXGATE_MONGO_DB=fluxgate
```

### 프로젝트 빌드

```bash
# 테스트 없이 전체 빌드
./mvnw install -DskipTests

# 단위 테스트만 실행 (Docker 불필요)
./mvnw test

# 단위 + 통합 테스트 실행 (Docker 필요 또는 위 환경 변수 설정)
./mvnw verify

# Redis 클러스터 테스트 포함 실행
./mvnw verify -Predis-cluster-it

# 통합 테스트 명시적 건너뛰기
./mvnw verify -DskipITs
```

## 변경 사항 만들기

1. upstream 동기화: `git fetch upstream && git rebase upstream/main`
2. 기능 브랜치 생성: `git checkout -b feat/my-feature`
3. 변경 사항 작성 (아래 표준 참조).
4. `./mvnw -q spotless:apply`로 Java 코드 포맷 적용.
5. `./mvnw test -pl <변경된-모듈> -am`으로 단위 테스트 통과 확인.
6. [Conventional Commits](#커밋-메시지)를 사용하여 커밋.
7. Push 후 PR을 열어주세요.

## 코딩 표준

### Java 스타일

* **Google Java Format**은 Spotless로 적용됩니다. 모든 커밋 전에
  `./mvnw spotless:apply`를 실행하세요. `spotless:check`를 통과하지 못한 PR은
  CI에서 거부됩니다.
* **2칸 들여쓰기**; 탭 사용 금지.
* **Java 11 언어 레벨** 적용 모듈: `fluxgate-core`, `fluxgate-redis-ratelimiter`,
  `fluxgate-mongo-adapter`, `fluxgate-control-support`, `fluxgate-spring-boot2-starter`.
  이 모듈들에서는 `record`, `switch` 표현식, 텍스트 블록(`"""`), `var`,
  `Stream.toList()`, `String.formatted` 사용 금지.
* **Java 17 언어 레벨**: `fluxgate-spring-boot3-starter`만 해당.
* **SLF4J 로깅**: `private static final Logger log = LoggerFactory.getLogger(MyClass.class);`
  `System.out.println`이나 `java.util.logging` 사용 금지.
* **Javadoc**: 모든 `public` 타입과 메서드에 작성. 같은 파일의 이웃한 Javadoc과
  동일한 스타일 사용.

### Spring Boot 2 미러 요구 사항

boot2(`fluxgate-spring-boot2-starter`)와 boot3(`fluxgate-spring-boot3-starter`) 스타터는
`jakarta.*` → `javax.*` 임포트를 제외하고 바이트 수준으로 동일하게 유지됩니다.
boot3 스타터 변경 시 반드시 boot2 스타터에도 임포트 치환을 적용하여 미러링해야 합니다.
PR 체크리스트가 이를 상기시켜 줍니다.

### 커밋 메시지

FluxGate는 [Conventional Commits](https://www.conventionalcommits.org/ko/)를 사용합니다:

```
feat(redis): add multi-band Lua script for atomic consumption
fix(core): correct nanosToMillis rounding in RateLimitResponse
docs(boot3): document trusted-proxies property
test(mongo): add Testcontainers IT for rule reload
chore(deps): bump bucket4j to 8.15.0
```

타입: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`, `ci`, `revert`.

주요 변경(breaking change): 타입 뒤에 `!` 추가 (`feat!:`) 및 `BREAKING CHANGE:` 푸터 포함.

### CHANGELOG

사용자에게 보이는 모든 변경 사항은 [CHANGELOG.md](CHANGELOG.md)의
`## [Unreleased]` 아래에 항목을 추가하세요.
[Keep a Changelog](https://keepachangelog.com/ko/) 섹션을 사용하세요:
`Added`, `Changed`, `Fixed`, `Deprecated`, `Removed`, `Security`.

## 테스트

### 테스트 계층

| 계층 | 명령 | Docker 필요? | 파일 패턴 |
|---|---|---|---|
| 단위 | `./mvnw test` | 아니요 | `*Test.java` (`*IntegrationTest`, `*IT` 제외) |
| 통합 | `./mvnw verify` | 예 (또는 환경 변수) | `*IntegrationTest.java`, `*IT.java` |

단위 테스트는 외부 서비스 없이 실행되어야 합니다. 통합 테스트는 Testcontainers를
사용하며, 적합한 Docker 데몬이나 환경 변수 URI가 없으면 자동으로 건너뜁니다.
`@Disabled` 대신 기존 `RedisContainerSupport` / `MongoContainerSupport` 기반
클래스를 통해 `Assumptions.assumeTrue(dockerAvailable)`을 사용하세요.

### 커버리지

신규 코드는 기존 라인 80% / 브랜치 70% 커버리지 임계값을 유지해야 합니다.
JaCoCo 보고서는 `./mvnw verify` 후 `target/site/jacoco/index.html`에서 확인할 수 있습니다.

### 테스트 작성

* 모든 동작 변경은 변경 _전에_ 실패하고 _후에_ 통과하는 회귀 테스트가 필요합니다.
* `assertj` 플루언트 어설션을 사용하세요; 순수 JUnit `assertEquals`는 지양합니다.
* 테스트 이름은 `givenX_whenY_thenZ` 또는 파일에서 사용하는 명령형 동사 형식을 따르세요.

## 변경 사항 제출

1. `main`을 대상으로 PR을 여세요.
2. [PR 템플릿](.github/PULL_REQUEST_TEMPLATE.md)을 빠짐없이 작성하세요.
3. CI 매트릭스는 Java 11, 17, 21로 실행됩니다; 세 버전 모두 통과해야 합니다.
4. 리뷰 코멘트는 14일 이내에 처리해주세요. 그렇지 않으면 대기열 관리를 위해
   PR이 닫힐 수 있습니다.

## 리뷰 프로세스

모든 병합된 변경 사항에는 [@rojae](https://github.com/rojae)의 승인이 최소 1개
필요합니다. 리뷰는 최선을 다해 진행되며, 간단한 변경의 경우 1~2주를 예상하세요.
복잡한 변경은 더 오래 걸릴 수 있습니다 — 먼저 이슈를 열어 설계를 논의하세요.

## 좋은 첫 번째 이슈

[`good first issue`](https://github.com/OpenFluxGate/fluxgate/labels/good%20first%20issue)
레이블이 붙은 이슈들은 신규 기여자를 위해 선정된 것입니다. 이 이슈들은:

* 이슈 본문에 명확한 수락 기준이 있습니다.
* 코드베이스의 제한된 영역을 다룹니다.
* 테스트 전략이 개요로 제시되어 있습니다.

작업하려면 이슈에 댓글을 남겨 관리자가 할당할 수 있게 해주세요. 막히면 이슈에서
질문하세요 — 기초적인 질문도 환영합니다.
