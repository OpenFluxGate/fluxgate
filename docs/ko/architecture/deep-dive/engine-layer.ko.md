# Engine Layer Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에, 전체 계층을 한 문서로 훑는 서술은
> [아키텍처 Deep Dive](../../../ARCHITECTURE_DEEP_DIVE.ko.md)에 있습니다.

이 문서는 FluxGate의 Engine Layer를 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RateLimitEngine](#1-ratelimitengine)
2. [RateLimitRuleSetProvider와 CachingRuleSetProvider](#2-ratelimitrulesetprovider와-cachingrulesetprovider)
3. [RuleCache와 CaffeineRuleCache](#3-rulecache와-caffeinerulecache)
4. [KeyResolver](#4-keyresolver)

---

## 1. RateLimitEngine

```
fluxgate-core/src/main/java/org/fluxgate/core/engine/
└── RateLimitEngine.java
```

`RateLimitEngine`은 Rate Limiting의 **정식 진입점(canonical entry point)** 입니다. 룰셋 조회와
실제 토큰 소비를 조율하고, 룰셋이 없을 때의 동작을 결정합니다.

```java
// RateLimitEngine.java - 실제 코드
public final class RateLimitEngine {

  /** Prefix of the synthetic key reported when no rule set exists and the strategy is DENY. */
  private static final String MISSING_RULE_SET_KEY_PREFIX = "missing-rule-set:";


  // ... DENIED_KEY_PREFIX, IP_KEY_PREFIX

  /** Strategy when no rule set is found for a given id. */
  public enum OnMissingRuleSetStrategy {
    /** Throw an IllegalArgumentException when the rule set id is not found. */
    THROW,

    /**
     * Fail-open: allow the request without applying any rate limiting. This will return an
     * "allowed" result with null rule information.
     */
    ALLOW,

    /**
     * Fail-closed: reject the request without applying any rate limiting. The returned result has
     * no matched rule, {@code nanosToWait = 0} and a synthetic key of the form {@code
     * missing-rule-set:<id>} so the rejection is traceable in metrics and logs. Only the id is
     * sanitised, so the prefix survives an id that has to be rewritten.
     */
    DENY
  }

  private final RateLimitRuleSetProvider ruleSetProvider;
  private final RateLimiter rateLimiter;
  private final OnMissingRuleSetStrategy onMissingRuleSetStrategy;
  private final PathPatternMatcher pathMatcher;


  /**
   * Check rate limit with a default of 1 permit.
   *
   * @param ruleSetId the rule set to apply (must not be null)
   * @param context request-scoped information (must not be null)
   * @return the rate limit result, never null
   */
  public RateLimitResult check(String ruleSetId, RequestContext context) {
    return check(ruleSetId, context, 1L);
  }

  /**
   * Check rate limit for the given ruleSetId and permits.
   *
   * <p>Never returns {@code null}: a missing rule set is resolved by {@link
   * OnMissingRuleSetStrategy}, and a {@link RateLimiter} that breaks its contract by returning
   * {@code null} raises an {@link IllegalStateException} instead of leaking the null to callers.
   *
   * <p>Access control is evaluated before calling the limiter: {@link AccessControl.Decision#DENY}
   * returns a rejected result immediately; {@link AccessControl.Decision#ALLOW_BYPASS} returns an
   * allowed result without consuming any tokens.
   *
   * @param ruleSetId the rule set to apply (must not be null)
   * @param context request-scoped information (must not be null)
   * @param permits number of permits to consume
   * @return the rate limit result, never null
   */
  public RateLimitResult check(String ruleSetId, RequestContext context, long permits) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(context, "context must not be null");

    Optional<RateLimitRuleSet> optionalRuleSet = ruleSetProvider.findById(ruleSetId);
    if (!optionalRuleSet.isPresent()) {
      return onMissingRuleSet(ruleSetId);
    }

    RateLimitRuleSet ruleSet = optionalRuleSet.get();

    // ===== access control =====
    AccessControl accessControl = ruleSet.getAccessControl();
    if (!accessControl.isEmpty()) {
      List<RateLimitKey> resolvedKeys = new ArrayList<>();
      RateLimitKey primaryKey = null;
      // Keys are only compared against the key lists; with IP lists alone there is nothing to
      // resolve, and resolving would cost a resolver call per matching rule for nothing.
      if (!accessControl.getAllowedKeys().isEmpty() || !accessControl.getDeniedKeys().isEmpty()) {
        primaryKey = resolveKeysForAccessControl(context, ruleSet, resolvedKeys);
      }
      AccessControl.Decision decision =
          accessControl.evaluate(context.getClientIp(), primaryKey, resolvedKeys);
      if (decision == AccessControl.Decision.DENY) {
        return RateLimitResult.builder(
                deniedKey(accessControl, context.getClientIp(), resolvedKeys))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(0L)
            .build();
      }
      if (decision == AccessControl.Decision.ALLOW_BYPASS) {
        return RateLimitResult.allowedWithoutRule();
      }
    }

    // ===== delegate to limiter =====
    RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, permits, pathMatcher);
    if (result == null) {
      throw new IllegalStateException(
          "RateLimiter "
              + rateLimiter.getClass().getName()
              + " returned null for ruleSetId: "
              + ruleSetId);
    }
    return result;
  }

  // ... resolveKeysForAccessControl(), deniedKey()

  private RateLimitResult onMissingRuleSet(String ruleSetId) {
    switch (onMissingRuleSetStrategy) {
      case THROW:
        throw new IllegalArgumentException("Unknown ruleSetId: " + ruleSetId);
      case DENY:
        // Fail-closed branch: do not call RateLimiter at all.
        return RateLimitResult.builder(RateLimitKey.of(MISSING_RULE_SET_KEY_PREFIX, ruleSetId))
            .allowed(false)
            .remainingTokens(0L)
            .nanosToWaitForRefill(0L)
            .build();
      case ALLOW:
      default:
        // Fail-open branch: do not call RateLimiter at all.
        return RateLimitResult.allowedWithoutRule();
    }
  }
}
```

### 리미터 호출 전: 접근 제어와 경로 매처

룰셋이 있으면 리미터를 부르기 전에 룰셋의 `AccessControl`을 평가합니다. `DENY`면 토큰을 건드리지 않고
`denied:` 접두사의 합성 키로 거부하고, `ALLOW_BYPASS`면 토큰을 소비하지 않고 허용합니다. 키 목록
(허용·거부 키)이 비어 있으면 키를 해석하지 않으므로, IP 목록만 쓰는 룰셋은 규칙마다 KeyResolver를
부르는 비용을 치르지 않습니다.

리미터는 인자 4개짜리 `tryConsume(context, ruleSet, permits, pathMatcher)`로 호출됩니다. 엔진에 설정한
`PathPatternMatcher`(기본값 `SimpleAntPathMatcher.INSTANCE`)가 그대로 넘어가므로, 어떤 규칙이 요청에
맞는지는 엔진과 리미터가 같은 매처로 판단합니다.

### OnMissingRuleSetStrategy: 세 갈래

| 전략 | 동작 | 언제 쓰나 |
|-----|------|----------|
| `THROW` | `IllegalArgumentException` | 빌더 기본값. 라이브러리를 직접 조립할 때 설정 오류를 즉시 드러냄 |
| `ALLOW` | Rate Limiting 없이 허용 (fail-open) | `missing-rule-behavior: ALLOW`. Rate Limiting은 부가 기능이라는 입장 |
| `DENY` | 거부 (fail-closed) | 스타터 기본값(`missing-rule-behavior: DENY`). 룰셋 없는 요청이 통과하면 안 되는 배포 |

`DENY`가 0.4에서 추가된 갈래입니다. 중요한 세부는 **거부 결과에 합성 키가 붙는다**는 점입니다.

```
missing-rule-set:<ruleSetId>
```

`RateLimitResult`는 거부 결과에 키를 요구하는데, 정의상 실제 키는 존재하지 않습니다. 합성 키를 두면
메트릭과 로그에서 "룰셋을 못 찾아 거부됨"이 다른 거부와 구분되어 추적됩니다. 빈 문자열이나 null을
넣으면 그 추적성이 사라집니다.

`nanosToWaitForRefill = 0`인 이유도 같습니다. 부족한 것은 토큰이 아니라 설정이므로 기다려서 풀릴
일이 아닙니다.

### THROW / ALLOW / DENY 모두 RateLimiter를 호출하지 않습니다

세 분기 모두 `RateLimiter`를 건드리지 않습니다(코드 주석의 `do not call RateLimiter at all`).
룰셋이 없다면 적용할 규칙도, 소비할 버킷도 없기 때문입니다. 존재하지 않는 룰셋 id로 들어온 요청이
Redis 왕복을 유발하지 않는다는 뜻이기도 합니다.

### null 방어

`RateLimiter` 구현이 계약을 깨고 `null`을 돌려주면 `IllegalStateException`이 납니다. 그 null이
그대로 올라가면 필터에서 `NullPointerException`이 되어, 원인이 사용자 구현체라는 사실이 스택
트레이스에서 사라집니다. 예외 메시지에 구현 클래스명과 ruleSetId를 함께 담는 이유입니다.

### 스타터 배선

```java
// FluxgateRateLimiterAutoConfiguration.java - 실제 코드
@Bean
@ConditionalOnMissingBean(RateLimitEngine.class)
@ConditionalOnBean({RateLimiter.class, RateLimitRuleSetProvider.class})
public RateLimitEngine fluxgateRateLimitEngine(
    RateLimitRuleSetProvider ruleSetProvider,
    RateLimiter rateLimiter,
    ObjectProvider<PathPatternMatcher> pathMatcherProvider) {
  OnMissingRuleSetStrategy strategy =
      properties.getRatelimit().isDenyWhenRuleMissing()
          ? OnMissingRuleSetStrategy.DENY
          : OnMissingRuleSetStrategy.ALLOW;

  PathPatternMatcher pathMatcher = pathMatcherProvider.getIfAvailable();

  log.info(
      "Creating RateLimitEngine: limiter={}, ruleSetProvider={}, onMissingRuleSet={},"
          + " pathMatcher={}",
      rateLimiter.getClass().getSimpleName(),
      ruleSetProvider.getClass().getSimpleName(),
      strategy,
      pathMatcher != null ? pathMatcher.getClass().getSimpleName() : "default");

  RateLimitEngine.Builder builder =
      RateLimitEngine.builder()
          .ruleSetProvider(ruleSetProvider)
          .rateLimiter(rateLimiter)
          .onMissingRuleSetStrategy(strategy);

  if (pathMatcher != null) {
    builder.pathMatcher(pathMatcher);
  }

  return builder.build();
}
```

```yaml
fluxgate:
  ratelimit:
    missing-rule-behavior: DENY  # 기본값 DENY, ALLOW = fail-open (isDenyWhenRuleMissing()가 이 값을 읽음)
```

`RateLimitEngine`의 Javadoc이 말하는 대로, 이 결정이 내려지는 곳은 **이 한 군데뿐**입니다.
0.3.x에서는 핸들러 구현마다 "룰셋이 없으면 허용"을 각자 판단했고, 그래서 설정으로 바꿀 수가 없었습니다.

### 주요 구성 요소

| 구성 요소 | 역할 |
|----------|------|
| `RateLimitRuleSetProvider` | ruleSetId로 룰셋 조회 (MongoDB, YAML, 캐싱 데코레이터 등) |
| `RateLimiter` | 실제 토큰 소비 (`ResilientRateLimiter` → `LazyRedisRateLimiter` → `RedisRateLimiter` 체인일 수 있음) |
| `OnMissingRuleSetStrategy` | 룰셋 미발견 시 동작 |

### 사용 예시

```java
RateLimitEngine engine = RateLimitEngine.builder()
    .ruleSetProvider(cachingRuleSetProvider)
    .rateLimiter(redisRateLimiter)
    .onMissingRuleSetStrategy(OnMissingRuleSetStrategy.ALLOW)  // Fail-open
    .build();

RateLimitResult result = engine.check("api-limits", requestContext);

if (result.isAllowed()) {
    // 요청 처리
} else {
    // 429 Too Many Requests 응답
}
```

Spring Boot 스타터를 쓰면 이 조립은 자동이며, 위 배선 코드가 대신 해 줍니다.

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

  private static final Logger log = LoggerFactory.getLogger(CachingRuleSetProvider.class);

  private final RateLimitRuleSetProvider delegate;
  private final RuleCache cache;

  public CachingRuleSetProvider(RateLimitRuleSetProvider delegate, RuleCache cache) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");

    // Delegate get/load/put to the cache so implementations can load atomically
    return cache.getOrLoad(
        ruleSetId,
        id -> {
          log.debug("Cache miss for ruleSetId: {}, loading from delegate", id);
          return delegate.findById(id);
        });
  }

  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload triggered from {}, invalidating all cached rules", event.getSource());
      cache.invalidateAll();
    } else {
      log.info(
          "Reload triggered for ruleSetId: {} from {}", event.getRuleSetId(), event.getSource());
      cache.invalidate(event.getRuleSetId());
    }
  }
}
```

### 0.4에서 달라진 점: get/load/put → getOrLoad

0.3.x의 `findById`는 **캐시 조회 → 미스면 delegate 호출 → put** 세 단계를 프로바이더가 직접 밟았습니다.
그 사이에 락이 없으므로, 전체 리로드 직후처럼 수천 개 요청이 동시에 미스를 내면 **요청마다** MongoDB를
때렸습니다(캐시 스탬피드).

0.4는 그 세 단계를 캐시에게 한 번에 넘깁니다. 캐시 구현이 원자적 로딩을 제공하면 미스가 하나의
로드로 합쳐지고, 그렇지 않은 구현은 인터페이스 기본 구현으로 기존 동작을 유지합니다.

---

## 3. RuleCache와 CaffeineRuleCache

### RuleCache 인터페이스

```java
// RuleCache.java - 실제 코드
public interface RuleCache {

