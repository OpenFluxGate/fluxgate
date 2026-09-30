# FluxGate 문서

FluxGate 문서에 오신 것을 환영합니다.

[English](README.md) | 한국어

---

## 문서 구조

### 아키텍처

- [아키텍처 개요](ko/architecture/README.ko.md) - 시스템 아키텍처, 다이어그램, 핵심 개념
- [기초 지식](ko/architecture/base-knowledge.ko.md) - Rate Limiting 기본 개념
- **Deep Dive (소스코드 분석)**
  - [전체 Deep Dive](ARCHITECTURE_DEEP_DIVE.ko.md) - Filter → Handler → Engine → RateLimiter → Storage → Reload 한 문서
  - [Filter Layer](ko/architecture/deep-dive/filter-layer.ko.md) - 요청 가로채기, RequestContext
  - [Handler Layer](ko/architecture/deep-dive/handler-layer.ko.md) - Rate Limiting 조율
  - [Engine Layer](ko/architecture/deep-dive/engine-layer.ko.md) - 룰셋 해석, 캐싱
  - [RateLimiter Layer](ko/architecture/deep-dive/ratelimiter-layer.ko.md) - 토큰 버킷 실행
  - [Redis RateLimiter](ko/architecture/deep-dive/redis-ratelimiter.ko.md) - Lua 계약, 키 포맷
  - [Storage Layer](ko/architecture/deep-dive/storage-layer.ko.md) - Redis, MongoDB
  - [Hot Reload](ko/architecture/deep-dive/hot-reload.ko.md) - 핫 리로드 메커니즘
- [알고리즘 분석](ko/architecture/algorithm-analysis.ko.md) - 토큰 버킷 복잡도와 Lua 최적화

### 커스터마이징

- [Request Context](ko/customization/request-context.ko.md) - 요청 컨텍스트 커스터마이징, 인증된 principal로 신원 설정
- [Key Resolver](ko/customization/key-resolver.ko.md) - Rate Limit 키 커스터마이징과 새니타이즈

### 운영

- [0.4 마이그레이션](ko/operations/migration-0.4.ko.md) - 0.3.x에서 올라올 때의 영향과 체크리스트

---

## 빠른 링크

- [메인 README](../README.ko.md) - 시작 가이드
- [CHANGELOG](../CHANGELOG.md) - 변경 내역과 Breaking 목록
- [보안 정책](../SECURITY.md) - 신고 절차, 보안 기본값, 테넌트 격리
- [기여 가이드](../CONTRIBUTING.ko.md)
- [샘플 애플리케이션](../fluxgate-samples/README.md) - 예제 구현
- [GitHub 저장소](https://github.com/OpenFluxGate/fluxgate)

---

## 모듈 문서

| 모듈 | 설명 | README |
|------|------|--------|
| `fluxgate-core` | 핵심 Rate Limiting 엔진, SPI, 인메모리 Limiter | [README](../fluxgate-core/README.md) |
| `fluxgate-redis-ratelimiter` | Redis 토큰 버킷 저장소, Lua 계약, 키 포맷 | [README](../fluxgate-redis-ratelimiter/README.md) |
| `fluxgate-mongo-adapter` | MongoDB 규칙 관리 | [README](../fluxgate-mongo-adapter/README.md) |
| `fluxgate-spring-boot3-starter` | Spring Boot 3.x 자동 구성, 전체 프로퍼티 레퍼런스 | [README](../fluxgate-spring-boot3-starter/README.md) |
| `fluxgate-spring-boot2-starter` | Spring Boot 2.7.x 자동 구성 (javax.servlet) | [README](../fluxgate-spring-boot2-starter/README.md) |
| `fluxgate-control-support` | `@NotifyRuleChange`와 Redis 규칙 변경 통지기 | — |
| `fluxgate-testkit` | 인메모리 핸들러, 규칙 빌더, JUnit 5 확장, 벤치마크 | [README](../fluxgate-testkit/README.md) |
