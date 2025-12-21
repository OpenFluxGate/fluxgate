# FluxGate 문서

FluxGate 문서에 오신 것을 환영합니다.

[English](README.md) | 한국어

---

## 문서 구조

### 아키텍처

- [아키텍처 개요](ko/architecture/README.ko.md) - 시스템 아키텍처, 다이어그램, 핵심 개념
- **Deep Dive (소스코드 분석)**
  - [Filter Layer](ko/architecture/deep-dive/filter-layer.ko.md) - 요청 가로채기, RequestContext
  - [Handler Layer](ko/architecture/deep-dive/handler-layer.ko.md) - Rate Limiting 조율
  - [Engine Layer](ko/architecture/deep-dive/engine-layer.ko.md) - 규칙 매칭, 키 해석
  - [RateLimiter Layer](ko/architecture/deep-dive/ratelimiter-layer.ko.md) - 토큰 버킷 실행
  - [Storage Layer](ko/architecture/deep-dive/storage-layer.ko.md) - Redis, MongoDB
  - [Hot Reload](ko/architecture/deep-dive/hot-reload.ko.md) - 핫 리로드 메커니즘

### 커스터마이징

- [Request Context](ko/customization/request-context.ko.md) - 요청 컨텍스트 커스터마이징
- [Key Resolver](ko/customization/key-resolver.ko.md) - Rate Limit 키 커스터마이징

---

## 빠른 링크

- [메인 README](../README.ko.md) - 시작 가이드
- [샘플 애플리케이션](../fluxgate-samples/) - 예제 구현
- [GitHub 저장소](https://github.com/OpenFluxGate/fluxgate)

---

## 모듈 문서

| 모듈 | 설명 |
|------|------|
| `fluxgate-core` | 핵심 Rate Limiting 엔진 |
| `fluxgate-redis-ratelimiter` | Redis 기반 토큰 버킷 저장소 |
| `fluxgate-mongo-adapter` | MongoDB 규칙 관리 |
| `fluxgate-spring-boot3-starter` | Spring Boot 3.x 자동 구성 |
| `fluxgate-spring-boot2-starter` | Spring Boot 2.7.x 자동 구성 |