  /** 캐시에서 RuleSet 조회 */
  Optional<RateLimitRuleSet> get(String ruleSetId);

  /**
   * Returns the cached rule set, loading and caching it on a miss.
   *
   * <p>The default implementation is a plain get/load/put sequence and is not atomic:
   *
   * <ul>
   *   <li>concurrent misses for the same id can each run the loader;
   *   <li>an {@link #invalidate(String)} (a rule reload) that lands while the loader is running is
   *       lost: the value loaded before the reload is put afterwards and served until the next
   *       invalidation or expiry.
   * </ul>
   *
   * <p>Implementations backed by a cache with atomic loading should override this method to
   * collapse concurrent loads, to cache negative results, and to drop a load that raced an
   * invalidation (for example by comparing an invalidation generation before and after loading).
   */
  default Optional<RateLimitRuleSet> getOrLoad(
      String ruleSetId, Function<String, Optional<RateLimitRuleSet>> loader) {
    Optional<RateLimitRuleSet> cached = get(ruleSetId);
    if (cached.isPresent()) {
      return cached;
    }
    Optional<RateLimitRuleSet> loaded = loader.apply(ruleSetId);
    loaded.ifPresent(ruleSet -> put(ruleSetId, ruleSet));
    return loaded;
  }

  /** RuleSet을 캐시에 저장 */
  void put(String ruleSetId, RateLimitRuleSet ruleSet);

