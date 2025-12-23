# Hot Reload Deep Dive

이 문서는 FluxGate의 Hot Reload 메커니즘을 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RuleReloadStrategy 인터페이스](#1-ruleReloadstrategy-인터페이스)
2. [RuleReloadEvent와 ReloadSource](#2-rulereloadevent와-reloadsource)
3. [PollingReloadStrategy](#3-pollingreloadstrategy)
4. [RedisPubSubReloadStrategy](#4-redispubsubreloadstrategy)
5. [BucketResetHandler](#5-bucketresethandler)
6. [전체 흐름 요약](#6-전체-흐름-요약)

---

## 1. RuleReloadStrategy 인터페이스

```
fluxgate-core/src/main/java/org/fluxgate/core/reload/
└── RuleReloadStrategy.java
```

규칙 Hot Reload를 위한 전략 인터페이스입니다.

```java
// RuleReloadStrategy.java - 실제 코드
public interface RuleReloadStrategy {

  /** 리로드 메커니즘 시작 (멱등성 보장) */
  void start();

  /** 리로드 메커니즘 중지 및 리소스 해제 */
  void stop();

  /** 실행 중 여부 반환 */
  boolean isRunning();

  /** 특정 규칙 세트 리로드 트리거 */
  void triggerReload(String ruleSetId);

  /** 모든 캐시된 규칙 리로드 트리거 */
  void triggerReloadAll();

  /** 리로드 이벤트 리스너 추가 */
  void addListener(RuleReloadListener listener);

  /** 리스너 제거 */
  void removeListener(RuleReloadListener listener);
}
```

### 사용 예시

```java
RuleReloadStrategy strategy = new PollingReloadStrategy(...);
strategy.addListener(event -> cache.invalidate(event.getRuleSetId()));
strategy.start();

// 프로그래밍 방식으로 리로드 트리거
strategy.triggerReload("my-rule-set");

// 종료 시
strategy.stop();
```

---

## 2. RuleReloadEvent와 ReloadSource

```
fluxgate-core/src/main/java/org/fluxgate/core/reload/
├── RuleReloadEvent.java
├── RuleReloadListener.java
└── ReloadSource.java
```

### RuleReloadEvent

```java
// RuleReloadEvent.java - 실제 코드
public final class RuleReloadEvent {

  private final String ruleSetId;      // null이면 전체 리로드
  private final ReloadSource source;   // 트리거 소스
  private final Instant timestamp;     // 이벤트 생성 시간
  private final Map<String, Object> metadata;

  /** ruleSetId가 null이면 전체 리로드 */
  public boolean isFullReload() {
    return ruleSetId == null;
  }

  /** 특정 규칙 세트 리로드 이벤트 생성 */
  public static RuleReloadEvent forRuleSet(String ruleSetId, ReloadSource source) {
    return builder().ruleSetId(ruleSetId).source(source).build();
  }

  /** 전체 리로드 이벤트 생성 */
  public static RuleReloadEvent fullReload(ReloadSource source) {
    return builder().source(source).build();
  }
}
```

### RuleReloadListener

```java
// RuleReloadListener.java - 실제 코드
@FunctionalInterface
public interface RuleReloadListener {

  /**
   * 리로드 이벤트 수신 시 호출
   *
   * @param event 리로드 상세 정보를 담은 이벤트
   */
  void onReload(RuleReloadEvent event);
}
```

### ReloadSource

```java
// ReloadSource.java - 실제 코드
public enum ReloadSource {

  /** Redis Pub/Sub 메시지를 통한 리로드 */
  PUBSUB,

  /** 주기적 폴링으로 변경 감지 */
  POLLING,

  /** API 또는 프로그래밍 방식 수동 호출 */
  MANUAL,

  /** 외부 REST 엔드포인트 호출 */
  API,

  /** 애플리케이션 시작 시 */
  STARTUP,

  /** 캐시 TTL 만료 */
  CACHE_EXPIRY
}
```

---

## 3. PollingReloadStrategy

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/strategy/
└── PollingReloadStrategy.java
```

주기적으로 규칙 변경을 감지하는 폴링 기반 전략입니다.

```java
// PollingReloadStrategy.java - 실제 코드
public class PollingReloadStrategy extends AbstractReloadStrategy {

  private final RateLimitRuleSetProvider provider;
  private final RuleCache cache;
  private final Duration pollInterval;
  private final Duration initialDelay;

  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> pollTask;

  // 해시코드로 규칙 세트 버전 추적
  private final Map<String, Integer> versionMap = new ConcurrentHashMap<>();

  @Override
  protected void doStart() {
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "fluxgate-rule-poller");
      t.setDaemon(true);
      return t;
    });

    pollTask = scheduler.scheduleWithFixedDelay(
        this::pollForChanges,
        initialDelay.toMillis(),
        pollInterval.toMillis(),
        TimeUnit.MILLISECONDS);

    log.info("Polling reload strategy started: interval={}, initialDelay={}",
        pollInterval, initialDelay);
  }

  /** 캐시된 모든 규칙 세트의 변경 확인 */
  private void pollForChanges() {
    try {
      Set<String> cachedIds = cache.getCachedRuleSetIds();

      if (cachedIds.isEmpty()) {
        log.trace("No cached rule sets to poll");
        return;
      }

      for (String ruleSetId : cachedIds) {
        checkForChange(ruleSetId);
      }
    } catch (Exception e) {
      log.error("Error during polling cycle", e);
    }
  }

  /** 특정 규칙 세트 변경 확인 */
  private void checkForChange(String ruleSetId) {
    Optional<RateLimitRuleSet> currentOpt = provider.findById(ruleSetId);

    if (currentOpt.isEmpty()) {
      // 규칙 세트 삭제됨
      Integer previousVersion = versionMap.remove(ruleSetId);
      if (previousVersion != null) {
        log.info("Rule set deleted: {}", ruleSetId);
        notifyListeners(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.POLLING));
      }
      return;
    }

    RateLimitRuleSet current = currentOpt.get();
    int currentVersion = computeVersion(current);
    Integer previousVersion = versionMap.get(ruleSetId);

    if (previousVersion == null) {
      // 처음 보는 규칙 세트
      versionMap.put(ruleSetId, currentVersion);
    } else if (!previousVersion.equals(currentVersion)) {
      // 규칙 세트 변경됨
      versionMap.put(ruleSetId, currentVersion);
      log.info("Rule set changed: {} (version: {} -> {})",
          ruleSetId, previousVersion, currentVersion);
      notifyListeners(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.POLLING));
    }
  }

  /** 규칙 세트의 버전 해시 계산 */
  private int computeVersion(RateLimitRuleSet ruleSet) {
    int hash = ruleSet.getId().hashCode();
    if (ruleSet.getDescription() != null) {
      hash = 31 * hash + ruleSet.getDescription().hashCode();
    }
    hash = 31 * hash + ruleSet.getRules().hashCode();
    return hash;
  }
}
```

### 설정

```yaml
fluxgate:
  reload:
    strategy: POLLING
    polling:
      interval: 30s
      initial-delay: 10s
```

### 동작 방식

```
+-------------------+                  +-------------------+
|  PollingStrategy  |                  |  MongoDB/Source   |
+-------------------+                  +-------------------+
         |                                      |
         |  (1) 30초마다 폴링                     |
         | -----------------------------------> |
         |                                      |
         |  (2) 규칙 세트 조회                    |
         | <----------------------------------- |
         |                                      |
         |  (3) 해시코드 비교                     |
         |      - 이전: 12345                    |
         |      - 현재: 67890                    |
         |      → 변경 감지!                      |
         |                                      |
         |  (4) RuleReloadEvent 발행             |
         | ---> Listeners                       |
```

---

## 4. RedisPubSubReloadStrategy

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/strategy/
└── RedisPubSubReloadStrategy.java
```

Redis Pub/Sub을 통해 실시간으로 규칙 변경을 전파합니다.

```java
// RedisPubSubReloadStrategy.java - 실제 코드
public class RedisPubSubReloadStrategy extends AbstractReloadStrategy {

  public static final String FULL_RELOAD_MESSAGE = "*";

  private final String redisUri;
  private final String channel;
  private final boolean retryOnFailure;
  private final Duration retryInterval;
  private final boolean isCluster;

  private Object redisClient;  // RedisClient 또는 RedisClusterClient
  private final AtomicReference<StatefulRedisPubSubConnection<String, String>> connectionRef =
      new AtomicReference<>();
  private ScheduledExecutorService retryScheduler;

  @Override
  protected void doStart() {
    if (retryOnFailure) {
      retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fluxgate-pubsub-retry");
        t.setDaemon(true);
        return t;
      });
    }

    subscribe();
    log.info("Redis Pub/Sub reload strategy started on channel: {}", channel);
  }

  /** Pub/Sub 구독 설정 */
  private void subscribe() {
    try {
      ensureClient();

      StatefulRedisPubSubConnection<String, String> connection;
      if (isCluster) {
        connection = ((RedisClusterClient) redisClient).connectPubSub();
      } else {
        connection = ((RedisClient) redisClient).connectPubSub();
      }

      connection.addListener(new RedisPubSubAdapter<String, String>() {
        @Override
        public void message(String channel, String message) {
          handleMessage(message);
        }

        @Override
        public void subscribed(String channel, long count) {
          log.info("Subscribed to channel: {}", channel);
        }

        @Override
        public void unsubscribed(String channel, long count) {
          log.info("Unsubscribed from channel: {}", channel);
          if (isRunning() && retryOnFailure && count == 0) {
            scheduleRetry();
          }
        }
      });

      connection.sync().subscribe(channel);
      connectionRef.set(connection);
    } catch (Exception e) {
      log.error("Failed to subscribe to Redis channel: {}", channel, e);
      if (retryOnFailure) {
        scheduleRetry();
      }
    }
  }

  /**
   * Pub/Sub 메시지 처리
   *
   * 지원 형식:
   * - JSON: {"ruleSetId": "xxx", "fullReload": false, ...}
   * - Plain text: "*"이면 전체 리로드, 아니면 ruleSetId
   */
  private void handleMessage(String message) {
    log.debug("Received Pub/Sub message: {}", message);

    RuleReloadEvent event;
    if (message == null || message.isEmpty() || FULL_RELOAD_MESSAGE.equals(message)) {
      event = RuleReloadEvent.fullReload(ReloadSource.PUBSUB);
      log.info("Full reload triggered via Pub/Sub");
    } else if (message.trim().startsWith("{")) {
      // JSON 형식 메시지
      event = parseJsonMessage(message);
    } else {
      // Plain text ruleSetId
      event = RuleReloadEvent.forRuleSet(message, ReloadSource.PUBSUB);
      log.info("Reload triggered via Pub/Sub for ruleSetId: {}", message);
    }

    notifyListeners(event);
  }
}
```

### 설정

```yaml
fluxgate:
  reload:
    strategy: PUBSUB
    pubsub:
      channel: fluxgate:rule-reload
      retry-on-failure: true
      retry-interval: 5s
```

### 메시지 형식

| 메시지 | 동작 |
|-------|------|
| `*` 또는 빈 문자열 | 전체 규칙 리로드 |
| `my-rule-set` | 특정 규칙 세트 리로드 |
| `{"ruleSetId": "xxx"}` | JSON 형식 - 특정 규칙 세트 |
| `{"fullReload": true}` | JSON 형식 - 전체 리로드 |

### 동작 방식

```
+------------------+    Pub     +---------+    Sub     +------------------+
|  Admin Server    | ---------> |  Redis  | ---------> |  App Instance 1  |
|  (규칙 수정)      |            | Channel |            |                  |
+------------------+            +---------+            +------------------+
                                     |
                                     | Sub
                                     v
                                +------------------+
                                |  App Instance 2  |
                                +------------------+
                                     |
                                     | Sub
                                     v
                                +------------------+
                                |  App Instance N  |
                                +------------------+
```

---

## 5. BucketResetHandler

```
fluxgate-core/src/main/java/org/fluxgate/core/reload/
└── BucketResetHandler.java

fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/handler/
└── RedisBucketResetHandler.java
```

규칙 변경 시 Redis의 토큰 버킷을 리셋합니다.

### BucketResetHandler 인터페이스

```java
// BucketResetHandler.java - 실제 코드
public interface BucketResetHandler {

  /**
   * 특정 규칙 세트의 모든 버킷 리셋
   *
   * @param ruleSetId 버킷을 리셋할 규칙 세트 ID
   */
  void resetBuckets(String ruleSetId);

  /**
   * 모든 버킷 리셋 (전체 리셋)
   */
  void resetAllBuckets();
}
```

### RedisBucketResetHandler 구현

```java
// RedisBucketResetHandler.java - 실제 코드
public class RedisBucketResetHandler implements BucketResetHandler, RuleReloadListener {

  private final RedisTokenBucketStore tokenBucketStore;

  public RedisBucketResetHandler(RedisTokenBucketStore tokenBucketStore) {
    this.tokenBucketStore =
        Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
  }

  @Override
  public void resetBuckets(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    log.info("Resetting token buckets for ruleSetId: {}", ruleSetId);
    long deleted = tokenBucketStore.deleteBucketsByRuleSetId(ruleSetId);
    log.info("Reset complete: {} buckets deleted for ruleSetId: {}", deleted, ruleSetId);
  }

  @Override
  public void resetAllBuckets() {
    log.info("Resetting all token buckets (full reset)");
    long deleted = tokenBucketStore.deleteAllBuckets();
    log.info("Full reset complete: {} buckets deleted", deleted);
  }

  /** RuleReloadListener 구현 - 리로드 이벤트 수신 시 자동 버킷 리셋 */
  @Override
  public void onReload(RuleReloadEvent event) {
    if (event.isFullReload()) {
      log.info("Full reload event received, resetting all buckets");
      resetAllBuckets();
    } else {
      String ruleSetId = event.getRuleSetId();
      log.info("Reload event received for ruleSetId: {}, resetting buckets", ruleSetId);
      resetBuckets(ruleSetId);
    }
  }
}
```

### 버킷 리셋이 필요한 이유

규칙이 변경되면:
1. 캐시된 규칙 정의는 무효화됨
2. **하지만** Redis의 토큰 버킷 상태는 그대로 남아있음
3. 버킷을 리셋해야 새 규칙이 즉시 적용됨

```
규칙 변경 전:                    규칙 변경 후 (버킷 미리셋):
+------------------+            +------------------+
| capacity: 100    |            | capacity: 10     |  <- 새 규칙
| tokens: 50       |            | tokens: 50       |  <- 이전 버킷 상태!
+------------------+            +------------------+
                                 (capacity보다 많은 토큰)

규칙 변경 후 (버킷 리셋):
+------------------+
| capacity: 10     |
| tokens: 10       |  <- 새 규칙에 맞게 리셋
+------------------+
```

---

## 6. 전체 흐름 요약

### 리스너 등록 및 조합

```java
// 자동 설정 예시
@Bean
public RuleReloadStrategy reloadStrategy(
    RateLimitRuleSetProvider provider,
    RuleCache cache,
    CachingRuleSetProvider cachingProvider,
    RedisBucketResetHandler bucketResetHandler) {

  RuleReloadStrategy strategy = new PollingReloadStrategy(
      provider, cache, Duration.ofSeconds(30), Duration.ofSeconds(10));

  // (1) 캐시 무효화 리스너
  strategy.addListener(cachingProvider);

  // (2) 버킷 리셋 리스너
  strategy.addListener(bucketResetHandler);

  strategy.start();
  return strategy;
}
```

### Hot Reload 전체 흐름

```
+-----------------------------------------------------------------------+
|  Admin이 MongoDB에서 규칙 수정                                          |
|                   |                                                    |
|                   v                                                    |
|  Redis Pub/Sub으로 변경 이벤트 발행                                      |
|    PUBLISH fluxgate:rule-reload "my-rule-set"                          |
|                   |                                                    |
|                   v                                                    |
|  +---------------------------------------------------------------+    |
|  |  모든 애플리케이션 인스턴스가 이벤트 수신                           |    |
|  |                                                                |    |
|  |  (1) RedisPubSubReloadStrategy.handleMessage()                |    |
|  |      -> RuleReloadEvent 생성                                   |    |
|  |                                                                |    |
|  |  (2) 등록된 리스너들에게 이벤트 전파                             |    |
|  |      |                                                         |    |
|  |      +-> CachingRuleSetProvider.onReload()                     |    |
|  |      |      -> cache.invalidate(ruleSetId)                     |    |
|  |      |      -> 캐시된 규칙 삭제                                  |    |
|  |      |                                                         |    |
|  |      +-> RedisBucketResetHandler.onReload()                    |    |
|  |             -> tokenBucketStore.deleteBucketsByRuleSetId()     |    |
|  |             -> Redis의 토큰 버킷 상태 삭제                       |    |
|  |                                                                |    |
|  |  (3) 다음 요청 시 MongoDB에서 새 규칙 로드                       |    |
|  |      -> 새 규칙으로 Rate Limiting 적용                          |    |
|  +---------------------------------------------------------------+    |
+-----------------------------------------------------------------------+
```

---

## 전략 선택 가이드

| 상황 | 추천 전략 | 이유 |
|-----|----------|------|
| 실시간 반영 필요 | `RedisPubSubReloadStrategy` | 즉시 전파, 낮은 레이턴시 |
| Redis 없음 | `PollingReloadStrategy` | Redis 의존성 없음 |
| 개발/테스트 | `NoOpReloadStrategy` | 리로드 비활성화 |

---

## 관련 문서

- [Storage Layer Deep Dive](storage-layer.ko.md)
- [Engine Layer Deep Dive](engine-layer.ko.md)
- [아키텍처 개요](../README.ko.md)
