# Redis RateLimiter Module Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에, 전체 계층을 한 문서로 훑는 서술은
> [아키텍처 Deep Dive](../../../ARCHITECTURE_DEEP_DIVE.ko.md)에 있습니다.

이 문서는 `fluxgate-redis-ratelimiter` 모듈을 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md) | [English](../../../en/architecture/redis-ratelimiter.md)

---

## 목차

1. [모듈 구조](#1-모듈-구조)
2. [RedisRateLimiter](#2-redisratelimiter)
3. [버킷 키 레이아웃](#3-버킷-키-레이아웃)
4. [Redis Connection Layer](#4-redis-connection-layer)
5. [RedisRateLimiterConfig](#5-redisratelimiterconfig)
6. [사용 예제](#6-사용-예제)

---

## 1. 모듈 구조

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
├── RedisRateLimiter.java              # RateLimiter 인터페이스 구현
├── config/
│   └── RedisRateLimiterConfig.java    # 설정 및 초기화
├── connection/
│   ├── RedisConnectionProvider.java   # 연결 추상화 인터페이스
│   ├── StandaloneRedisConnection.java # Standalone 모드 구현
│   ├── ClusterRedisConnection.java    # Cluster 모드 구현
│   ├── RedisConnectionFactory.java    # 연결 팩토리
│   ├── RedisUriUtils.java             # URI 비밀정보 마스킹
│   └── RedisConnectionException.java  # 예외 클래스
├── store/
│   ├── RedisTokenBucketStore.java     # 토큰 버킷 저장소
│   ├── BucketState.java               # 버킷 상태 객체
│   ├── RedisRuleSetStore.java         # RuleSet 저장소 (@Deprecated 0.4.0)
│   └── RuleSetData.java               # RuleSet 데이터 객체
├── script/
│   ├── LuaScriptRegistry.java         # 스토어별 스크립트 본문 + SHA
│   ├── LuaScriptLoader.java           # @Deprecated 0.4.0 (호환용)
│   └── LuaScripts.java                # @Deprecated 0.4.0 (호환용)
└── health/
    └── RedisHealthCheckerImpl.java    # 헬스체크 구현

fluxgate-redis-ratelimiter/src/main/resources/lua/
└── token_bucket_consume.lua           # 다중 대역 토큰 버킷 스크립트
```

### 0.4에서 사라진 것: 프로세스 전역 스크립트 SHA

`LuaScripts`와 `LuaScriptLoader`는 `static volatile` 슬롯에 스크립트 본문과 SHA를 담고 있었습니다.
한 JVM 안에서 서로 다른 Redis를 가리키는 스토어가 둘 있으면 **서로의 SHA를 덮어씁니다.**

```java
// LuaScripts.java - 실제 코드
/**
 * @deprecated Use {@link LuaScriptRegistry} instead. The static slots here are process-wide, so two
 *     {@code RedisTokenBucketStore} instances pointing at different Redis deployments overwrite
 *     each other's SHA. Nothing inside FluxGate reads this class any more; it is kept only so that
 *     existing callers keep compiling and will be removed in a future release.
 */
@Deprecated(since = "0.4.0", forRemoval = true)
public final class LuaScripts {
```

두 클래스는 **FluxGate 내부에서 더 이상 읽히지 않습니다.** 기존 호출자의 컴파일 호환을 위해 남아
있을 뿐이며, 자리를 이어받은 것이 인스턴스 상태를 갖는 `LuaScriptRegistry`입니다.

### 의존성 관계

```
+-------------------+
|  RedisRateLimiter |  ← RateLimiter 인터페이스 구현
+-------------------+
         |
         | uses (규칙 단위로 1회 호출)
         v
+------------------------+       +---------------------+
|  RedisTokenBucketStore |-owns->|  LuaScriptRegistry  |  ← 스토어마다 독립적인 SHA
+------------------------+       +---------------------+
         |
         | uses
         v
+-------------------------+
| RedisConnectionProvider |  ← Standalone/Cluster 추상화
+-------------------------+
         |
    +----+----+
    |         |
    v         v
+----------+ +----------+
|Standalone| | Cluster  |
|Connection| |Connection|
+----------+ +----------+
```

```java
// RedisTokenBucketStore.java - 실제 코드 주석
/**
 * <p>Each store owns its own {@link LuaScriptRegistry}, so two stores pointing at different Redis
 * deployments in one JVM keep independent script SHAs.
 */
```

---

## 2. RedisRateLimiter

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
└── RedisRateLimiter.java
```

`RateLimiter` 인터페이스의 Redis 기반 구현입니다.

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Redis-backed distributed rate limiter implementation.
 *
 * <p>Properties of this implementation:
 *
 * <ul>
 *   <li>Token buckets live in Redis hashes, one per (rule set, rule, key, band)
 *   <li>The Lua script uses Redis TIME, so all nodes share one clock
 *   <li>Buckets expire through Redis TTL, derived from the band's window. TOKEN_BUCKET and
 *       SLIDING_WINDOW TTLs are capped by {@code fluxgate.redis.max-bucket-ttl}; a FIXED_WINDOW
 *       counter is exempt and expires exactly at the end of its window
 *   <li>Supports multi-band rules (e.g. 10/sec AND 100/min AND 1000/hour)
 * </ul>
 *
 * <p><strong>Atomicity, honestly.</strong> Every band of <em>one</em> rule is evaluated in a single
 * Lua call, so within a rule the decision is atomic and all-or-nothing: a request rejected by the
 * per-minute band does not drain the per-second band. Across <em>rules</em> a rejected request
 * costs nothing either, by one of two strategies:
 *
 * <ol>
 *   <li><strong>One call.</strong> When every key of every matching rule may go into one script
 *       call - always on a standalone Redis, and in a cluster when all keys hash to one slot - all
 *       rules are evaluated together, all-or-nothing, exactly like the bands of one rule.
 *   <li><strong>Compensation.</strong> Otherwise (a cluster, with rules whose hash tags land in
 *       different slots) the rules are charged one by one; when one rejects, or Redis fails, the
 *       rules already charged are refunded by {@link RedisTokenBucketStore#refund}.
 * </ol>
 *
 * <p>Compensation is not atomic, and does not pretend to be. Between a rule's charge and its refund
 * - one round trip per rule - concurrent requests see that rule a permit lower and may be rejected
 * by it; that errs on the strict side and heals itself with the refund. If the refund itself cannot
 * run (the process dies, or Redis fails between charge and refund) the permit stays spent, which is
 * the pre-0.4 behaviour. A refund also only returns what is still there: a fixed window that rolled
 * over, or a sliding sub-bucket that left the window, between charge and refund has nothing left to
 * give back.
 *
 * <p><strong>Cost of a compensated rejection.</strong> When rule {@code k} of {@code n} rejects,
 * the request costs {@code k} consume calls, {@code k - 1} refund calls and up to 8 check-only
 * calls: the rules after the rejecting one are not charged, but are checked so that the reported
 * Retry-After is the longest wait of all rules, not the first one found. Each call is one round
 * trip, possibly to another cluster node. The checks are capped so that a rule set with many
 * cross-slot rules cannot turn one rejected request into an unbounded number of round trips; rules
 * beyond the cap are not consulted, and their wait - if longer - is only discovered when the client
 * retries.
 *
 * <p>Thread-safe and suitable for distributed environments with multiple API gateway nodes.
 *
 * @see RedisTokenBucketStore
 */
public class RedisRateLimiter implements RateLimiter, AutoCloseable {

  /** Prefix shared by every token bucket key. Disjoint from {@code fluxgate:ruleset:}. */
  public static final String BUCKET_KEY_PREFIX = "fluxgate:bucket:";

  private final RedisTokenBucketStore tokenBucketStore;

  /** Rule ids already warned about a window that the bucket TTL cap shortens. */
  private final Set<String> ttlClampWarnedRules = ConcurrentHashMap.newKeySet();

  /**
   * Path pattern matcher used to filter applicable rules via {@link
   * RateLimitRuleSet#getMatchingRules}.
   */
  private final PathPatternMatcher pathMatcher;

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits) {
    return tryConsume(context, ruleSet, permits, pathMatcher);
  }

  @Override
  public RateLimitResult tryConsume(
      RequestContext context, RateLimitRuleSet ruleSet, long permits, PathPatternMatcher matcher) {

    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(ruleSet, "ruleSet must not be null");
    Objects.requireNonNull(matcher, "matcher must not be null");

    if (permits <= 0) {
      throw new IllegalArgumentException("permits must be > 0");
    }

    List<RateLimitRule> rules = ruleSet.getMatchingRules(context, matcher);
    if (rules.isEmpty()) {
      log.debug("No matching rules in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    // ========================================================================
    // Multi-Rule Rate Limiting with Per-Rule Key Resolution
    // ========================================================================
    // Each rule can have a different LimitScope (PER_IP, PER_USER, PER_API_KEY, ...), so the
    // KeyResolver is asked once per rule - for every rule before anything is charged, so a key
    // that cannot be resolved rejects the request without having drained an earlier rule.
    // ========================================================================

    List<RuleCall> calls = new ArrayList<>(rules.size());
    for (RateLimitRule rule : rules) {
      // rules from getMatchingRules are already enabled and match the request path/method
      List<RateLimitBand> bands = rule.getBands();
      if (bands == null || bands.isEmpty()) {
        continue;
      }

      RateLimitKey logicalKey;
      try {
        logicalKey = ruleSet.getKeyResolver().resolve(context, rule);
      } catch (MissingRateLimitKeyException e) {
        return record(context, ruleSet, missingKeyResult(e, rule));
      }
      Objects.requireNonNull(
          logicalKey, "resolved RateLimitKey must not be null for rule: " + rule.getId());

      warnOnceIfBucketTtlClampsWindow(rule, bands);

      List<String> bucketKeys = new ArrayList<>(bands.size());
      for (RateLimitBand band : bands) {
        bucketKeys.add(buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band));
      }
      calls.add(new RuleCall(rule, logicalKey, bands, bucketKeys));
    }

    if (calls.isEmpty()) {
      // All matching rules have empty band lists: nothing to enforce.
      log.debug("No rule with bands in ruleSet {}, nothing to enforce", ruleSet.getId());
      return record(context, ruleSet, RateLimitResult.allowedWithoutRule());
    }

    if (calls.size() > 1) {
      List<String> allKeys = new ArrayList<>();
      for (RuleCall call : calls) {
        allKeys.addAll(call.bucketKeys);
      }
      if (new HashSet<>(allKeys).size() == allKeys.size()
          && tokenBucketStore.canEvaluateAtomically(allKeys)) {
        return record(context, ruleSet, consumeInOneCall(calls, allKeys, permits));
      }
    }
    return record(context, ruleSet, consumeWithCompensation(calls, permits));
  }

  /**
   * Evaluates every rule in a single script call: the bands of all rules are concatenated, so the
   * script's two passes make the whole decision all-or-nothing.
   *
   * <p>The script reports the rejecting band with the longest wait, or on allow the band with the
   * fewest tokens left, in the concatenated order - the same band rule-by-rule evaluation reports,
   * since that checks the rules after a rejecting one for a longer wait as well.
   */
  private RateLimitResult consumeInOneCall(
      List<RuleCall> calls, List<String> allKeys, long permits) {
    List<RateLimitBand> allBands = new ArrayList<>(allKeys.size());
    for (RuleCall call : calls) {
      allBands.addAll(call.bands);
    }

    BucketState state = tokenBucketStore.tryConsume(allKeys, allBands, permits);

    RuleCall call = calls.get(0);
    int localIndex = state.bandIndex();
    for (RuleCall candidate : calls) {
      call = candidate;
      if (localIndex < candidate.bands.size()) {
        break;
      }
      localIndex -= candidate.bands.size();
    }
    RateLimitBand band = bandAt(call.bands, localIndex);

    if (!state.consumed()) {
      Binding rejection = new Binding(call.key, call.rule, band, state);
      logRejected(rejection);
      return rejectedResult(call.key, call.rule, band, state);
    }
    Binding binding = new Binding(call.key, call.rule, band, state);
    logAllowed(binding);
    return allowedResult(binding);
  }

  /**
   * Charges the rules one by one and, when one rejects or Redis fails, refunds the rules already
   * charged. See the class Javadoc for the window in which this is not atomic.
   */
  private RateLimitResult consumeWithCompensation(List<RuleCall> calls, long permits) {
    List<RuleCall> charged = new ArrayList<>(calls.size());
    List<BucketState> chargedStates = new ArrayList<>(calls.size());
    Binding binding = null;

    for (int index = 0; index < calls.size(); index++) {
      RuleCall call = calls.get(index);
      BucketState state;
      try {
        state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
      } catch (RuntimeException e) {
        refund(charged, chargedStates, permits);
        throw e;
      }
      RateLimitBand bindingBand = bandAt(call.bands, state.bandIndex());

      if (!state.consumed()) {
        // No further rule is charged, and the rules already charged get their permits back.
        refund(charged, chargedStates, permits);
        Binding rejection =
            longestWait(
                new Binding(call.key, call.rule, bindingBand, state),
                calls.subList(index + 1, calls.size()),
                permits);
        logRejected(rejection);
        return rejectedResult(rejection.key, rejection.rule, rejection.band, rejection.state);
      }

      charged.add(call);
      chargedStates.add(state);
      if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
        binding = new Binding(call.key, call.rule, bindingBand, state);
      }
    }

    logAllowed(binding);
    return allowedResult(binding);
  }

  // ... longestWait(), refund(), 결과 빌더와 키 헬퍼가 이어집니다
}
```

`tryConsume`은 두 단계로 동작합니다.

1. **수집.** `ruleSet.getMatchingRules(context, matcher)`는 활성화되어 있고 `RuleMatcher`가 요청과
   맞는 규칙만 돌려줍니다(인자 3개짜리 오버로드는 limiter 자신의 매처, 기본값
   `SimpleAntPathMatcher.INSTANCE`를 씁니다). 대역이 있는 규칙마다 키를 해석하고 버킷 키를 만들어
   `RuleCall`에 담습니다. 이 단계에서는 아무것도 차감하지 않으므로, 뒤 규칙에서
   `MissingRateLimitKeyException`이 나도 앞 규칙을 소모하지 않고 거부됩니다.
2. **차감.** `RuleCall`이 둘 이상이고, 버킷 키가 서로 겹치지 않고,
   `tokenBucketStore.canEvaluateAtomically(allKeys)`가 참이면 모든 규칙을 한 번의 스크립트 호출로
   평가합니다(`consumeInOneCall`). 그 밖의 경우 - 규칙이 하나이거나 규칙이 클러스터 슬롯에 흩어진
   경우 - 규칙을 하나씩 차감합니다(`consumeWithCompensation`). 규칙이 하나면 정확히 한 번의 호출입니다.

### 0.4에서 달라진 흐름: 대역마다 왕복 → 규칙마다 한 번

0.3.x의 루프는 **대역마다** `tokenBucketStore.tryConsume(bucketKey, band, permits)`를 호출했습니다.
그 결과가 다음 두 가지 문제였습니다.

**문제 1: 다중 대역 규칙이 명목 한도보다 낮게 동작.**
"초당 10개 AND 분당 100개" 규칙에서 분당 대역이 거부하는 순간에도, 그 앞의 초당 대역은 이미 토큰을
차감한 상태였습니다. 거부된 요청이 허용했을 대역의 토큰을 계속 빨아먹으므로 실효 한도가 설정값보다
낮아집니다.

**문제 2: 왕복 횟수.** 대역 3개 규칙이면 Redis 왕복이 3번이었습니다.

0.4는 한 규칙의 **모든 대역 키를 한 번의 Lua 호출**에 넘깁니다. 스크립트가 2패스로 처리하므로
전부 차감하거나 전무입니다.

```java
// RedisRateLimiter.java - 실제 코드 (수집 단계, 이어서 consumeWithCompensation)
List<String> bucketKeys = new ArrayList<>(bands.size());
for (RateLimitBand band : bands) {
  bucketKeys.add(buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band));
}
calls.add(new RuleCall(rule, logicalKey, bands, bucketKeys));

// ...

state = tokenBucketStore.tryConsume(call.bucketKeys, call.bands, permits);
```

### 규칙 간: 한 번의 호출, 아니면 보상

클래스 Javadoc의 제목이 **"Atomicity, honestly"** 인 이유입니다. 규칙마다 키가 다르고, 클러스터에서
다른 키는 다른 해시 슬롯에 살 수 있기 때문에 항상 하나의 Lua 호출에 넣을 수는 없습니다. 0.4는 두
전략을 씁니다. 어느 쪽이든 **모든 규칙의 키를 먼저 해석**하므로, 뒤 규칙의 키가 없어 거부되는 요청도
앞 규칙을 차감하지 않습니다.

**1. 한 번의 호출 (all-or-nothing).** 모든 규칙의 키가 한 스크립트 호출에 들어갈 수 있고
(단독 Redis는 항상, 클러스터는 모든 키가 같은 슬롯일 때 - `RedisTokenBucketStore.canEvaluateAtomically`)
같은 버킷 키가 두 번 나오지 않으면, 모든 규칙의 대역을 이어 붙여 한 번의 `token_bucket_consume.lua`
호출로 평가합니다(`consumeInOneCall`). 스크립트의 2패스가 그대로 규칙 간 원자성이 됩니다. 결과의 대역
인덱스는 이어 붙인 목록 기준이므로 해당 규칙으로 되돌려 매핑합니다.

**2. 보상 (compensation).** 그렇지 않으면 규칙을 하나씩 차감하고, 거부(또는 Redis 장애)가 나면 이미
차감한 규칙을 `token_bucket_refund.lua`로 환불합니다.

```
규칙 3개 룰셋, 서로 다른 슬롯, 세 번째 규칙이 거부하는 경우:

Rule 1 (PER_IP)      → 허용, 토큰 1개 차감  → 환불됨
Rule 2 (PER_USER)    → 허용, 토큰 1개 차감  → 환불됨
Rule 3 (GLOBAL)      → 거부 → 앞선 규칙 환불 후 반환

요청은 429를 받고, Rule 1, 2의 남은 토큰은 요청 전과 같습니다.
```

환불은 차감한 상태만 되돌립니다. TOKEN_BUCKET은 용량을 넘지 않게 더하고, SLIDING_WINDOW는 차감 시각
(consume 스크립트가 돌려주는 Redis TIME, `BucketState.redisTimeMicros()`)의 서브 버킷을 0 아래로
내려가지 않게 줄이며, FIXED_WINDOW는 카운터가 아직 차감한 그 윈도를 셀 때만 줄입니다.

**보상의 남은 비원자성 (솔직하게):**

1. 차감과 환불 사이(규칙당 한 번의 왕복) 동시 요청은 앞선 규칙을 한 개 적게 봅니다. 엄격한 쪽으로
   틀리고, 환불로 스스로 회복됩니다.
2. 환불이 실행되지 못하면(프로세스 종료, 그 사이 Redis 장애) 토큰은 소비된 채 남습니다. 0.3.x와 같은
   결과입니다.
3. 차감과 환불 사이에 고정 윈도가 넘어가거나 슬라이딩 서브 버킷이 윈도 밖으로 나가면 되돌릴 것이
   없습니다.
4. 가장 긴 대기 시간을 찾는 데도 왕복이 듭니다. `n`개 중 `k`번째 규칙이 거부하면 요청은 소비 `k`번,
   환불 `k - 1`번, 그리고 그 뒤 규칙들에 대한 check-only 호출(`RedisTokenBucketStore.check`)을 최대
   **8**번 합니다. Retry-After가 모든 규칙 중 가장 긴 대기 시간이 되도록 하기 위해서입니다. 8개를
   넘는 규칙은 확인하지 않으므로, 그중 더 긴 대기 시간은 클라이언트가 재시도할 때 드러납니다.

클러스터에서 엄격한 원자성이 필요하면 **대역 여러 개를 가진 규칙 하나**를 쓰세요.

### binding 대역 선택

허용 경로에서는 **토큰이 가장 적게 남은** 결과를 보고합니다. 한 번의 호출에서는 스크립트가 그 대역을
직접 돌려주고, 보상 경로에서는 규칙을 모두 차감하면서 비교합니다.

```java
// RedisRateLimiter.java - 실제 코드 (consumeWithCompensation)
if (binding == null || state.remainingTokens() < binding.state.remainingTokens()) {
  binding = new Binding(call.key, call.rule, bindingBand, state);
}
```

`Binding`은 그 순간 가장 제약이 큰 (규칙, 키, 대역, 상태) 묶음입니다.

```java
// RedisRateLimiter.java - 실제 코드
/** The (rule, key, band, state) tuple currently considered the most restrictive. */
private static final class Binding {
  private final RateLimitKey key;
  private final RateLimitRule rule;
  private final RateLimitBand band;
  private final BucketState state;
}
```

HTTP 헤더에 나가는 `X-RateLimit-Remaining`은 이 값입니다. 가장 여유 있는 대역을 보고하면 클라이언트가
실제보다 훨씬 큰 quota를 믿게 됩니다.

### 버킷 TTL 상한이 윈도보다 짧을 때 경고

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Warns once per rule when the bucket TTL cap is shorter than one of its windows.
 *
 * <p>The Lua script clamps TOKEN_BUCKET and SLIDING_WINDOW TTLs to {@code
 * fluxgate.redis.max-bucket-ttl}, which means a bucket of a longer window can expire - and be
 * re-initialised full - before its window is over. That is the deliberate trade against letting
 * forgeable identity keys occupy Redis for weeks, but it changes what the rule enforces, so an
 * operator must be told rather than left to discover it. FIXED_WINDOW counters are exempt (they
 * expire at their window end), so they never trigger the warning.
 */
private void warnOnceIfBucketTtlClampsWindow(RateLimitRule rule, List<RateLimitBand> bands) {
  long capSeconds = tokenBucketStore.getMaxBucketTtl().getSeconds();

  for (RateLimitBand band : bands) {
    if (!ttlCapShortensWindow(band, capSeconds)) {
      continue;
    }
    long ttlSeconds = bucketTtlSeconds(band);

    String ruleId = rule.getId() != null ? rule.getId() : "unknown";
    if (ttlClampWarnedRules.add(ruleId)) {
      log.warn(
          "Rule '{}' band '{}' has a {}s window, whose bucket would need a {}s TTL, but "
              + "fluxgate.redis.max-bucket-ttl is {}s: the bucket expires early and the window is "
              + "effectively shortened. Raise max-bucket-ttl for this deployment, or use a scope "
              + "with bounded cardinality for long windows.",
          ruleId,
          band.getKeyLabel(),
          band.getWindow().getSeconds(),
          ttlSeconds,
          capSeconds);
    }
    return;
  }
}

/**
 * Tells whether {@code max-bucket-ttl} cuts the bucket of this band short. Only TOKEN_BUCKET and
 * SLIDING_WINDOW buckets are capped; a FIXED_WINDOW counter expires at its window end whatever
 * the cap.
 */
static boolean ttlCapShortensWindow(RateLimitBand band, long capSeconds) {
  return band.getAlgorithm() != RateLimitAlgorithm.FIXED_WINDOW
      && bucketTtlSeconds(band) > capSeconds;
}

/** The TTL the Lua script would give this band's bucket without the cap: window + 10%. */
private static long bucketTtlSeconds(RateLimitBand band) {
  return (long) Math.ceil(band.getWindow().getSeconds() * 1.1);
}
```

30일 윈도 규칙을 기본 상한(7일)으로 돌리면 버킷이 7일마다 만료되고, 만료된 버킷은 다음 요청에서
**가득 찬 상태로 재생성됩니다.** 즉 규칙이 실제로 강제하는 것은 30일 한도가 아닙니다. 조용히 넘어가지
않고 규칙마다 한 번 WARN을 남기는 이유입니다.

### 로그에 키가 평문으로 남지 않습니다

```java
// RedisRateLimiter.java - 실제 코드
/** Masks a resolved key for logging: the first few characters, then {@code ***}. */
private static String mask(RateLimitKey key) {
  String value = key.value();
  if (value.length() <= MASK_VISIBLE_CHARS) {
    return "***";
  }
  return value.substring(0, MASK_VISIBLE_CHARS) + "***";
}
```

### close()는 아무것도 닫지 않습니다

```java
// RedisRateLimiter.java - 실제 코드
/**
 * No-op, kept so that the limiter can be used in try-with-resources and as a Spring bean without
 * surprises.
 *
 * <p>This limiter owns neither the {@link RedisTokenBucketStore} nor the Redis connection behind
 * it: both are supplied by the caller and are closed by whoever created them (typically {@code
 * RedisRateLimiterConfig} or the Spring context). Closing them here would shut down a connection
 * other beans still use.
 */
@Override
public void close() {
  log.debug("RedisRateLimiter closed; the token bucket store and connection are not owned by it");
}
```

소유하지 않은 자원을 닫는 것이 더 위험합니다. `@Bean`으로 등록된 limiter가 컨텍스트 종료 시
다른 빈이 여전히 쓰는 Lettuce 연결을 내려버리면 종료 순서에 따라 예외가 쏟아집니다.

---

## 3. 버킷 키 레이아웃

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Build the Redis key for a token bucket.
 *
 * <p>Format: {@code fluxgate:bucket:&#123;ruleSetId:ruleId:keyValue&#125;:bandKeyLabel}, plus
 * {@value #FIXED_WINDOW_KEY_SUFFIX} for a {@link RateLimitAlgorithm#FIXED_WINDOW} band, whose
 * counter is a hash that records its window. The suffix keeps those counters apart from the plain
 * string counters earlier 0.4 builds wrote under the bare name, which then age out on their own
 * expiry instead of failing with {@code WRONGTYPE}.
 *
 * <p>Example: {@code fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s}
 *
 * <p>The braces are a Redis Cluster hash tag: only what is inside them is hashed, so every band
 * of one rule and key lands in the same slot and the multi-band Lua script can be atomic. The
 * band segment uses {@link RateLimitBand#getKeyLabel()}, which is derived from the band
 * configuration when no label was set - two unlabelled bands of one rule can no longer collide.
 *
 * @param ruleSetId ID of the rule set
 * @param ruleId ID of the rule
 * @param key Rate limit key (e.g. IP address, API key), already sanitised by the core
 * @param band Rate limit band
 * @return Redis key string
 */
private static String buildBucketKey(
    String ruleSetId, String ruleId, RateLimitKey key, RateLimitBand band) {

  // ruleSetId and ruleId are escaped so that Redis hash-tag delimiters (:, {, }) and SCAN glob
  // metacharacters (*, ?, [, ], \) embedded in operator-controlled strings cannot alter the key
  // namespace or the bucket key pattern - reversibly, so two ids can never share a bucket. The
  // band label is escaped too: a label "x:fw" must not land on the FIXED_WINDOW counter of a band
  // labelled "x". key.value() is already sanitised by the core.
  return BUCKET_KEY_PREFIX
      + "{"
      + sanitizeSegment(ruleSetId)
      + ":"
      + sanitizeSegment(ruleId)
      + ":"
      + key.value()
      + "}:"
      + escapeBandLabel(band.getKeyLabel())
      + (band.getAlgorithm() == RateLimitAlgorithm.FIXED_WINDOW ? FIXED_WINDOW_KEY_SUFFIX : "");
}
```

### 키 형태

```
fluxgate:bucket:{api-limits:per-ip-rule:ip:192.168.1.100}:100-per-60s
└──── 접두사 ───┘└──────────── 해시 태그 ────────────────┘└─ 대역 라벨 ┘
```

세 부분 각각에 이유가 있습니다.

#### (1) `fluxgate:bucket:` 접두사

버킷 키 공간이 룰셋 정의(`fluxgate:ruleset:*`, `fluxgate:rulesets`)와 **서로 겹치지 않습니다.**

```java
// RedisTokenBucketStore.java - 실제 코드 주석
/**
 * Deletes all token buckets (full reset).
 *
 * <p>This is used when a full reload is triggered to reset all rate limit state. The pattern is
 * {@code fluxgate:bucket:*}, which is disjoint from {@code fluxgate:ruleset:*} and {@code
 * fluxgate:rulesets} - a full reset can no longer destroy rule set definitions stored in Redis.
 */
```

0.3.x의 전체 리셋 패턴은 `fluxgate:*`였고, 그것은 **Redis에 저장한 룰셋 정의까지 삭제했습니다.**

#### (2) `{...}` 해시 태그

Redis Cluster는 중괄호 안의 내용만 해싱합니다. `ruleSetId:ruleId:keyValue`를 태그로 묶으면 한
규칙+키의 **모든 대역이 같은 슬롯**에 들어갑니다. 다중 키 Lua 스크립트가 클러스터에서 동작하기 위한
전제 조건입니다.

```java
// RedisTokenBucketStore.java - 실제 코드 주석
/**
 * <p>In cluster mode all keys must live in the same hash slot; {@link RedisRateLimiter} pins them
 * with a hash tag over {@code ruleSetId:ruleId:keyValue}.
 */
```

#### (3) `band.getKeyLabel()` 대역 세그먼트

```java
// RateLimitBand.java - 실제 코드
public String getKeyLabel() {
  if (label != null && !label.trim().isEmpty()) {
    return label;
  }
  int nanoOfSecond = window.getNano();
  if (nanoOfSecond == 0) {
    return capacity + "-per-" + window.getSeconds() + "s";
  }
  if (nanoOfSecond % 1_000_000 == 0) {
    return capacity + "-per-" + window.toMillis() + "ms";
  }
  return capacity + "-per-" + window.toNanos() + "ns";
}
```

라벨이 없으면 **설정에서 파생**됩니다. 0.3.x는 라벨이 없는 대역에 `"default"`를 썼기 때문에,
한 규칙에 라벨 없는 대역이 둘 있으면 **같은 버킷을 공유**했습니다. 초당 10개와 분당 100개가
하나의 버킷에서 서로를 덮어썼다는 뜻입니다.

### 세그먼트 정규화

`ruleSetId`와 `ruleId`는 운영자가 정하는 문자열이며, 거기에 `:`나 `{`가 들어가면 키 구조가 바뀝니다.

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Percent-escapes the characters that are unsafe in a bucket-key segment.
 *
 * <p>Unsafe characters are: {@code :}, {@code {}, {@code }}, {@code *}, {@code ?}, {@code [},
 * {@code ]}, {@code \}, any whitespace character, and {@code %} itself; each is replaced by the
 * {@code %XX} escapes of its UTF-8 bytes. Escaping {@code %} keeps the mapping reversible, so two
 * different ids can never share a bucket the way {@code a:b} and {@code a_b} did when unsafe
 * characters were replaced by an underscore. Ids without any of these characters - the usual case
 * - keep exactly the segment they had before.
 */
static String sanitizeSegment(String value) {
  if (value == null) {
    return "";
  }
  StringBuilder sb = null;
  for (int i = 0; i < value.length(); i++) {
    char c = value.charAt(i);
    if (isUnsafeSegmentChar(c)) {
      if (sb == null) {
        sb = new StringBuilder(value.length() + 8).append(value, 0, i);
      }
      appendEscaped(sb, c);
    } else if (sb != null) {
      sb.append(c);
    }
  }
  return sb == null ? value : sb.toString();
}

/**
 * Escapes the band label segment: {@code :} and {@code %} only, so that a label cannot imitate
 * the {@value #FIXED_WINDOW_KEY_SUFFIX} suffix of a FIXED_WINDOW counter while every other label
 * - including all derived ones such as {@code 100-per-60s} - keeps its key.
 */
static String escapeBandLabel(String label) {
  if (label.indexOf(':') < 0 && label.indexOf('%') < 0) {
    return label;
  }
  StringBuilder sb = new StringBuilder(label.length() + 8);
  for (int i = 0; i < label.length(); i++) {
    char c = label.charAt(i);
    if (c == ':' || c == '%') {
      appendEscaped(sb, c);
    } else {
      sb.append(c);
    }
  }
  return sb.toString();
}
```

**거부나 치환이 아니라 가역 이스케이프**가 의도입니다. 예전에는 안전하지 않은 문자를 `_`로 바꿨기 때문에
`a:b`와 `a_b`가 한 버킷을 공유했습니다. 퍼센트 이스케이프(UTF-8 바이트의 `%XX`, `%` 자신 포함)는 단사이므로
서로 다른 id는 절대 충돌하지 않고, 안전하지 않은 문자가 없는 id는 이전과 똑같은 세그먼트를 유지합니다.

대역 라벨에는 더 좁은 이스케이프(`escapeBandLabel`: `:`와 `%`만)를 적용합니다. `x:fw` 같은 라벨이
`FIXED_WINDOW` 대역이 키 뒤에 붙이는 `:fw` 접미사를 흉내 내지 못하게 하기 위해서입니다. 고정 윈도우
카운터는 윈도우를 기록하는 해시이며, 이 접미사가 0.4 초기 빌드가 접미사 없는 이름으로 쓴 문자열 카운터와
분리해 줍니다.

### SCAN 패턴

```java
// RedisRateLimiter.java - 실제 코드
/**
 * Returns the {@code SCAN MATCH} pattern that selects every token bucket of one rule set.
 *
 * <p>{@code ruleSetId} is first escaped exactly as in the bucket keys, so that embedded glob
 * metacharacters and hash-tag delimiters cannot corrupt the pattern, then any residual
 * metacharacters are backslash-escaped as a second line of defense. The pattern is anchored on
 * {@link #BUCKET_KEY_PREFIX} and can therefore never match {@code fluxgate:ruleset:*} or {@code
 * fluxgate:rulesets}.
 *
 * @return a glob pattern such as {@code fluxgate:bucket:&#123;api-limits:*}
 */
public static String bucketKeyPattern(String ruleSetId) {
  Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
  return BUCKET_KEY_PREFIX + "{" + escapeGlob(sanitizeSegment(ruleSetId)) + ":*";
}
```

이스케이프 후 **다시 글로브 이스케이프**를 거치는 이중 방어입니다. 패턴이 `BUCKET_KEY_PREFIX`에
고정되어 있으므로 룰셋 정의 키와 절대 겹치지 않습니다.

---

## 4. Redis Connection Layer

### RedisConnectionProvider (인터페이스)

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── RedisConnectionProvider.java
```

Standalone과 Cluster 모드를 통합하는 추상화 레이어입니다.

```java
// RedisConnectionProvider.java - 메서드 목록 (주석은 요약)
public interface RedisConnectionProvider extends AutoCloseable {

    RedisMode getMode();
    boolean isConnected();

    /** Lua 스크립트 로드 (Cluster: Lettuce가 모든 마스터 노드에 브로드캐스트) */
    String scriptLoad(String script);

    /** EVALSHA로 스크립트 실행 (효율적, 캐시된 스크립트 사용) */
    <T> T evalsha(String sha, String[] keys, String[] args);

    /** EVAL로 스크립트 실행 (NOSCRIPT 폴백용) */
    <T> T eval(String script, String[] keys, String[] args);

    // Hash 명령어
    boolean hset(String key, String field, String value);
    long hset(String key, Map<String, String> map);
    Map<String, String> hgetall(String key);

    long del(String... keys);

    /** UNLINK (Redis 4+). 기본 구현은 del()로 위임 */
    default long unlink(String... keys) {
        return del(keys);
    }

    // Set 명령어
    long sadd(String key, String... members);
    Set<String> smembers(String key);
    long srem(String key, String... members);

    boolean exists(String key);
    long ttl(String key);

    /** KEYS. 큰 키스페이스에서 위험 */
    java.util.List<String> keys(String pattern);

    /** SCAN. 기본 구현은 keys()로 위임 */
    default java.util.List<String> scanKeys(String pattern, long count) {
        return keys(pattern);
    }

    /** SCAN, 페이지 단위. 기본 구현은 scanKeys(pattern, count)를 한 페이지로 전달 */
    default void scanKeys(String pattern, long count, Consumer<List<String>> pageConsumer) { ... }

    String flushdb();
    String ping();

    /** Cluster 전용 */
    List<String> clusterNodes();

    @Override
    void close();

    enum RedisMode {
        STANDALONE,
        CLUSTER
    }
}
```

### 0.4에서 추가된 두 메서드: scanKeys와 unlink

버킷 리셋 경로가 `KEYS`와 `DEL`을 쓰던 것이 문제였습니다.

```java
// RedisConnectionProvider.java - 실제 코드 Javadoc
/**
 * Incrementally scans keys matching the given pattern.
 *
 * <p>Unlike {@link #keys(String)}, this method is safe for production-sized keyspaces because it
 * uses Redis SCAN semantics instead of blocking the server for a full keyspace scan.
 */
default java.util.List<String> scanKeys(String pattern, long count) {
  return keys(pattern);
}
```

```java
// RedisConnectionProvider.java - 실제 코드 Javadoc
/**
 * Deletes one or more keys, reclaiming the memory in a background thread where the server
 * supports it.
 *
 * <p>{@code UNLINK} keeps a bulk delete off the Redis event loop, which matters when a rule
 * reload drops a large number of buckets at once. The default implementation delegates to {@link
 * #del(String...)} so that providers talking to a server older than Redis 4 keep working.
 */
default long unlink(String... keys) {
  return del(keys);
}
```

버킷 리셋 경로는 페이지 단위 오버로드 `scanKeys(pattern, count, pageConsumer)`를 쓰고, 페이지가 도착하는
대로 `UNLINK`합니다. 큰 룰셋의 키도 한꺼번에 메모리에 들지 않습니다.

| 명령어 | 문제 | 대체 |
|-------|------|------|
| `KEYS pattern` | 단일 스레드 Redis를 전체 키스페이스 스캔 동안 **블로킹** | `SCAN` (커서 기반, 배치 단위) |
| `DEL k1..kn` | 대량 삭제 시 메모리 회수가 **이벤트 루프에서** 일어남 | `UNLINK` (백그라운드 회수) |

둘 다 `default` 메서드이므로, 두 메서드를 오버라이드하지 않은 커스텀 프로바이더나 Redis 4 미만
서버와 이야기하는 프로바이더도 계속 동작합니다.

### StandaloneRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── StandaloneRedisConnection.java
```

단일 Redis 노드 연결을 처리합니다. Lettuce 기반이며 동기 커맨드를 사용합니다.

```java
// StandaloneRedisConnection.java - 실제 코드 (요지)
public class StandaloneRedisConnection implements RedisConnectionProvider {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    @Override
    public RedisMode getMode() {
        return RedisMode.STANDALONE;
    }

    @Override
    public String scriptLoad(String script) {
        return commands.scriptLoad(script);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evalsha(String sha, String[] keys, String[] args) {
        return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T eval(String script, String[] keys, String[] args) {
        return (T) commands.eval(script, ScriptOutputType.MULTI, keys, args);
    }
}
```

`ScriptOutputType.MULTI`는 Lua가 배열을 돌려주기 때문입니다. 0.4 소비 스크립트는 정수 8개(여덟 번째는 결정 시점의 Redis `TIME`)를, 환불 스크립트는 대역마다 하나씩을
반환합니다.

### ClusterRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── ClusterRedisConnection.java
```

```java
// ClusterRedisConnection.java - 실제 코드 (요지)
public class ClusterRedisConnection implements RedisConnectionProvider {

    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> connection;
    private final RedisAdvancedClusterCommands<String, String> commands;

    @Override
    public RedisMode getMode() {
        return RedisMode.CLUSTER;
    }

    @Override
    public String scriptLoad(String script) {
        // In cluster mode, scriptLoad broadcasts to all nodes automatically
        // Lettuce handles this via the cluster connection
        String sha = commands.scriptLoad(script);
        log.debug("Lua script loaded to cluster, SHA: {}", sha);
        return sha;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evalsha(String sha, String[] keys, String[] args) {
        // Lettuce cluster client automatically routes EVALSHA to the correct node
        // based on the key's slot
        return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    }
}
```

클러스터 모드에서 다중 키 `EVALSHA`가 성립하려면 모든 키가 같은 슬롯이어야 합니다. 그것을 보장하는
것이 위의 해시 태그입니다.

### RedisConnectionFactory

```java
// RedisConnectionFactory.java - 실제 코드 (발췌)
public final class RedisConnectionFactory {

  /**
   * Creates a Redis connection based on the provided URI with custom timeout.
   *
   * @param uri single URI or comma-separated URIs for cluster
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(String uri, Duration timeout) {
    Objects.requireNonNull(uri, "uri must not be null");

    // Check if it's a cluster configuration (comma-separated nodes)
    if (RedisUriUtils.detectMode(uri) == RedisConnectionProvider.RedisMode.CLUSTER) {
      List<String> nodes = RedisUriUtils.splitNodes(uri);
      log.info("Detected cluster mode with {} nodes", nodes.size());
      return new ClusterRedisConnection(nodes, timeout);
    }

    log.info("Using standalone mode");
    return new StandaloneRedisConnection(uri, timeout);
  }

  /**
   * Creates a Redis connection with explicit mode selection.
   *
   * @param mode the desired Redis mode
   * @param uris the Redis URIs (single for standalone, multiple for cluster)
   * @param timeout connection timeout
   * @return the appropriate Redis connection provider
   */
  public static RedisConnectionProvider create(
      RedisConnectionProvider.RedisMode mode, List<String> uris, Duration timeout) { ... }
}
```

모드 판정은 단순히 쉼표가 있는지로 하지 않습니다. `RedisUriUtils`가 URI 스킴이 둘 이상이거나,
자격 증명 밖에 쉼표가 있을 때만 Cluster로 봅니다. 그래서 `redis://:pa,ss@host:6379`처럼 비밀번호에
쉼표가 든 URI도 Standalone으로 판정되고, 노드를 나눌 때도 비밀번호 안의 쉼표에서 끊지 않습니다.

```java
// RedisUriUtils.java - 실제 코드
public static RedisMode detectMode(String uri) {
  return RedisUris.isCluster(uri) ? RedisMode.CLUSTER : RedisMode.STANDALONE;
}

public static List<String> splitNodes(String uri) {
  return RedisUris.splitNodes(uri);
}
```

### RedisUriUtils: URI 마스킹

연결 실패 메시지와 초기화 로그에는 URI가 그대로 실립니다. `redis://user:secret@host`의 자격 증명이
로그로 새지 않도록 전용 유틸을 통과시킵니다.

```java
// RedisRateLimiterConfig.java - 실제 코드
public RedisRateLimiterConfig(String redisUri, Duration timeout, Duration maxBucketTtl) {
  this(
      () ->
          RedisConnectionFactory.create(
              Objects.requireNonNull(redisUri, "redisUri must not be null"),
              Objects.requireNonNull(timeout, "timeout must not be null")),
      maxBucketTtl,
      redisUri == null ? null : RedisUriUtils.mask(redisUri));
}
```

`LazyRedisRateLimiter`도 같은 문제를 다룹니다.

```java
// LazyRedisRateLimiter.java - 실제 코드
/**
 * Returns the most specific message available for a connection failure, with credentials removed.
 *
 * <p>Driver failures happily echo the URI they were given, which may carry a password. The
 * message ends up in the health endpoint and in every {@link RedisUnavailableException}, so it is
 * masked here rather than at each reader.
 */
private static String rootMessage(Throwable t) { ... }

/** Replaces URI credentials and {@code password=} values with a placeholder. */
private static String mask(String message) {
  String masked = URI_CREDENTIALS.matcher(message).replaceAll("://***@");
  return SECRET_PARAMETER.matcher(masked).replaceAll("$1=***");
}
```

### Standalone vs Cluster 비교

| 항목 | Standalone | Cluster |
|-----|-----------|---------|
| 노드 수 | 1개 | 3개 이상 (권장 6개) |
| 데이터 분산 | 없음 | 해시 슬롯 기반 (16384개) |
| 고가용성 | 수동 Failover | 자동 Failover |
| 스크립트 로드 | 단일 노드 | 모든 마스터 노드 (Lettuce 브로드캐스트) |
| EVALSHA 라우팅 | 해당 없음 | 키 해시 슬롯 기반 자동 라우팅 |
| 다중 키 스크립트 | 제약 없음 | 모든 키가 같은 슬롯이어야 함 → 해시 태그 필수 |
| URI 형식 | `redis://host:6379` | `redis://node1:6379,redis://node2:6379,...` |

---

## 5. RedisRateLimiterConfig

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/config/
└── RedisRateLimiterConfig.java
```

```java
// RedisRateLimiterConfig.java - 실제 코드
/**
 * Configuration entry point for the Redis-based rate limiter.
 *
 * <p>This class handles:
 *
 * <ul>
 *   <li>Redis connection setup (both Standalone and Cluster modes)
 *   <li>Loading the Lua scripts into that Redis
 *   <li>TokenBucketStore initialization
 * </ul>
 *
 * <p><strong>Ownership.</strong> A connection this class created is closed by {@link #close()}. A
 * connection handed in through {@link #RedisRateLimiterConfig(RedisConnectionProvider)} belongs to
 * the caller and is left open, so closing this config never shuts down a Lettuce client other parts
 * of the application still use.
 */
@SuppressWarnings("deprecation") // still exposes the deprecated RedisRuleSetStore
public final class RedisRateLimiterConfig implements AutoCloseable {

  private final RedisConnectionProvider connectionProvider;
  private final RedisTokenBucketStore tokenBucketStore;
  private final RedisRuleSetStore ruleSetStore;
  private final boolean ownsConnectionProvider;

  public RedisRateLimiterConfig(String redisUri, Duration timeout, Duration maxBucketTtl) {
    this(
        () ->
            RedisConnectionFactory.create(
                Objects.requireNonNull(redisUri, "redisUri must not be null"),
                Objects.requireNonNull(timeout, "timeout must not be null")),
        maxBucketTtl,
        redisUri == null ? null : RedisUriUtils.mask(redisUri));
  }

  /**
   * Creates a config that owns the connection the given connector opens.
   *
   * <p>{@code maxBucketTtl} is validated before the connector runs, and the connection is closed
   * again when the stores cannot be built, so a failed construction never leaves a Lettuce client
   * (and its event loop threads) behind.
   */
  RedisRateLimiterConfig(
      Supplier<RedisConnectionProvider> connector, Duration maxBucketTtl, String endpoint) {
    validateMaxBucketTtl(maxBucketTtl);

    RedisConnectionProvider provider = connector.get();
    try {
      this.tokenBucketStore = new RedisTokenBucketStore(provider, maxBucketTtl);
      this.ruleSetStore = new RedisRuleSetStore(provider);
    } catch (RuntimeException | Error e) {
      try {
        provider.close();
      } catch (RuntimeException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    this.connectionProvider = provider;
    this.ownsConnectionProvider = true;

    logInitialized(endpoint);
  }

  /**
   * Create a new RedisRateLimiterConfig with an existing connection provider and a TTL cap.
   *
   * <p>The provider is <em>not</em> closed by {@link #close()}.
   */
  public RedisRateLimiterConfig(RedisConnectionProvider connectionProvider, Duration maxBucketTtl) {
    this.connectionProvider =
        Objects.requireNonNull(connectionProvider, "connectionProvider must not be null");
    this.ownsConnectionProvider = false;
    this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider, maxBucketTtl);
    this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

    logInitialized("externally managed connection");
  }

  private void logInitialized(String endpoint) {
    log.info(
        "FluxGate Redis rate limiter initialized ({} mode, {})",
        connectionProvider.getMode(),
        endpoint);
  }

  public RedisTokenBucketStore getTokenBucketStore() {
    return tokenBucketStore;
  }

  /**
   * Whether {@link #close()} closes the connection provider.
   *
   * @return true when this config created the connection itself
   */
  public boolean ownsConnectionProvider() {
    return ownsConnectionProvider;
  }
}
```

### 0.4에서 달라진 초기화 흐름

0.3.x는 `LuaScriptLoader.loadScripts(connectionProvider)`를 **명시적으로 호출**한 뒤
`RedisTokenBucketStore`를 만들었고, 스토어 생성자는 "스크립트가 로드되었는지" 검사해
`IllegalStateException`을 던졌습니다. 순서를 틀리면 런타임에 터졌습니다.

0.4에서는 스토어가 자기 스크립트를 직접 챙깁니다.

```java
// RedisTokenBucketStore.java - 실제 코드 (생성자 끝부분)
if (!scripts.isLoaded()) {
  scripts.loadInto(connectionProvider);
}
```

```
RedisRateLimiterConfig 생성 (URI 또는 mode + uris)
         |
         v
+--------------------------------------+
| (0) maxBucketTtl 검증 (>= 1s)        |
|     연결을 열기 전에 수행            |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (1) RedisConnectionFactory.create    |
|     - RedisUriUtils.detectMode /     |
|       splitNodes                     |
|     - Standalone 또는 Cluster 연결   |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (2) new RedisTokenBucketStore(...)   |
|     - LuaScriptRegistry 생성         |
|       (classpath에서 .lua 읽기)      |
|     - maxBucketTtl 검증 (>= 1s)      |
|     - isLoaded()가 false면 loadInto()|
|       → SCRIPT LOAD → SHA 보관       |
+--------------------------------------+
         |
         v
+--------------------------------------+
| (3) new RedisRuleSetStore(...)       |
|     - @Deprecated 0.4.0              |
+--------------------------------------+

(2)나 (3)에서 예외가 나면 (1)에서 연 연결을 다시 닫습니다.
```

### ownsConnectionProvider: 소유권의 명시화

| 생성 경로 | `ownsConnectionProvider` | `close()` 동작 |
|----------|--------------------------|----------------|
| URI 또는 mode+uris로 생성 | `true` | 연결도 닫음 |
| 기존 `RedisConnectionProvider`를 받음 | `false` | 연결은 **남겨둠** |

이 구분이 없으면, 외부에서 관리하는 Lettuce 클라이언트를 넘겨준 뒤 config를 닫는 순간 다른 빈들이
쓰던 연결이 사라집니다.

### maxBucketTtl

```java
// RedisTokenBucketStore.java - 실제 코드
/**
 * Default upper bound on a bucket TTL.
 *
 * <p>A bucket normally lives one window plus 10%. Without a cap, a rule with a 30 day window
 * keeps one Redis hash per distinct key alive for 33 days - and identity keys are cheap to forge,
 * so the keyspace an attacker can pin down is bounded only by the window. Seven days keeps long
 * windows working while bounding that; raise it deliberately if you rate limit over longer
 * periods with a scope whose cardinality you control.
 */
public static final Duration DEFAULT_MAX_BUCKET_TTL = Duration.ofDays(7);
```

`null`을 넘기면 기본값 7일이고, 1초 미만은 생성자에서 거부됩니다.

```java
Duration effectiveTtl = maxBucketTtl != null ? maxBucketTtl : DEFAULT_MAX_BUCKET_TTL;
if (effectiveTtl.getSeconds() < 1) {
  throw new IllegalArgumentException("maxBucketTtl must be at least 1 second");
}
```

### RedisRuleSetStore는 폐기 예정입니다

```java
// RedisRateLimiterConfig.java - 실제 코드
/**
 * @deprecated the Redis rule set store cannot express the core rule model; see {@link
 *     RedisRuleSetStore}
 */
@Deprecated(since = "0.4.0")
public RedisRuleSetStore getRuleSetStore() {
  return ruleSetStore;
}
```

룰셋은 MongoDB 어댑터(또는 직접 구현한 `RateLimitRuleSetProvider`)로 관리하세요.

---

## 6. 사용 예제

### Standalone 모드

```java
// 단일 Redis 서버 연결 (timeout 5s, 버킷 TTL 상한 7일)
RedisRateLimiterConfig config =
    new RedisRateLimiterConfig("redis://localhost:6379", Duration.ofSeconds(5), null);

// RateLimiter 생성
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());

// Rate Limiting 수행
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

if (result.isAllowed()) {
    // 요청 허용. result.getRemainingTokens()는 binding 대역의 남은 토큰
} else {
    // 요청 거부. result.getNanosToWaitForRefill() 후 재시도
}

// 종료 시 리소스 정리 (이 config가 연결을 소유하므로 연결도 닫힘)
config.close();
```

### Cluster 모드

```java
// 쉼표로 구분된 노드 URI (자동 Cluster 모드 감지)
String clusterUri = "redis://node1:6379,redis://node2:6379,redis://node3:6379";
RedisRateLimiterConfig autoDetected =
    new RedisRateLimiterConfig(clusterUri, Duration.ofSeconds(5), null);

// 또는 명시적 Cluster 모드
RedisRateLimiterConfig explicit = new RedisRateLimiterConfig(
    RedisMode.CLUSTER,
    List.of("redis://node1:6379", "redis://node2:6379", "redis://node3:6379"),
    Duration.ofSeconds(5),
    null);

// 이후 사용법은 동일
RedisRateLimiter rateLimiter = new RedisRateLimiter(explicit.getTokenBucketStore());
```

### 버킷 리셋 (룰 변경 시)

```java
RedisTokenBucketStore store = config.getTokenBucketStore();

// 한 룰셋의 버킷만 (SCAN + UNLINK, 패턴은 RedisRateLimiter.bucketKeyPattern)
long deleted = store.deleteBucketsByRuleSetId("api-limits");

// 전체 버킷 (패턴 "fluxgate:bucket:*" — 룰셋 정의는 건드리지 않음)
long allDeleted = store.deleteAllBuckets();
```

### Spring Boot 통합

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    mode: auto             # auto | standalone | cluster (auto는 URI에서 감지)
    uri: redis://localhost:6379  # Standalone
    # uri: redis://node1:6379,redis://node2:6379,redis://node3:6379  # Cluster
    timeout-ms: 5000
    fail-fast: false      # true면 부팅 시 Redis 미가용을 실패로 처리
    max-bucket-ttl: 7d    # TOKEN_BUCKET / SLIDING_WINDOW 버킷 TTL의 상한 (FIXED_WINDOW 제외)
```

```java
// Spring Boot AutoConfiguration이 자동으로 빈 생성
@Autowired
private RateLimiter rateLimiter;  // ResilientRateLimiter(@Primary)가 주입됩니다
```

주입되는 `RateLimiter`는 **원시 `RedisRateLimiter`가 아닙니다.** 스타터는 다음 체인을 세웁니다.

```
ResilientRateLimiter        @Primary  — 재시도 + 서킷 브레이커 + 강등
        v
LazyRedisRateLimiter                  — 요청 스레드가 절대 연결하지 않음
        v
RedisRateLimiter → RedisTokenBucketStore → Lua
```

### fail-fast=false가 기본인 이유

```java
// LazyRedisRateLimiter.java - 실제 코드 Javadoc
/**
 * <p>Rate limiting is an auxiliary concern, so a Redis outage during a rollout must not turn into
 * an application crash loop. One connection attempt is made when this limiter is created; if it
 * fails the failure is logged and a reconnect is scheduled (starting at the configured interval and
 * backing off to a cap), while calls fail with {@link RedisUnavailableException} so the configured
 * {@code fluxgate.ratelimit.failure-behavior} - or the in-memory fallback of {@link
 * ResilientRateLimiter} - decides what happens to the request. Set {@code
 * fluxgate.redis.fail-fast=true} to restore the eager connect that aborts the boot instead.
 *
 * <p><b>Request threads never connect.</b> Connecting is a blocking socket operation behind a
 * single lock, so letting {@link #tryConsume} do it turned one unreachable Redis into a thread pool
 * exhaustion: every worker queued on the lock for the connect timeout, and a retryable exception on
 * top multiplied that by the retry count. Only the constructor and the background reconnect task
 * ever call {@link #connect()}, which also means request traffic cannot advance the backoff. The
 * reconnect task re-arms itself after every failure and stops only on {@link #close()}.
 */
```

요청 스레드가 연결을 시도하지 않는다는 점이 핵심입니다. Redis 하나가 닿지 않는 상황이
**스레드 풀 고갈**로 번지지 않습니다.

```java
// LazyRedisRateLimiter.java - 실제 코드
@Override
public RateLimitResult tryConsume(
    RequestContext context, RateLimitRuleSet ruleSet, long permits) {
  return connectedDelegate().tryConsume(context, ruleSet, permits);
}

// PathPatternMatcher를 받는 인자 4개짜리 오버로드도 같은 방식으로 위임합니다

private RateLimiter connectedDelegate() {
  RateLimiter delegate = delegateRef.get();
  if (delegate == null) {
    // Deliberately does not connect: see the class Javadoc. The exception is non-retryable so the
    // retry executor hands it straight to the configured degradation.
    throw new RedisUnavailableException(
        "FluxGate Redis rate limiter is not connected yet: " + lastErrorMessage.get());
  }
  return delegate;
}
```

연결 상태는 `RedisConnectionState`로 공개되어, 헬스 엔드포인트가 limiter를 DEGRADED로 보고할 수
있습니다.

### 헬스체크와 Redis 권한

`RedisHealthCheckerImpl`은 모든 모드에서 `PING`을 보냅니다. 클러스터 모드에서는 **`CLUSTER NODES`**
(노드·마스터·레플리카 수)와 **`CLUSTER INFO`**(`cluster_state`, `cluster_slots_fail`)도 실행하고,
둘 중 하나가 실패하거나 빈 응답을 주면 클러스터를 DOWN으로 보고합니다. 따라서 Redis ACL을 쓰면
헬스체크에 쓰이는 사용자에게 limiter 자체가 쓰는 명령(스크립트 명령, 그리고 Lua 스크립트 안에서
호출하는 읽기·쓰기 명령과 `TIME` - ACL은 스크립트 안에서도 검사됩니다)에 더해 `ping`,
`cluster|nodes`, `cluster|info` 권한이 필요합니다. 예:

```
ACL SETUSER fluxgate on >secret ~fluxgate:* +@read +@write +@scripting +time +ping \
    +cluster|nodes +cluster|info
```

두 클러스터 하위 명령 권한이 없으면 정상 클러스터도 `cluster_state unknown (CLUSTER INFO failed or
returned nothing)` 또는 `no cluster nodes reported`와 함께 DOWN으로 보고됩니다.

---

## 관련 문서

- [Storage Layer Deep Dive](storage-layer.ko.md) - RedisTokenBucketStore, Lua 스크립트 상세
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md) - 알고리즘과 Lua 전체 분석
- [Handler Layer Deep Dive](handler-layer.ko.md) - resilience 체인 배선
- [아키텍처 개요](../README.ko.md)