  /** 특정 RuleSet 캐시 무효화 */
  void invalidate(String ruleSetId);

  /** 모든 캐시 무효화 */
  void invalidateAll();

  /** 현재 캐시된 RuleSet ID 목록 반환 (폴링 전략이 사용) */
  Set<String> getCachedRuleSetIds();

  /** 캐시 크기 반환 */
  int size();

  /** 캐시 통계 반환 (선택적) */
  default Optional<CacheStats> getStats() {
    return Optional.empty();
  }
}
```

`getOrLoad`가 `default` 메서드인 것은 의도된 선택입니다. 기존 `RuleCache` 구현은 그대로 컴파일되고
동작하며, 원자적 로딩을 지원하는 구현만 오버라이드해서 이득을 봅니다.

### CaffeineRuleCache (Caffeine 기반 구현)

```java
// CaffeineRuleCache.java - 실제 코드 (요지)
public class CaffeineRuleCache implements RuleCache {

  /** Negative TTL applied when none is configured explicitly. */
  public static final Duration DEFAULT_NEGATIVE_TTL = Duration.ofSeconds(5);

  /**
   * Monotonically increasing generation counter. Incremented whenever a cache entry or the entire
   * cache is invalidated. {@link #getOrLoad} snapshots the generation before calling the loader and
   * checks it afterwards; if the generation changed while the loader ran (because an invalidation
   * raced in), the miss-cache entry is not recorded — the next request should see the fresh rule.
   */
  private final AtomicLong generation = new AtomicLong();

