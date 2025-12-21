# Engine Layer Deep Dive

이 문서는 FluxGate의 Engine Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RateLimitEngine](#1-ratelimitengine)
2. [RateLimitRuleSetProvider와 CachingRuleSetProvider](#2-ratelimitrulesetprovider와-cachingrulesetprovider)
3. [KeyResolver](#3-keyresolver)

---

## 1. RateLimitEngine

```
fluxgate-core/src/main/java/org/fluxgate/core/engine/
└── RateLimitEngine.java
```

`RateLimitEngine`은 Rate Limiting의 **고수준 진입점**입니다. RuleSet 조회와 실제 토큰 소비를 조율합니다.

```java
// RateLimitEngine.java - 실제 코드
public final class RateLimitEngine {

  /** RuleSet을 찾지 못했을 때의 전략 */
  public enum OnMissingRuleSetStrategy {
    /** ruleSetId를 찾지 못하면 IllegalArgumentException 발생 */
    THROW,

    /** Fail-open: Rate Limiting 없이 요청 허용 */
    ALLOW
  }

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RateLimiter rateLimiter;
  private final OnMissingRuleSetStrategy onMissingRuleSetStrategy;

  // Builder 패턴으로 생성
  private RateLimitEngine(Builder builder) {
    this.ruleSetProvider = Objects.requireNonNull(builder.ruleSetProvider,
        "ruleSetProvider must not be null");
    this.rateLimiter = Objects.requireNonNull(builder.rateLimiter,
        "rateLimiter must not be null");
    this.onMissingRuleSetStrategy = Objects.requireNonNull(
        builder.onMissingRuleSetStrategy,
        "onMissingRuleSetStrategy must not be null");
  }

  /** 기본 1 permit으로 Rate Limit 체크 */
  public RateLimitResult check(String ruleSetId, RequestContext context) {
    return check(ruleSetId, context, 1L);
  }

  /** 지정된 permits로 Rate Limit 체크 */
  public RateLimitResult check(String ruleSetId, RequestContext context, long permits) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(context, "context must not be null");

    // (1) RuleSetProvider를 통해 RuleSet 조회
    Optional<RateLimitRuleSet> optionalRuleSet = ruleSetProvider.findById(ruleSetId);

    // (2) RuleSet이 없을 때 전략에 따라 처리
    RateLimitRuleSet ruleSet = optionalRuleSet.orElseGet(() -> {
      if (onMissingRuleSetStrategy == OnMissingRuleSetStrategy.THROW) {
        throw new IllegalArgumentException("Unknown ruleSetId: " + ruleSetId);
      }
      // ALLOW: null 반환 → 아래에서 처리
      return null;
    });

    // (3) Fail-open: RuleSet이 없으면 Rate Limiting 없이 허용
    if (ruleSet == null) {
      return RateLimitResult.allowedWithoutRule();
    }

    // (4) RateLimiter로 토큰 소비 시도
    return rateLimiter.tryConsume(context, ruleSet, permits);
  }
}
```

### 주요 특징

| 구성 요소 | 역할 |
|----------|------|
| `RateLimitRuleSetProvider` | ruleSetId로 RuleSet 조회 (MongoDB, YAML 등) |
| `RateLimiter` | 실제 토큰 소비 로직 (TokenBucketStore 사용) |
| `OnMissingRuleSetStrategy` | RuleSet 미발견 시 동작 결정 |

### 사용 예시

```java
RateLimitEngine engine = RateLimitEngine.builder()
    .ruleSetProvider(mongoRuleSetProvider)
    .rateLimiter(tokenBucketRateLimiter)
    .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.ALLOW)  // Fail-open
    .build();

// Rate Limit 체크
RateLimitResult result = engine.check("api-limits", requestContext);

if (result.isAllowed()) {
    // 요청 처리
} else {
    // 429 Too Many Requests 응답
}
```

---

## 2. RateLimitRuleSetProvider와 CachingRuleSetProvider

```
fluxgate-core/src/main/java/org/fluxgate/core/
├── spi/
│   └── RateLimitRuleSetProvider.java    # SPI 인터페이스
└── reload/
    ├── CachingRuleSetProvider.java      # 캐싱 데코레이터
    ├── RuleCache.java                   # 캐시 인터페이스
    └── RuleReloadListener.java          # 리로드 리스너

fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/cache/
└── CaffeineRuleCache.java               # Caffeine 기반 캐시 구현
```

### RateLimitRuleSetProvider (SPI 인터페이스)

```java
// RateLimitRuleSetProvider.java - 실제 코드
public interface RateLimitRuleSetProvider {

  /**
   * 주어진 ID로 RateLimitRuleSet을 반환합니다.
   * MongoDB, YAML, DB 등 다양한 소스에서 로드할 수 있습니다.
   *
   * @param ruleSetId 규칙 세트의 고유 식별자
   * @return RuleSet을 담은 Optional, 없으면 empty
   */
  Optional<RateLimitRuleSet> findById(String ruleSetId);
}
```

### CachingRuleSetProvider (캐싱 데코레이터)

```java
// CachingRuleSetProvider.java - 실제 코드
public class CachingRuleSetProvider implements RateLimitRuleSetProvider, RuleReloadListener {

  private final RateLimitRuleSetProvider delegate;  // 실제 Provider (예: MongoDB)
  private final RuleCache cache;                    // 로컬 캐시 (예: Caffeine)

  public CachingRuleSetProvider(RateLimitRuleSetProvider delegate, RuleCache cache) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    // (1) 캐시에서 먼저 조회
    Optional<RateLimitRuleSet> cached = cache.get(ruleSetId);
    if (cached.isPresent()) {
      log.trace("Cache hit for ruleSetId: {}", ruleSetId);
      return cached;
    }

    // (2) 캐시 미스 → delegate(MongoDB 등)에서 조회
    log.debug("Cache miss for ruleSetId: {}, loading from delegate", ruleSetId);
    Optional<RateLimitRuleSet> loaded = delegate.findById(ruleSetId);

    // (3) 조회된 RuleSet을 캐시에 저장
    loaded.ifPresent(ruleSet -> {
      cache.put(ruleSetId, ruleSet);
      log.debug("Cached ruleSetId: {}", ruleSetId);
    });

    return loaded;
  }

  /** Hot Reload 이벤트 수신 시 캐시 무효화 */
  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload triggered, invalidating all cached rules");
      cache.invalidateAll();
    } else {
      log.info("Reload triggered for ruleSetId: {}", event.getRuleSetId());
      cache.invalidate(event.getRuleSetId());
    }
  }
}
```

### RuleCache 인터페이스

```java
// RuleCache.java - 실제 코드
public interface RuleCache {

  /** 캐시에서 RuleSet 조회 */
  Optional<RateLimitRuleSet> get(String ruleSetId);

  /** RuleSet을 캐시에 저장 */
  void put(String ruleSetId, RateLimitRuleSet ruleSet);

  /** 특정 RuleSet 캐시 무효화 */
  void invalidate(String ruleSetId);

  /** 모든 캐시 무효화 */
  void invalidateAll();

  /** 현재 캐시된 RuleSet ID 목록 반환 */
  Set<String> getCachedRuleSetIds();

  /** 캐시 크기 반환 */
  int size();

  /** 캐시 통계 반환 (선택적) */
  default Optional<CacheStats> getStats() {
    return Optional.empty();
  }
}
```

### CaffeineRuleCache (Caffeine 기반 구현)

```java
// CaffeineRuleCache.java - 실제 코드
public class CaffeineRuleCache implements RuleCache {

  private final Cache<String, RateLimitRuleSet> cache;
  private final Duration ttl;
  private final int maxSize;

  public CaffeineRuleCache(Duration ttl, int maxSize) {
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    this.maxSize = maxSize;

    this.cache = Caffeine.newBuilder()
        .expireAfterWrite(ttl)      // TTL 설정
        .maximumSize(maxSize)        // 최대 캐시 크기
        .recordStats()               // 통계 기록
        .removalListener((key, value, cause) -> {
          if (cause.wasEvicted()) {
            log.debug("Rule set evicted from cache: {} (cause: {})", key, cause);
          }
        })
        .build();

    log.info("CaffeineRuleCache initialized with ttl={}, maxSize={}", ttl, maxSize);
  }

  @Override
  public Optional<RateLimitRuleSet> get(String ruleSetId) {
    return Optional.ofNullable(cache.getIfPresent(ruleSetId));
  }

  @Override
  public void put(String ruleSetId, RateLimitRuleSet ruleSet) {
    cache.put(ruleSetId, ruleSet);
  }

  @Override
  public void invalidate(String ruleSetId) {
    cache.invalidate(ruleSetId);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }
}
```

### 캐시 설정 (application.yml)

```yaml
fluxgate:
  reload:
    cache:
      enabled: true
      ttl: 5m          # 캐시 TTL (기본값: 5분)
      max-size: 1000   # 최대 캐시 크기 (기본값: 1000)
```

---

## 3. KeyResolver

```
fluxgate-core/src/main/java/org/fluxgate/core/key/
├── KeyResolver.java           # 인터페이스
├── LimitScopeKeyResolver.java # LimitScope 기반 구현
└── RateLimitKey.java          # 키 값 객체
```

`KeyResolver`는 요청 컨텍스트와 규칙의 `LimitScope`에 따라 Rate Limit 키를 생성합니다.

### KeyResolver 인터페이스

```java
// KeyResolver.java - 실제 코드
public interface KeyResolver {

  /**
   * 요청 컨텍스트와 규칙에서 Rate Limit 키를 생성합니다.
   *
   * @param context 클라이언트 정보 (IP, userId, apiKey 등)
   * @param rule LimitScope을 포함한 Rate Limit 규칙
   * @return 생성된 Rate Limit 키
   */
  RateLimitKey resolve(RequestContext context, RateLimitRule rule);
}
```

### LimitScopeKeyResolver (기본 구현)

```java
// LimitScopeKeyResolver.java - 실제 코드
public class LimitScopeKeyResolver implements KeyResolver {

  private static final String GLOBAL_KEY = "global";

  @Override
  public RateLimitKey resolve(RequestContext context, RateLimitRule rule) {
    LimitScope scope = rule.getScope();
    if (scope == null) {
      scope = LimitScope.PER_IP;  // 기본값
    }

    String keyValue;
    switch (scope) {
      case GLOBAL:
        keyValue = GLOBAL_KEY;
        break;
      case PER_IP:
        keyValue = resolveClientIp(context);
        break;
      case PER_USER:
        keyValue = resolveUserId(context);
        break;
      case PER_API_KEY:
        keyValue = resolveApiKey(context);
        break;
      case CUSTOM:
        keyValue = resolveCustom(context, rule);
        break;
      default:
        keyValue = resolveClientIp(context);
        break;
    }

    log.debug("Resolved key for rule {} with scope {}: {}", rule.getId(), scope, keyValue);

    return new RateLimitKey(keyValue);
  }

  /** IP 주소 해석 - null/empty 시 "unknown" 반환 */
  private String resolveClientIp(RequestContext context) {
    String clientIp = context.getClientIp();
    if (clientIp == null || clientIp.isEmpty()) {
      log.warn("clientIp is null/empty, using 'unknown' as fallback");
      return "unknown";
    }
    return clientIp;
  }

  /** User ID 해석 - null/empty 시 IP로 fallback */
  private String resolveUserId(RequestContext context) {
    String userId = context.getUserId();
    if (userId == null || userId.isEmpty()) {
      log.warn("userId is null/empty for PER_USER scope, falling back to clientIp");
      return resolveClientIp(context);
    }
    return userId;
  }

  /** API Key 해석 - null/empty 시 IP로 fallback */
  private String resolveApiKey(RequestContext context) {
    String apiKey = context.getApiKey();
    if (apiKey == null || apiKey.isEmpty()) {
      log.warn("apiKey is null/empty for PER_API_KEY scope, falling back to clientIp");
      return resolveClientIp(context);
    }
    return apiKey;
  }

  /** Custom 키 해석 - keyStrategyId로 attributes에서 조회 */
  private String resolveCustom(RequestContext context, RateLimitRule rule) {
    String keyStrategyId = rule.getKeyStrategyId();
    if (keyStrategyId == null || keyStrategyId.isEmpty()) {
      log.warn("keyStrategyId is null/empty for CUSTOM scope, falling back to clientIp");
      return resolveClientIp(context);
    }

    Object value = context.getAttributes().get(keyStrategyId);
    if (value == null) {
      log.warn("Attribute '{}' not found for CUSTOM scope, falling back to clientIp", keyStrategyId);
      return resolveClientIp(context);
    }

    return value.toString();
  }
}
```

### RateLimitKey (값 객체)

```java
// RateLimitKey.java - 실제 코드
public final class RateLimitKey {

  private final String key;

  public RateLimitKey(String key) {
    this.key = Objects.requireNonNull(key, "key must not be null");
  }

  public static RateLimitKey of(String key) {
    return new RateLimitKey(key);
  }

  public String key() {
    return key;
  }

  public String value() {
    return key;
  }
}
```

### LimitScope별 키 생성 예시

| LimitScope | 키 소스 | 생성 키 예시 |
|------------|--------|-------------|
| `GLOBAL` | 상수 `"global"` | `global` |
| `PER_IP` | `RequestContext.clientIp` | `192.168.1.100` |
| `PER_USER` | `RequestContext.userId` | `user-123` |
| `PER_API_KEY` | `RequestContext.apiKey` | `api-key-abc` |
| `CUSTOM` | `attributes.get(keyStrategyId)` | `tenant-456` |

### Fallback 동작

값이 null 또는 empty인 경우:

```
PER_USER (userId 없음) → PER_IP로 fallback
PER_API_KEY (apiKey 없음) → PER_IP로 fallback
PER_IP (clientIp 없음) → "unknown" 사용
CUSTOM (attribute 없음) → PER_IP로 fallback
```

> **보수적 접근**: unknown IP를 가진 모든 요청은 동일한 버킷을 공유하여 빠르게 Rate Limit에 도달합니다.

---

## 관련 문서

- [Handler Layer Deep Dive](handler-layer.ko.md)
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md)
- [아키텍처 개요](../README.ko.md)