  private final Cache<String, RateLimitRuleSet> cache;
  private final Cache<String, Boolean> missCache;
  private final Duration ttl;
  private final int maxSize;
  private final Duration negativeTtl;

  public CaffeineRuleCache(Duration ttl, int maxSize, Duration negativeTtl) {
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    this.maxSize = maxSize;
    this.negativeTtl = negativeTtl != null ? negativeTtl : Duration.ZERO;

    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxSize)
            .recordStats()
            .removalListener(
                (key, value, cause) -> {
                  if (cause.wasEvicted()) {
                    log.debug("Rule set evicted from cache: {} (cause: {})", key, cause);
                  }
                })
            .build();

    boolean negativeCachingEnabled = !this.negativeTtl.isZero() && !this.negativeTtl.isNegative();
    this.missCache =
        negativeCachingEnabled
            ? Caffeine.newBuilder()
                .expireAfterWrite(this.negativeTtl)
                .maximumSize(Math.max(maxSize, 1))
                .build()
            : null;
  }

  @Override
  public Optional<RateLimitRuleSet> getOrLoad(
      String ruleSetId, Function<String, Optional<RateLimitRuleSet>> loader) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    Objects.requireNonNull(loader, "loader must not be null");

    RateLimitRuleSet cached = cache.getIfPresent(ruleSetId);
    if (cached != null) {
      return Optional.of(cached);
    }

    if (missCache != null && missCache.getIfPresent(ruleSetId) != null) {
      log.trace("Negative cache hit for rule set: {}", ruleSetId);
      return Optional.empty();
    }

    // Snapshot the generation before calling the loader.  If an invalidation races in while the
    // loader runs, the generation will have changed, and the negative entry must not be recorded —
    // the next request will then re-run the loader against the freshly-loaded (or absent) rule.
    long genBefore = generation.get();

    // Caffeine holds the per-key lock for the duration of the mapping function, so concurrent
    // misses for the same id collapse into a single load. Returning null records no mapping.
    RateLimitRuleSet loaded = cache.get(ruleSetId, key -> loader.apply(key).orElse(null));

    if (loaded == null) {
      if (missCache != null && generation.get() == genBefore) {
        missCache.put(ruleSetId, Boolean.TRUE);
        log.debug("Rule set not found, caching the miss for {}: {}", negativeTtl, ruleSetId);
      }
      return Optional.empty();
    }
    return Optional.of(loaded);
  }

  @Override
  public void invalidate(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    generation.incrementAndGet();
    cache.invalidate(ruleSetId);
    invalidateMiss(ruleSetId);
    log.debug("Invalidated rule set from cache: {}", ruleSetId);
  }

  @Override
  public void invalidateAll() {
    generation.incrementAndGet();
    cache.invalidateAll();
    if (missCache != null) {
      missCache.invalidateAll();
    }
    log.info("Invalidated all cached rule sets");
  }
}
```

### 부하 상태에서 이 캐시를 안전하게 만드는 두 가지 속성

클래스 Javadoc이 직접 말하는 두 속성입니다.

**(1) 원자적 로딩.** `cache.get(key, mappingFunction)`은 키별 락을 매핑 함수가 끝날 때까지 잡습니다.
같은 id에 대한 동시 미스가 **한 번의 로드로 합쳐집니다.** 이전의 get/load/put 조합은 in-flight
요청 전부가 MongoDB에 도달했습니다.

**(2) 부정 캐싱(negative caching).** 존재하지 않는 룰셋 id를 짧은 TTL 동안 기억합니다. 오타 하나가
**모든 요청**에서 룰 저장소 쿼리가 되는 일이 사라집니다.

### generation 카운터: 부정 캐싱의 경쟁 조건

부정 캐싱만 있으면 다음 순서에서 문제가 생깁니다.

```
스레드 A: getOrLoad("new-rules") → 미스 → loader 실행 중 (아직 없음)
                                            │
관리자:        룰셋 "new-rules" 생성 → Pub/Sub → invalidate("new-rules")
                                            │
스레드 A: loader가 empty 반환 → 부정 캐시에 기록  ← 방금 만들어진 룰셋을 5초간 못 봄
```

`generation`은 이것을 막습니다. loader 실행 **전에** 세대 번호를 찍고, 끝난 뒤 비교합니다. 그 사이
무효화가 끼어들었다면 세대가 달라지므로 **부정 항목을 기록하지 않습니다.** 다음 요청이 loader를
다시 실행해 갓 만들어진 룰을 봅니다.

`invalidate`와 `invalidateAll`이 둘 다 `generation.incrementAndGet()`을 먼저 호출하는 이유입니다.

### 캐시 설정 (application.yml)

```yaml
fluxgate:
  reload:
    cache:
      enabled: true
      ttl: 5m            # 캐시 TTL
      max-size: 1000     # 최대 캐시 크기
      negative-ttl: 5s   # "없음" 결과 TTL. 0이면 부정 캐싱 비활성화
```

`negative-ttl`을 길게 잡으면 새로 만든 룰셋이 보이기까지의 지연이 길어지고, 짧게 잡으면 오타 id의
저장소 부하가 늘어납니다. 5초는 Pub/Sub 무효화가 도달하는 시간과 비슷한 수준으로 맞춘 값입니다.

---

## 4. KeyResolver

```
fluxgate-core/src/main/java/org/fluxgate/core/key/
├── KeyResolver.java            # 인터페이스
├── LimitScopeKeyResolver.java  # LimitScope 기반 구현
├── MissingKeyBehavior.java     # 스코프 값이 없을 때의 동작
├── KeyValueSanitizer.java      # 키 값 정규화
└── RateLimitKey.java           # 키 값 객체
```

`KeyResolver`는 요청 컨텍스트와 규칙의 `LimitScope`에 따라 Rate Limit 키를 만듭니다.

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

  /** Default key used for {@link LimitScope#GLOBAL}; it needs no discriminator. */
  private static final String GLOBAL_KEY = "global";

  /** Key value used when {@link LimitScope#PER_IP} has no client IP to work with. */
  private static final String UNKNOWN_IP = "unknown";

  private static final String PREFIX_IP = "ip:";
  private static final String PREFIX_USER = "user:";
  private static final String PREFIX_API_KEY = "key:";
  private static final String PREFIX_CUSTOM = "custom:";

  private final MissingKeyBehavior missingKeyBehavior;

  /** Creates a resolver that falls back to the client IP when a scoped value is missing. */
  public LimitScopeKeyResolver() {
    this(MissingKeyBehavior.FALLBACK_TO_IP);
  }

  public LimitScopeKeyResolver(MissingKeyBehavior missingKeyBehavior) {
    this.missingKeyBehavior =
        Objects.requireNonNull(missingKeyBehavior, "missingKeyBehavior must not be null");
  }

  @Override
  public RateLimitKey resolve(RequestContext context, RateLimitRule rule) {
    LimitScope scope = rule.getScope();
    if (scope == null) {
      scope = LimitScope.PER_IP; // default
    }

    String keyValue;
    switch (scope) {
      case GLOBAL:
        keyValue = GLOBAL_KEY;
        break;
      case PER_IP:
        keyValue = resolveClientIp(context, rule, scope);
        break;
      case PER_USER:
        keyValue = resolveUserId(context, rule, scope);
        break;
      case PER_API_KEY:
        keyValue = resolveApiKey(context, rule, scope);
        break;
      case CUSTOM:
        keyValue = resolveCustom(context, rule, scope);
        break;
      default:
        keyValue = resolveClientIp(context, rule, scope);
        break;
    }

    log.debug("Resolved key for rule {} with scope {}: {}", rule.getId(), scope, mask(keyValue));

    return RateLimitKey.ofSanitized(keyValue);
  }

  private String resolveClientIp(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String clientIp = context != null ? context.getClientIp() : null;
    if (clientIp == null || clientIp.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("clientIp is null/empty, using '{}' as fallback", UNKNOWN_IP);
      return PREFIX_IP + UNKNOWN_IP;
    }
    return PREFIX_IP + KeyValueSanitizer.sanitize(clientIp);
  }

  private String resolveUserId(RequestContext context, RateLimitRule rule, LimitScope scope) {
    String userId = context != null ? context.getUserId() : null;
    if (userId == null || userId.isEmpty()) {
      if (missingKeyBehavior == MissingKeyBehavior.REJECT) {
        throw new MissingRateLimitKeyException(rule.getId(), scope);
      }
      log.debug("userId is null/empty for PER_USER scope, falling back to clientIp");
      return resolveClientIp(context, rule, scope);
    }
    return PREFIX_USER + KeyValueSanitizer.sanitize(userId);
  }

  /**
   * Masks a key value for logging: user ids and API keys must never reach the logs in clear text.
   */
  private static String mask(String keyValue) {
    if (keyValue == null || keyValue.length() <= 4) {
      return "***";
    }
    return keyValue.substring(0, 4) + "***";
  }
}
```

(`resolveApiKey`는 `PREFIX_API_KEY`, `resolveCustom`은 `PREFIX_CUSTOM`으로 같은 형태를 따릅니다.)

### 0.4의 세 가지 변경점

#### (1) 스코프 접두사

모든 키에 출처를 나타내는 접두사가 붙습니다.

| LimitScope | 키 소스 | 생성 키 예시 |
|------------|--------|-------------|
| `GLOBAL` | 상수 | `global` |
| `PER_IP` | `RequestContext.clientIp` | `ip:192.168.1.100` |
| `PER_USER` | `RequestContext.userId` | `user:user-123` |
| `PER_API_KEY` | `RequestContext.apiKey` | `key:api-key-abc` |
| `CUSTOM` | `attributes.get(keyStrategyId)` | `custom:tenant-456` |

접두사가 없던 0.3.x에서는 **서로 다른 스코프가 같은 버킷을 공유할 수 있었습니다.** `userId`가
하필 `10.0.0.5`인 사용자와 실제로 그 IP에서 오는 클라이언트가 `PER_USER` 규칙과 `PER_IP` 규칙에서
같은 키 값을 만들어냈습니다. 한쪽이 상대의 quota를 소진시킬 수 있다는 뜻입니다.

`CUSTOM` 스코프의 복합 값은 구분자를 그대로 유지합니다. `ip:10.0.0.1:user:u-1`로 만든 값은
`custom:ip:10.0.0.1:user:u-1`이 됩니다. 직접 키를 조립할 때는 각 성분에 접두사를 붙여 모호해지지
않게 하세요.

#### (2) MissingKeyBehavior

```java
// MissingKeyBehavior.java - 실제 코드
public enum MissingKeyBehavior {

  /**
   * Fall back to the client IP. The resulting key carries the {@code ip:} prefix, so an anonymous
   * request can never share a bucket with a user or API key whose identifier happens to look like
   * an IP address.
   *
   * <p>Note that the rule's limits still apply unchanged, so an anonymous caller inherits the quota
   * configured for the authenticated tier. Use {@link #REJECT} when that is not acceptable.
   */
  FALLBACK_TO_IP,

  /**
   * Reject the request. The resolver throws {@link
   * org.fluxgate.core.exception.MissingRateLimitKeyException}; rate limiter implementations catch
   * it and return a rejected result.
   */
  REJECT
}
```

```yaml
fluxgate:
  ratelimit:
    missing-key-behavior: FALLBACK_TO_IP  # 기본값. REJECT로 바꿀 수 있습니다
```

`FALLBACK_TO_IP`의 함정은 Javadoc이 직접 경고합니다. **규칙의 한도는 그대로 적용됩니다.**
인증된 사용자에게 주려던 넉넉한 quota를 익명 호출자가 그대로 물려받습니다. `X-User-Id` 헤더를
빼고 요청하는 것만으로 상위 티어 한도를 얻는 배포라면 `REJECT`를 쓰세요.

폴백 경로가 `PREFIX_IP`를 붙여 반환하는 것도 같은 맥락입니다. 폴백된 키는 스스로가 IP 출처임을
드러내며, `user:`로 위장하지 않습니다.

#### (3) 로그 마스킹

`resolve`가 찍는 DEBUG 로그는 이제 `mask(keyValue)`를 거칩니다. userId와 API 키가 평문으로 로그에
남으면 그 로그 파일 자체가 자격 증명 저장소가 됩니다. 앞 4글자 + `***`만 남습니다.

### 폴백 동작 정리

`missing-key-behavior=FALLBACK_TO_IP`(기본값)일 때:

```
PER_USER    (userId 없음)      → PER_IP로 폴백  → "ip:..."
PER_API_KEY (apiKey 없음)      → PER_IP로 폴백  → "ip:..."
CUSTOM      (attribute 없음)   → PER_IP로 폴백  → "ip:..."
CUSTOM      (keyStrategyId 없음) → PER_IP로 폴백 → "ip:..."
PER_IP      (clientIp 없음)    → "ip:unknown"
```

`missing-key-behavior=REJECT`일 때는 위 모든 경우가 `MissingRateLimitKeyException`이 되고,
`RedisRateLimiter`와 `Bucket4jRateLimiter`가 이를 잡아 거부 결과로 바꿉니다.

```java
// RedisRateLimiter.java - 실제 코드
RateLimitKey logicalKey;
try {
  logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
} catch (MissingRateLimitKeyException e) {
  return record(context, ruleSet, missingKeyResult(e, rule));
}
```

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Builds the rejected result for a request whose key could not be resolved.
 *
 * <p>A synthetic key is used because a rejected {@link RateLimitResult} requires one, and no real
 * key exists by definition. There is nothing to wait for: the request is missing a header, not
 * tokens.
 */
private static RateLimitResult missingKeyResult(
    MissingRateLimitKeyException e, RateLimitRule rule) {

  log.debug("Rejecting request because no rate limit key could be resolved: {}", e.getMessage());
  return RateLimitResult.builder(RateLimitKey.of("missing-key:", rule.getId()))
      .allowed(false)
      .matchedRule(rule)
      .policy(rule.getOnLimitExceedPolicy())
      .remainingTokens(0L)
      .nanosToWaitForRefill(0L)
      .limit(-1L)
      .resetTimeMillis(-1L)
      .build();
}
```

`missing-key:<ruleId>` 합성 키, `limit = -1`, `resetTimeMillis = -1`, 대기 시간 0.
`-1`이므로 헤더 라이터가 `X-RateLimit-Limit`과 `Reset` 헤더를 내보내지 않고, 존재하지 않는 quota를
광고하지 않습니다.

### KeyValueSanitizer

```java
// KeyValueSanitizer.java - 실제 코드 (Javadoc 발췌)
/**
 * <p>The output only ever contains {@code [A-Za-z0-9._:@-]} and is at most {@link #MAX_LENGTH}
 * characters long. The mapping is <em>injective</em> short of a hash collision, so two identities
 * cannot share a bucket or an allow/deny entry because sanitising made them look alike:
 *
 * <ul>
 *   <li>A value of at most {@link #MAX_LENGTH} allowed characters that does not start with {@code
 *       h:} is returned unchanged.
 *   <li>Any other value of at most {@code MAX_LENGTH - 19} characters becomes {@code h:}, the value
 *       with every disallowed character replaced by {@code _}, {@code :} and the first 16 hex
 *       digits of the SHA-256 of the original value, e.g. {@code a+1} becomes {@code h:a_1:<16
 *       hex>}. The 16 hex digits are a SHA-256 digest truncated to 64 bits: two such values only
 *       collide if they have the same restricted form <em>and</em> the same 64-bit digest prefix.
 *   <li>Longer values become {@code h:} followed by the full 64-digit SHA-256 hex of the original
 *       value.
 * </ul>
 *
 * <p>The {@code h:} marker is what keeps the mapping injective: an unchanged value never starts
 * with it, and every rewritten value does. As a consequence sanitising is <em>not</em> idempotent:
 * sanitising a rewritten value rewrites it again (no injective mapping can be idempotent unless it
 * is the identity). Sanitise raw values exactly once.
 */
public final class KeyValueSanitizer {

  /** Maximum sanitised length; longer or rewritten values are hashed to stay within it. */
  public static final int MAX_LENGTH = 256;
```

정규화가 막는 것 네 가지:

| 공격 | 정규화 없을 때 | 정규화 후 |
|-----|--------------|----------|
| 키 공간 팽창 | 1MB짜리 `X-User-Id` 헤더가 1MB짜리 Redis 키가 됨 | 237자를 넘는 비정상 값·256자를 넘는 값은 `h:<64 hex>`(66자)로 대체 |
| SCAN 글로브 밀입 | 키 값의 `*`, `?`, `[`, `]`가 `SCAN MATCH` 패턴을 바꿈 | `_`로 치환된 `h:<제한된 값>:<16 hex>` |
| 키 파싱 붕괴 | 제어문자·개행이 키/로그 구조를 깨뜨림 | `_`로 치환된 `h:<제한된 값>:<16 hex>` |
| 버킷 공유(충돌) | 단순 치환이면 `a+1`과 `a_1`이 같은 버킷 | `h:` 마커와 다이제스트로 구분 (`h:a_1:<16 hex>` ≠ `a_1`) |

SHA-256 다이제스트는 JVM 간 안정적이므로, 같은 값은 어느 노드에서도 같은 버킷으로 갑니다.
`hashCode()`를 쓰면 이 보장이 깨집니다. 짧은 형태의 16자리는 64비트로 자른 다이제스트지만,
충돌하려면 제한된 값까지 같아야 합니다.

새니타이즈는 **멱등이 아닙니다**. `h:`로 시작하는 값은 다시 인코딩되므로 원본 값을 정확히 한 번만
새니타이즈해야 합니다. `LimitScopeKeyResolver`는 접두사 뒤의 값만 새니타이즈한 뒤
`RateLimitKey.ofSanitized`로 키를 만들어, 접두사가 해시 바깥에 남고(`user:h:<64 hex>`) 두 번
인코딩되지 않습니다. 허용/차단 항목 정규화(`normalizeResolvedKey`)는 이미 인코딩된 형태
(`user:h:...`)를 그대로 두므로 멱등입니다.

### RateLimitKey (값 객체)

```java
// RateLimitKey.java - 실제 코드 (발췌)
public final class RateLimitKey {

  /** Longest prefix accepted by {@link #of(String, String)}. */
  public static final int MAX_PREFIX_LENGTH = 64;

  private final String key;

  public RateLimitKey(String key) {
    this(KeyValueSanitizer.sanitize(Objects.requireNonNull(key, "key must not be null")), true);
  }

  /** 이미 새니타이즈된 값(접두사 + 새니타이즈된 값)으로 키를 만듭니다. 패키지 전용. */
  static RateLimitKey ofSanitized(String sanitizedKey) { ... }

  /** 문자열 전체(접두사 포함)를 새니타이즈합니다. 결과는 최대 256자. */
  public static RateLimitKey of(String key) {
    return new RateLimitKey(key);
  }

  /** 접두사는 유지하고 값만 새니타이즈합니다. 값 부분이 최대 256자, 접두사는 그 위에 더해집니다. */
  public static RateLimitKey of(String prefix, String rawValue) {
    // prefix: 64자 이하, [A-Za-z0-9._:@-], h:로 시작 불가 — 아니면 IllegalArgumentException
    return ofSanitized(prefix + KeyValueSanitizer.sanitize(rawValue));
  }

  public String value() {
    return key;
  }
}
```

정규화가 **생성 경로에서** 일어나는 것이 핵심입니다. 사용자가 작성한 `KeyResolver`가 어떤 값을
만들어도, 공개 팩터리로 `RateLimitKey`가 되는 순간 정규화를 통과합니다. 각 resolver가 스스로
정규화하도록 맡겼다면 하나만 빠뜨려도 구멍이 됩니다. 두 공개 팩터리의 차이:

| 호출 | 결과 |
|------|------|
| `RateLimitKey.of("user:", "a+1")` | `user:h:a_1:<16 hex>` — 기본 리졸버와 같은 모양 |
| `RateLimitKey.of("user:a+1")` | `h:user:a_1:<16 hex>` — 접두사까지 함께 인코딩 |

커스텀 리졸버의 키가 기본 리졸버나 `user:` 허용/차단 항목과 일치해야 한다면 `of(prefix, rawValue)`를
쓰세요. 엔진이 접근 제어용으로 만드는 합성 폴백 키도 `RateLimitKey.of("ip:", clientIp)`로 같은
모양입니다.

그래서 `RedisRateLimiter.buildBucketKey`는 `key.value()`를 다시 정규화하지 않습니다.

```java
// RedisRateLimiter.java - 실제 코드 주석
// ruleSetId and ruleId are escaped so that Redis hash-tag delimiters (:, {, }) and SCAN glob
// metacharacters (*, ?, [, ], \) embedded in operator-controlled strings cannot alter the key
// namespace or the bucket key pattern - reversibly, so two ids can never share a bucket. The
// band label is escaped too: a label "x:fw" must not land on the FIXED_WINDOW counter of a band
// labelled "x". key.value() is already sanitised by the core.
```

---

## 관련 문서

- [Handler Layer Deep Dive](handler-layer.ko.md)
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md)
- [Hot Reload Deep Dive](hot-reload.ko.md)
- [KeyResolver 커스터마이징](../../customization/key-resolver.ko.md)
- [아키텍처 개요](../README.ko.md)
