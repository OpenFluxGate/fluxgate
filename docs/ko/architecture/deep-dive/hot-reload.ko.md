# Hot Reload Deep Dive

> 이 문서는 FluxGate **0.4**의 코드를 기준으로 합니다. 0.3.x에서 무엇이 바뀌었는지는
> [0.4 마이그레이션](../../operations/migration-0.4.ko.md)에 정리되어 있습니다.

이 문서는 FluxGate의 Hot Reload 메커니즘을 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [RuleReloadStrategy 인터페이스](#1-rulereloadstrategy-인터페이스)
2. [RuleReloadEvent와 ReloadSource](#2-rulereloadevent와-reloadsource)
3. [PollingReloadStrategy](#3-pollingreloadstrategy)
4. [RedisPubSubReloadStrategy](#4-redispubsubreloadstrategy)
5. [CompositeReloadStrategy (Pub/Sub + 폴링 백스톱)](#5-compositereloadstrategy-pubsub--폴링-백스톱)
6. [BucketResetHandler](#6-bucketresethandler)
7. [전체 흐름 요약](#7-전체-흐름-요약)

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

  private final String ruleSetId;      // 대상 룰셋 (전체 리로드면 null일 수 있음)
  private final ReloadSource source;   // 트리거 소스
  private final Instant timestamp;     // 이벤트 생성 시간
  private final Map<String, Object> metadata;
  private final boolean fullReload;    // 전체 리로드 플래그 (명시적)

  public boolean isFullReload() {
    return fullReload || ruleSetId == null;
  }

  /** 특정 규칙 세트 리로드 이벤트 생성 */
  public static RuleReloadEvent forRuleSet(String ruleSetId, ReloadSource source) {
    return builder().ruleSetId(ruleSetId).source(source).build();
  }

  /** 전체 리로드 이벤트 생성 */
  public static RuleReloadEvent fullReload(ReloadSource source) {
    return builder().source(source).fullReload(true).build();
  }
}
```

#### 0.4에서 추가된 fullReload 플래그

0.3.x는 "`ruleSetId`가 null이면 전체 리로드"라는 **암묵적 규약**만 있었습니다. 그러면 id를
채우는 것을 잊은 버그가 곧바로 가장 파괴적인 동작(모든 버킷 삭제)이 됩니다.

0.4는 플래그를 명시합니다. `isFullReload()`가 `fullReload || ruleSetId == null`인 것은 하위 호환
때문이며, 새로 만드는 전체 리로드 이벤트는 모두 플래그를 명시적으로 세팅합니다.

```java
// RedisPubSubReloadStrategy.java - 실제 코드
/** Builds a full reload event with the flag set explicitly rather than relying on a null id. */
private RuleReloadEvent fullReloadEvent() {
  return RuleReloadEvent.builder().source(ReloadSource.PUBSUB).fullReload(true).build();
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
    try {
      Optional<RateLimitRuleSet> currentOpt = provider.findById(ruleSetId);

      if (currentOpt.isEmpty()) {
        // Rule set was deleted
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
        // First time seeing this rule set
        versionMap.put(ruleSetId, currentVersion);
        log.debug("Tracking new rule set: {} (version: {})", ruleSetId, currentVersion);
      } else if (!previousVersion.equals(currentVersion)) {
        // Rule set changed
        versionMap.put(ruleSetId, currentVersion);
        log.info(
            "Rule set changed: {} (version: {} -> {})", ruleSetId, previousVersion, currentVersion);
        notifyListeners(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.POLLING));
      } else {
        log.trace("Rule set unchanged: {}", ruleSetId);
      }
    } catch (Exception e) {
      log.warn("Error checking rule set for changes: {}", ruleSetId, e);
    }
  }

  /**
   * Computes a version hash for a rule set.
   *
   * <p>Uses the rule set's content to generate a hash that changes when the rules change - and only
   * then. {@code RateLimitRule} and {@code RateLimitBand} have value-based {@code equals}/{@code
   * hashCode}, so two rule sets loaded from the store in successive polls hash identically as long
   * as their content is identical. Without that, every poll looked like a change and reset every
   * bucket in the deployment, handing all clients a fresh full quota each interval.
   *
   * <p>Deliberately not {@code RateLimitRuleSet.equals}, which also compares the key resolver and
   * the metrics recorder - usually freshly created lambdas that are never equal.
   */
  private int computeVersion(RateLimitRuleSet ruleSet) {
    return Objects.hash(ruleSet.getId(), ruleSet.getDescription(), ruleSet.getRules());
  }

  /**
   * Records the current version of a rule set without firing a reload event.
   *
   * <p>Called by {@link CompositeReloadStrategy} when the primary strategy already notified
   * listeners of a change for this rule set, so the next backstop poll does not fire a second
   * reset.
   */
  public void markSeen(String ruleSetId) {
    provider.findById(ruleSetId).ifPresent(rs -> versionMap.put(ruleSetId, computeVersion(rs)));
  }

  /**
   * Clears the version map without firing any reload event.
   *
   * <p>Called by {@link CompositeReloadStrategy} after a full-reload event, so the next poll treats
   * every rule set as a first observation and does not fire a second reset.
   */
  public void markAllSeen() {
    versionMap.clear();
  }
}
```

### 0.4에서 고쳐진 것: 폴링이 매 주기마다 버킷을 리셋했습니다

`computeVersion`의 Javadoc이 그 버그를 설명합니다.

> Without that, every poll looked like a change and reset every bucket in the deployment,
> handing all clients a fresh full quota each interval.

`RateLimitRule`과 `RateLimitBand`에 **값 기반 `equals`/`hashCode`** 가 없으면, MongoDB에서
같은 내용을 두 번 읽어와도 객체 식별자 기준 해시가 달라집니다. 그러면 폴링이 매 주기마다
"변경됨"으로 판단하고, 배포 전체의 버킷을 리셋합니다.

```
interval: 30s, 값 기반 equals 없음

00:00  폴링 → 해시 A → 최초 관측
00:30  폴링 → 해시 B (내용 동일, 객체만 새로움) → "변경!" → 모든 버킷 리셋
01:00  폴링 → 해시 C → "변경!" → 모든 버킷 리셋
        ...
→ 30초마다 모든 클라이언트가 가득 찬 quota를 새로 받습니다
→ Rate Limiting이 사실상 무력화됩니다
```

`RateLimitRuleSet.equals`를 쓰지 않은 것도 의도입니다. 그 구현은 `keyResolver`와
`metricsRecorder`까지 비교하는데, 둘은 보통 그때그때 만들어진 람다이므로 **절대 같지 않습니다.**
그래서 `Objects.hash(id, description, rules)`로 내용만 봅니다.

`checkForChange` 전체를 `try/catch`로 감싼 것도 0.4의 변경입니다. MongoDB 일시 장애가 폴링
스레드를 죽이면 이후 변경을 영원히 감지하지 못합니다. 이제 WARN을 남기고 다음 주기에 다시
시도합니다.

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
// RedisPubSubReloadStrategy.java - 실제 코드 (클래스 Javadoc)
/**
 * Redis Pub/Sub based reload strategy for real-time rule change notifications.
 *
 * <p>This strategy subscribes to a Redis channel and listens for rule change messages. When a
 * message is received, it triggers a reload event to invalidate cached rules.
 *
 * <p>Message format:
 *
 * <ul>
 *   <li>JSON: <code>{"version":2,"ruleSetId":"xxx","fullReload":false,"nonce":"..."}</code> -
 *       {@code version} is optional and defaults to {@link #LEGACY_MESSAGE_SCHEMA_VERSION};
 *       versions 1 and 2 are understood; {@code fullReload} must be set explicitly to request a
 *       full reload
 *   <li>{@code "*"} - full reload, kept for backward compatibility with plain-text publishers
 *   <li>{@code "ruleSetId"} - reload that one rule set
 * </ul>
 *
 * <p><b>Anything else is logged at WARN and ignored.</b> The channel is an ordinary Redis channel
 * with no authentication, and on a shared Redis it can carry traffic from unrelated applications,
 * so an empty message, a schema mismatch or a parse failure must never be turned into the most
 * destructive action available - it used to trigger a full reload and wipe every bucket.
 *
 * <p><b>Signed messages.</b> Parsing hardening keeps an accidental message from doing damage; it
 * does nothing against a deliberate one, because {@code PUBLISH fluxgate:rule-reload '*'} is all a
 * full reset takes. Set {@code fluxgate.reload.pubsub.secret} to the same value as the publisher's
 * {@code fluxgate.control.secret} and this strategy accepts only JSON messages carrying a valid
 * HMAC-SHA256 {@code signature} whose {@code timestamp} lies inside {@code
 * fluxgate.reload.pubsub.max-message-age} (60s by default) and which it has not seen before.
 * Version 2 messages are verified over {@link HmacSigner#canonicalRuleChangeV2}, which binds the
 * channel this strategy subscribes to and a per-message {@code nonce}; the nonce is remembered for
 * the replay window, so a captured message published again is ignored. Version 1 messages (no
 * nonce, no channel binding) are still verified over {@link HmacSigner#canonicalRuleChange} and are
 * deduplicated by their signature, as long as {@link #setAcceptLegacySigned(boolean)} (starter
 * property {@code fluxgate.reload.pubsub.accept-legacy-signed}, {@code true} by default) allows
 * them; the first one accepted is reported at WARN, and once every publisher signs version 2 the
 * property should be set to {@code false}. Everything else - an unsigned message, a wrong
 * signature, a stale one, a replay, a version 2 message without a nonce, and the plain-text {@code
 * "*"} and {@code "ruleSetId"} forms - is logged at WARN and ignored. Both sides normalise the
 * secret with {@link HmacSigner#normalizeSecret(String)}, and a secret shorter than {@value
 * HmacSigner#MIN_RECOMMENDED_SECRET_BYTES} bytes is reported at WARN. Constructed without a secret,
 * this class accepts unsigned messages and says so in a single INFO line at startup; the starter
 * only does that when {@code fluxgate.reload.pubsub.allow-unsigned=true}; otherwise {@code AUTO}
 * polls instead and an explicit {@code PUBSUB} refuses to start (H-3, since 0.4).
 *
 * <p>Redis Pub/Sub is at-most-once, so a dropped message would leave this instance serving stale
 * rules. The starter therefore composes this strategy with a low-frequency polling backstop; see
 * {@code fluxgate.reload.pubsub.backstop-polling-interval}.
 *
 * <p>Configuration example:
 *
 * <pre>
 * fluxgate:
 *   reload:
 *     strategy: PUBSUB
 *     pubsub:
 *       channel: fluxgate:rule-reload
 *       retry-on-failure: true
 *       retry-interval: 5s
 *       backstop-polling-interval: 60s
 *       secret: ${FLUXGATE_RELOAD_SECRET}
 *       max-message-age: 60s
 * </pre>
 */
public class RedisPubSubReloadStrategy extends AbstractReloadStrategy {

  /** Message indicating a full reload should occur. */
  public static final String FULL_RELOAD_MESSAGE = "*";

  /**
   * Newest schema version understood by this strategy. Messages with a version other than this one
   * or {@link #LEGACY_MESSAGE_SCHEMA_VERSION} are dropped.
   */
  public static final int MESSAGE_SCHEMA_VERSION = 2;

  /** Schema version of a message without nonce or channel binding, and of one without a version. */
  public static final int LEGACY_MESSAGE_SCHEMA_VERSION = 1;

  /** Default replay window for signed messages. */
  public static final Duration DEFAULT_MAX_MESSAGE_AGE = Duration.ofSeconds(60);

  /**
   * Upper bound of remembered message identities. Only authentic messages are remembered, so
   * reaching it takes a control plane publishing this many changes inside one replay window; a
   * message that would exceed it is ignored rather than accepted without replay protection.
   */
  static final int MAX_REMEMBERED_MESSAGES = 100_000;

  /**
   * Messages waiting for the listener thread. A full one drops further messages with a WARN instead
   * of growing without bound when a publisher floods the channel; the backstop polling (when
   * enabled) still picks up the changes.
   */
  static final int MAX_PENDING_MESSAGES = 10_000;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** Characters of an untrusted payload that reach the log. */
  private static final int MAX_LOGGED_MESSAGE = 256;

  /** Characters of an untrusted rule set id that reach the log. */
  private static final int MAX_LOGGED_RULE_SET_ID = 128;

  private final String redisUri;
  private final String channel;
  private final boolean retryOnFailure;
  private final Duration retryInterval;
  private final boolean isCluster;
  private final Duration timeout;

  /** Shared secret messages must be signed with, or null to accept unsigned messages. */
  private final String secret;

  /** Replay window applied to a signed message's timestamp. */
  private final Duration maxMessageAge;

  /**
   * Whether signed version 1 messages (no nonce, no channel binding) are still accepted. Defaults
   * to true for rolling upgrades from pre-0.4 control planes.
   */
  private volatile boolean acceptLegacySigned = true;

  /** Whether accepting a signed version 1 message has already been reported. */
  private final AtomicBoolean legacySignedWarned = new AtomicBoolean();

  /** Identity (nonce, or signature for version 1) of accepted signed messages -> forget-after. */
  private final ConcurrentHashMap<String, Long> seenMessages = new ConcurrentHashMap<>();

  /** Lettuce client; a {@link RedisClient} or a {@link RedisClusterClient}, created lazily. */
  private final AtomicReference<Object> redisClientRef = new AtomicReference<>();

  /** Whether this strategy created the client and is therefore responsible for shutting it down. */
  private final boolean ownsClient;

  private final AtomicReference<StatefulRedisPubSubConnection<String, String>> connectionRef =
      new AtomicReference<>();
  private final AtomicReference<ScheduledExecutorService> retrySchedulerRef =
      new AtomicReference<>();

  /**
   * Runs the reload listeners off the Lettuce event loop, one message at a time in arrival order. A
   * slow listener (a full bucket reset scanning a large keyspace) would otherwise block the thread
   * that reads the subscription and every other command multiplexed on that event loop.

  // ... 생성자, 구독, 메시지 처리
}
```

### 4.1 메시지 파싱: 알 수 없는 것은 무시합니다

0.3.x의 `handleMessage`에는 심각한 문제가 있었습니다.

```java
// 0.3.x 구조 (위험)
if (message == null || message.isEmpty() || FULL_RELOAD_MESSAGE.equals(message)) {
    event = RuleReloadEvent.fullReload(ReloadSource.PUBSUB);   // ← 빈 메시지가 전체 리셋!
}
```

**빈 메시지가 전체 리로드였습니다.** 파싱 실패도, 스키마 불일치도 마찬가지였습니다. 채널은
인증이 없는 평범한 Redis 채널이고, 공용 Redis라면 **무관한 애플리케이션의 트래픽**이 흘러들어올
수 있습니다. 실수로 흘러든 메시지 하나가 가장 파괴적인 동작(모든 버킷 삭제)을 일으켰습니다.

0.4의 `parseMessage`는 `Optional`을 돌려주고, 이해할 수 없는 것은 WARN을 남기고 **버립니다.**

```java
// RedisPubSubReloadStrategy.java - 실제 코드
private void handleMessage(String message) {
  log.debug("Received Pub/Sub message: {}", sanitizeMessage(message));
  parseMessage(message).ifPresent(this::notifyListeners);
}

/**
 * Parses a Pub/Sub message into a reload event.
 *
 * <p>Package-private so the message table can be tested without a Redis connection.
 *
 * @return the event to publish, or empty when the message must be ignored
 */
Optional<RuleReloadEvent> parseMessage(String message) {
  if (message == null || message.trim().isEmpty()) {
    log.warn("Ignoring empty rule reload message on channel {}", channel);
    return Optional.empty();
  }

  String trimmed = message.trim();

  if (secret != null && !trimmed.startsWith("{")) {
    // Only the JSON form can carry a signature, so the plain-text forms - including the legacy
    // "*" full reload - are exactly the shapes an attacker would use.
    log.warn(
        "Ignoring unsigned rule reload message on channel {} (a secret is configured): {}",
        channel,
        sanitizeMessage(trimmed));
    return Optional.empty();
  }

  if (FULL_RELOAD_MESSAGE.equals(trimmed)) {
    log.info("Full reload triggered via Pub/Sub");
    return Optional.of(fullReloadEvent());
  }

  // Anything that looks like JSON goes through the JSON path, so a JSON array or a malformed
  // object is dropped rather than used verbatim as a rule set id.
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    return parseJsonMessage(trimmed);
  }

  log.info("Reload triggered via Pub/Sub for ruleSetId: {}", sanitizeRuleSetId(trimmed));
  return Optional.of(RuleReloadEvent.forRuleSet(trimmed, ReloadSource.PUBSUB));
}
```

`[`로 시작하는 것까지 JSON 경로로 보내는 것에 주의하세요. 그러지 않으면 JSON 배열이나 깨진 객체가
**그대로 ruleSetId로** 쓰입니다.

### 4.2 JSON 경로: 스키마 버전과 명시적 fullReload

```java
// RedisPubSubReloadStrategy.java - 실제 코드
/**
 * Parses a JSON format message.
 *
 * @param message the JSON message
 * @return the parsed reload event, or empty when the message must be ignored
 */
private Optional<RuleReloadEvent> parseJsonMessage(String message) {
  JsonNode root;
  try {
    root = OBJECT_MAPPER.readTree(message);
  } catch (Exception e) {
    log.warn(
        "Ignoring unparseable rule reload message on channel {}: {} ({})",
        channel,
        sanitizeMessage(message),
        e.getMessage());
    return Optional.empty();
  }

  if (root == null || !root.isObject()) {
    log.warn(
        "Ignoring non-object rule reload message on channel {}: {}",
        channel,
        sanitizeMessage(message));
    return Optional.empty();
  }

  int version = root.path("version").asInt(LEGACY_MESSAGE_SCHEMA_VERSION);
  if (version != MESSAGE_SCHEMA_VERSION && version != LEGACY_MESSAGE_SCHEMA_VERSION) {
    log.warn(
        "Ignoring rule reload message with unknown schema version {} on channel {}: {}",
        version,
        channel,
        sanitizeMessage(message));
    return Optional.empty();
  }

  boolean fullReload = root.path("fullReload").asBoolean(false);
  String ruleSetId = root.path("ruleSetId").asText(null);

  if (secret != null
      && !isAuthentic(
          message,
          version,
          ruleSetId,
          fullReload,
          root.path("timestamp").asLong(0L),
          root.path("source").asText(null),
          root.path("nonce").asText(null),
          root.path("signature").asText(null))) {
    return Optional.empty();
  }

  if (fullReload) {
    log.info("Full reload triggered via Pub/Sub (JSON)");
    return Optional.of(fullReloadEvent());
  }

  if (ruleSetId != null && !ruleSetId.trim().isEmpty()) {
    log.info("Reload triggered via Pub/Sub for ruleSetId: {}", sanitizeRuleSetId(ruleSetId));
    return Optional.of(RuleReloadEvent.forRuleSet(ruleSetId, ReloadSource.PUBSUB));
  }

  log.warn(
      "Ignoring rule reload message without ruleSetId and without fullReload on channel {}: {}",
      channel,
      sanitizeMessage(message));
  return Optional.empty();
}

/** Builds a full reload event with the flag set explicitly rather than relying on a null id. */
private RuleReloadEvent fullReloadEvent() {
  return RuleReloadEvent.builder().source(ReloadSource.PUBSUB).fullReload(true).build();
}
```

세 가지 방어가 겹쳐 있습니다.

| 방어 | 막는 것 |
|-----|--------|
| `asBoolean(false)` | `fullReload`를 **명시**해야 전체 리로드. 필드 누락이 전체 리셋이 되지 않습니다 |
| 스키마 버전 검사 | 버전 1·2 외의 메시지를 임의 해석하지 않습니다. `version`이 없으면 버전 1로 봅니다 |
| `ruleSetId`도 `fullReload`도 없으면 무시 | null id로 전체 리로드를 암시하는 경로를 없앴습니다 |

`fullReloadEvent()`가 플래그를 **명시적으로 세팅**하는 것도 같은 원칙입니다. "id가 null이면 전체"
같은 암묵적 규약은 버그가 새어 들어올 틈입니다.

### 4.3 HMAC 서명: 의도적인 공격에 대한 방어

파싱 강화는 **실수로** 흘러든 메시지를 막습니다. 의도적인 공격에는 아무 도움이 되지 않습니다.
Javadoc의 표현이 정확합니다.

> Parsing hardening keeps an accidental message from doing damage; it does nothing against a
> deliberate one, because `PUBLISH fluxgate:rule-reload '*'` is all a full reset takes.

Redis에 닿을 수 있는 누구든 한 줄로 배포 전체의 버킷을 날릴 수 있습니다.

```java
// RedisPubSubReloadStrategy.java - 실제 코드
 * Checks the signature, the replay window and the replay cache of a message.
 *
 * <p>Only called when a secret is configured. A missing signature, a signature computed with
 * another secret or for another channel, a tampered field, a timestamp outside the replay window
 * and a message already seen all end here, because from the outside they are the same thing: a
 * message this data plane did not authorise (this time).
 *
 * @return true when the message may be acted on
 */
private boolean isAuthentic(
    String message,
    int version,
    String ruleSetId,
    boolean fullReload,
    long timestamp,
    String source,
    String nonce,
    String signature) {

  if (signature == null || signature.trim().isEmpty()) {
    log.warn(
        "Ignoring unsigned rule reload message on channel {} (a secret is configured): {}",
        channel,
        sanitizeMessage(message));
    return false;
  }

  boolean legacy = version == LEGACY_MESSAGE_SCHEMA_VERSION;
  if (legacy && !acceptLegacySigned) {
    log.warn(
        "Ignoring signed version 1 rule reload message on channel {}: "
            + "fluxgate.reload.pubsub.accept-legacy-signed=false (version 1 binds neither the "
            + "channel nor a nonce): {}",
        channel,
        sanitizeMessage(message));
    return false;
  }
  if (!legacy && (nonce == null || nonce.trim().isEmpty())) {
    log.warn(
        "Ignoring version {} rule reload message without a nonce on channel {}: {}",
        version,
        channel,
        sanitizeMessage(message));
    return false;
  }

  String canonical =
      legacy
          ? HmacSigner.canonicalRuleChange(version, ruleSetId, fullReload, timestamp, source)
          : HmacSigner.canonicalRuleChangeV2(
              version, channel, ruleSetId, fullReload, timestamp, source, nonce);
  if (!HmacSigner.verify(secret, canonical, signature)) {
    log.warn(
        "Ignoring rule reload message with an invalid signature on channel {}: {}",
        channel,
        sanitizeMessage(message));
    return false;
  }

  long ageMillis = System.currentTimeMillis() - timestamp;
  if (Math.abs(ageMillis) > maxMessageAge.toMillis()) {
    log.warn(
        "Ignoring rule reload message on channel {}: its timestamp is {}ms away from now, outside "
            + "the {} replay window",
        channel,
        ageMillis,
        maxMessageAge);
    return false;
  }

  // Only an authentic, fresh message reaches the cache, so a forger cannot fill it.
  String identity =
      legacy ? "sig:" + signature.trim().toLowerCase(Locale.ROOT) : "nonce:" + nonce;
  if (!rememberFirstDelivery(identity, timestamp)) {
    log.warn(
        "Ignoring replayed rule reload message on channel {} (already processed inside the {} "
            + "replay window): {}",
        channel,
        maxMessageAge,
        sanitizeMessage(message));
    return false;
  }

  if (legacy && legacySignedWarned.compareAndSet(false, true)) {
    log.warn(
        "Accepted a signed version 1 rule reload message on channel {}. Version 1 binds neither "
            + "the channel nor a nonce; upgrade the publisher (fluxgate-control-support 0.4+ "
            + "signs version 2) and then set fluxgate.reload.pubsub.accept-legacy-signed=false. "
            + "Reported once.",
        channel);
  }
  return true;
}

/**
 * Records a message identity for the rest of the replay window.
 *
 * <p>The identity has to be remembered for as long as the timestamp check would still accept the
 * message, which is until {@code timestamp + maxMessageAge}; the local clock is used as the floor
 * so a message from a publisher whose clock runs behind is not forgotten early.
 *
 * @return true on the first delivery, false for a replay (or when the cache is full)
 */
private boolean rememberFirstDelivery(String identity, long timestamp) {
  long now = System.currentTimeMillis();
  seenMessages.values().removeIf(forgetAfter -> forgetAfter < now);
  if (seenMessages.size() >= MAX_REMEMBERED_MESSAGES) {
    log.warn(
        "Replay cache is full ({} messages inside the {} window); ignoring the message",
        MAX_REMEMBERED_MESSAGES,
        maxMessageAge);
    return false;
  }
  long forgetAfter = Math.max(now, timestamp) + maxMessageAge.toMillis();
  return seenMessages.putIfAbsent(identity, forgetAfter) == null;
}
```

| 검사 | 막는 것 |
|-----|--------|
| 서명 존재 | 서명 없는 메시지 (평문 `*` 포함) |
| 버전 1 허용 여부 (`accept-legacy-signed`) | 채널·nonce를 묶지 않는 버전 1 서명 메시지. 기본값 `true`(롤링 업그레이드용), 모든 발행자가 버전 2로 서명하면 `false`로 |
| 버전 2의 `nonce` 존재 | nonce 없는 버전 2 메시지 |
| `HmacSigner.verify` | 다른 비밀로 만든 서명, 필드가 조작된 메시지, (버전 2) **다른 채널용으로 서명된** 메시지 |
| `Math.abs(ageMillis) > maxMessageAge` | 재전송 창(기본 60초) 밖의 메시지 — 과거에 캡처한 유효 메시지의 재사용 |
| `rememberFirstDelivery` | 창 **안에서의** 재전송. 버전 2는 nonce, 버전 1은 서명을 식별자로 창이 끝날 때까지 기억합니다 |

`Math.abs`인 것에 주의하세요. 미래 타임스탬프도 거부합니다. 시계가 앞선 발행자의 메시지가
무기한 유효하게 남는 것을 막습니다. 재전송 캐시에는 서명과 시간 검사를 통과한 메시지만 들어가므로
위조자가 캐시를 채울 수 없고, 상한(`MAX_REMEMBERED_MESSAGES`)에 닿으면 재전송 보호 없이 받아들이는
대신 메시지를 버립니다.

서명이 전체 페이로드가 아니라 **정규 문자열**에 대해 계산되는 것도 의도입니다. 버전 2는
`HmacSigner.canonicalRuleChangeV2(version, channel, ruleSetId, fullReload, timestamp, source, nonce)`,
버전 1은 `HmacSigner.canonicalRuleChange(version, ruleSetId, fullReload, timestamp, source)`입니다.
JSON 직렬화 방식(키 순서, 공백)이 달라도 같은 의미의 메시지는 같은 서명을 갖고, 버전 2는 구독 채널을
서명에 묶으므로 한 채널용 메시지를 다른 채널에 재발행해도 통하지 않습니다.

### 4.4 비밀이 설정되지 않았을 때

```java
// RedisPubSubReloadStrategy.java - 실제 코드
@Override
protected void doStart() {
  // ... 메시지 실행기(fluxgate-pubsub-listener)와 재시도 스케줄러 생성

  subscribe();
  if (secret != null) {
    log.info(
        "Redis Pub/Sub reload strategy started on channel: {} (verifying HMAC-SHA256 signatures, "
            + "replay window {})",
        channel,
        maxMessageAge);
    if (HmacSigner.isWeakSecret(secret)) {
      log.warn(
          "fluxgate.reload.pubsub.secret is shorter than {} bytes. A short HMAC secret can be "
              + "brute-forced offline from a single captured message; use at least {} random bytes.",
          HmacSigner.MIN_RECOMMENDED_SECRET_BYTES,
          HmacSigner.MIN_RECOMMENDED_SECRET_BYTES);
    }
  } else {
    log.info("Redis Pub/Sub reload strategy started on channel: {}", channel);
    log.info(
        "fluxgate.reload.pubsub.secret is not set: any publisher able to reach this Redis can "
            + "trigger a full reload on channel '{}' and drop every token bucket. Set it here and "
            + "fluxgate.control.secret on the control plane to the same value.",
        channel);
  }
```

전략 클래스를 시크릿 없이 직접 생성하면 0.3.x 동작(서명 검증 없음)이 **그대로 유지되며**, 부팅 시 INFO 한
줄로 그 사실과 위험, 그리고 해결 방법을 알립니다. 스타터의 자동 구성은 다릅니다. 시크릿이 없으면 `AUTO`는
WARN과 함께 폴링으로 대체하고, 명시적 `PUBSUB`는 기동을 실패시키며, `allow-unsigned=true`만이 서명 없는
채널을 엽니다.

### 4.5 연결 관리: 누수와 중복 방지

```java
// RedisPubSubReloadStrategy.java - 실제 코드 (subscribe 요지)
RedisPubSubCommands<String, String> sync = connection.sync();
sync.subscribe(channel);

// A retry must not leave the previous connection open: a flapping Redis would otherwise pile
// them up until the process runs out of connections.
closeConnection(connectionRef.getAndSet(connection), false);
```

```java
// RedisPubSubReloadStrategy.java - 실제 코드 (doStop 요지)
@Override
protected void doStop() {
  closeConnection(connectionRef.getAndSet(null), true);
  ...
  // The client owns a Netty event loop group: leaking it leaks threads and file descriptors on
  // every context restart.
  if (ownsClient) {
    shutdownClient(redisClientRef.getAndSet(null));
  }

  log.info("Redis Pub/Sub reload strategy stopped");
}
```

두 가지 누수를 막습니다.

**(1) 재시도 시 이전 연결.** Redis가 불안정하게 붙었다 끊기면(flapping) 재시도마다 연결이 하나씩
쌓여 결국 연결이 고갈됩니다. `getAndSet`으로 교체하면서 이전 것을 닫습니다.

**(2) Netty 이벤트 루프 그룹.** Lettuce 클라이언트는 자기 스레드 풀을 소유합니다. Spring 컨텍스트가
재시작될 때마다 클라이언트를 누수하면 **스레드와 파일 디스크립터가 함께** 누수됩니다.
`ownsClient`가 true일 때만(= 이 전략이 URI로 직접 만들었을 때만) 종료합니다. 외부에서 받은
클라이언트는 그 소유자가 닫습니다.

`ensureClient()`의 경쟁 조건 처리도 같은 계열입니다.

```java
// RedisPubSubReloadStrategy.java - 실제 코드
if (!redisClientRef.compareAndSet(null, created)) {
  // Another thread won the race; discard ours rather than leaking it.
  shutdownClient(created);
  return redisClientRef.get();
}
```

### 4.6 untrusted 페이로드는 로그에도 그대로 들어가지 않습니다

```java
// RedisPubSubReloadStrategy.java - 실제 코드
/** Characters of an untrusted payload that reach the log. */
private static final int MAX_LOGGED_MESSAGE = 256;

/** Characters of an untrusted rule set id that reach the log. */
private static final int MAX_LOGGED_RULE_SET_ID = 128;

/** Caps and strips control characters from an untrusted payload before it reaches the log. */
private static String sanitizeMessage(String message) {
  return LogSanitizer.sanitize(message, MAX_LOGGED_MESSAGE);
}
```

WARN 로그에 원본 메시지를 남기는 것은 진단에 필요하지만, 그 메시지는 **누가 보냈는지 알 수 없는
입력**입니다. CRLF가 들어 있으면 로그 줄을 위조할 수 있고, 거대한 메시지는 로그를 폭주시킵니다.
길이 제한 + 제어문자 제거를 거칩니다.

### 설정

```yaml
fluxgate:
  reload:
    strategy: PUBSUB
    pubsub:
      channel: fluxgate:rule-reload
      retry-on-failure: true
      retry-interval: 5s
      backstop-polling-interval: 60s        # Pub/Sub 유실 대비 (0이면 백스톱 없음)
      secret: ${FLUXGATE_RELOAD_SECRET}     # 발행자의 fluxgate.control.secret과 동일하게
      max-message-age: 60s                  # 서명 메시지의 재전송 허용 창 (기본값)
      accept-legacy-signed: true            # 0.4 이전 발행자의 v1 서명 메시지 수용. 모두 v2로 서명하면 false
      # allow-unsigned: true                # 개발 전용 - secret 없이 기동 (WARN)
```

0.4부터 Pub/Sub는 `secret`이 있어야 동작합니다. 없으면 `AUTO`는 WARN 후 폴링으로 대체되고,
명시적 `PUBSUB`는 `IllegalStateException`으로 기동이 실패합니다. `allow-unsigned: true`일 때만 아래 표의
"`secret` 없음" 열처럼 동작합니다. 컨트롤 플레인의 `fluxgate.control.secret` /
`fluxgate.control.allow-unsigned`도 같은 규칙을 따릅니다.

### 메시지 형식

| 메시지 | `secret` 없음 | `secret` 설정됨 |
|-------|--------------|----------------|
| `{"version":2,"ruleSetId":"xxx","fullReload":false,"timestamp":...,"nonce":"...","signature":"..."}` | 특정 룰셋 리로드 | 서명(구독 채널·`nonce` 바인딩)·시각이 유효하고 처음 보는 `nonce`면 리로드, 아니면 **무시** |
| `{"version":2,"fullReload":true,...,"nonce":"...","signature":"..."}` | 전체 리로드 | 같은 조건이면 전체 리로드, 아니면 **무시** |
| `{"version":2,...}` 인데 `nonce` 없음 | 리로드 (서명·`nonce` 검사 안 함) | **무시** (WARN) |
| `{"version":1,...,"signature":"..."}` 또는 `version` 생략 (v1 레거시) | 그대로 리로드 | `accept-legacy-signed=true`(기본값)이고 서명·시각이 유효하며 같은 서명을 처음 볼 때만 리로드, 아니면 **무시** |
| `*` | 전체 리로드 (하위 호환) | **무시** (서명을 담을 수 없는 형태) |
| `my-rule-set` | 해당 룰셋 리로드 (하위 호환) | **무시** |
| 빈 문자열 / 공백 | **무시** (WARN) | **무시** (WARN) |
| 파싱 실패, JSON 배열, 비객체 | **무시** (WARN) | **무시** (WARN) |
| `{"version":3,...}` 등 1·2 이외의 버전 | **무시** (WARN) — 스키마 불일치 | **무시** (WARN) |
| `{}` (ruleSetId·fullReload 모두 없음) | **무시** (WARN) | **무시** (WARN) |

0.3.x와의 차이를 한 줄로: **빈 메시지와 파싱 실패가 더 이상 전체 리셋이 아닙니다.**

### 동작 방식

```
+------------------+    Pub     +---------+    Sub     +------------------+
|  Admin Server    | ---------> |  Redis  | ---------> |  App Instance 1  |
|  (규칙 수정)      |            | Channel |            |                  |
|  HMAC 서명 첨부   |            +---------+            |  서명 검증 후 적용 |
+------------------+                 |                 +------------------+
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

Redis Pub/Sub은 **at-most-once**입니다. 구독자가 잠시 끊긴 사이에 발행된 메시지는 사라집니다.
그 인스턴스는 캐시 TTL이 만료될 때까지 낡은 규칙을 씁니다. 그래서 스타터는 이 전략을 폴링
백스톱과 조합합니다.

---

## 5. CompositeReloadStrategy (Pub/Sub + 폴링 백스톱)

```
fluxgate-spring-boot3-starter/src/main/java/org/fluxgate/spring/reload/strategy/
└── CompositeReloadStrategy.java
```

```java
// CompositeReloadStrategy.java - 실제 코드
/**
 * Reload strategy that runs a primary strategy together with one or more backstops.
 *
 * <p>Used to pair {@link RedisPubSubReloadStrategy} with a low-frequency {@link
 * PollingReloadStrategy}: Pub/Sub delivers changes within milliseconds but is at-most-once, so a
 * dropped message would leave this instance serving stale rules until the cache TTL expires. The
 * polling backstop makes the node converge within its interval regardless.
 *
 * <p>Events from every delegate are funnelled through this strategy's own listener list, so the
 * ordering contract of {@link AbstractReloadStrategy} (cache invalidation before bucket reset)
 * holds no matter which delegate detected the change.
 */
public class CompositeReloadStrategy extends AbstractReloadStrategy implements RuleReloadListener {

  private final RuleReloadStrategy primary;
  private final List<RuleReloadStrategy> backstops;
  private final ReloadSource source;

  public CompositeReloadStrategy(RuleReloadStrategy primary, RuleReloadStrategy... backstops) {
    this.primary = Objects.requireNonNull(primary, "primary must not be null");
    this.backstops = new ArrayList<>(Arrays.asList(Objects.requireNonNull(backstops)));
    this.backstops.forEach(
        backstop -> Objects.requireNonNull(backstop, "backstop must not be null"));
    this.source =
        primary instanceof AbstractReloadStrategy
            ? ((AbstractReloadStrategy) primary).getReloadSource()
            : ReloadSource.MANUAL;

    primary.addListener(this);
    this.backstops.forEach(backstop -> backstop.addListener(this));
  }
}
```

### 두 전략을 합치는 이유

| 전략 | 지연 | 전달 보장 |
|-----|------|----------|
| Pub/Sub | 밀리초 | **at-most-once** — 유실 가능 |
| 폴링 | 최대 interval | 결국 반영됨 (eventually consistent) |

Pub/Sub 하나만 쓰면 메시지 유실 시 **캐시 TTL(기본 5분)까지** 낡은 규칙을 씁니다. 폴링 하나만
쓰면 항상 interval만큼 늦습니다. 조합하면 정상 경로는 밀리초, 유실 시에도 백스톱 interval 안에
수렴합니다.

### 이중 리셋 방지: markSeen

여기가 이 클래스의 핵심 로직입니다.

```java
// CompositeReloadStrategy.java - 실제 코드
/**
 * Republishes an event detected by one of the delegates to this strategy's own listeners and
 * synchronises every polling backstop's version map, so the backstop's next poll does not treat
 * the already-delivered change as a new one and fire a second reset.
 */
@Override
public void onReload(RuleReloadEvent event) {
  notifyListeners(event);
  for (RuleReloadStrategy backstop : backstops) {
    if (backstop instanceof PollingReloadStrategy) {
      PollingReloadStrategy polling = (PollingReloadStrategy) backstop;
      if (event.isFullReload()) {
        polling.markAllSeen();
      } else {
        polling.markSeen(event.getRuleSetId());
      }
    }
  }
}
```

`markSeen`이 없으면 다음 순서가 일어납니다.

```
00:00.000  관리자가 룰셋 변경 + Pub/Sub 발행
00:00.005  Pub/Sub 전략이 감지 → 캐시 무효화 + 버킷 리셋   ← 정상
           (폴링의 versionMap은 여전히 이전 버전)
00:60.000  폴링 백스톱이 돌아옴 → "버전이 달라졌다!" → 버킷을 또 리셋  ← 중복
           → 모든 클라이언트가 60초 뒤에 quota를 한 번 더 받습니다
```

`markSeen(ruleSetId)`은 **이벤트를 발행하지 않고** 폴링의 `versionMap`만 현재 버전으로 갱신합니다.
그래서 다음 폴링이 그것을 변경으로 보지 않습니다.

전체 리로드일 때는 `markAllSeen()`으로 맵을 비웁니다. 그러면 다음 폴링이 모든 룰셋을 **최초 관측**으로
취급해(위 `checkForChange`의 `previousVersion == null` 분기) 역시 이벤트를 내지 않습니다.

### 리스너 순서 계약의 보존

Javadoc의 두 번째 단락이 중요합니다.

> Events from every delegate are funnelled through this strategy's own listener list, so the
> ordering contract of `AbstractReloadStrategy` (cache invalidation before bucket reset) holds no
> matter which delegate detected the change.

리스너를 각 delegate에 따로 등록하면, 어느 쪽이 감지했는지에 따라 순서 보장이 달라집니다.
Composite은 자기 자신을 모든 delegate의 리스너로 등록하고, 받은 이벤트를 **자기 리스너 목록으로
다시 발행**합니다. 순서는 한 곳에서만 결정됩니다.

```java
// AbstractReloadStrategy.java - 실제 코드 Javadoc
/**
 * Notifies all registered listeners of a reload event, in ascending order.
 *
 * <p>Listeners sharing an order all run even if one of them fails; a failure then skips every
 * listener with a strictly higher order, so a failed cache invalidation never leads to a bucket
 * reset against stale rules.
 */
protected void notifyListeners(RuleReloadEvent event) { ... }
```

캐시 무효화가 실패했는데 버킷을 리셋하면, **낡은 규칙으로** 가득 찬 버킷이 새로 만들어집니다.
그래서 순서가 낮은 그룹이 실패하면 더 높은 순서는 건너뜁니다.

### 시작과 종료 순서

```java
// CompositeReloadStrategy.java - 실제 코드
@Override
protected void doStart() {
  primary.start();
  backstops.forEach(RuleReloadStrategy::start);
  log.info(
      "Composite reload strategy started: primary={}, backstops={}",
      primary.getClass().getSimpleName(),
      backstops.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.toList()));
}

@Override
protected void doStop() {
  for (int i = backstops.size() - 1; i >= 0; i--) {
    backstops.get(i).stop();
  }
  primary.stop();
  log.info("Composite reload strategy stopped");
}
```

종료는 시작의 **역순**입니다. 백스톱을 먼저 멈춰 primary가 아직 살아 있는 동안에는 유실 대비가
유지되도록 합니다.

### 설정

```yaml
fluxgate:
  reload:
    strategy: PUBSUB
    pubsub:
      backstop-polling-interval: 60s   # > 0 이면 Composite이 만들어집니다
```

`backstop-polling-interval`이 0이면 `RedisPubSubReloadStrategy` 단독으로 등록됩니다. 그 경우
메시지 유실이 캐시 TTL까지 낡은 규칙으로 이어진다는 사실을 받아들이는 선택입니다.

---

## 6. BucketResetHandler

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
/**
 * Redis implementation of {@link BucketResetHandler}.
 *
 * <p>The store is resolved through a {@link Supplier} so the handler can be created before the
 * Redis connection is established: when Redis is still unavailable, a reset is logged and skipped
 * instead of failing the reload. Deleting only bucket keys is the store's responsibility.
 */
public class RedisBucketResetHandler implements BucketResetHandler, RuleReloadListener {

  private final Supplier<RedisTokenBucketStore> tokenBucketStoreSupplier;

  public RedisBucketResetHandler(RedisTokenBucketStore tokenBucketStore) {
    Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
    this.tokenBucketStoreSupplier = () -> tokenBucketStore;
  }

  /**
   * Creates a new RedisBucketResetHandler that resolves the store on each reset.
   *
   * @param tokenBucketStoreSupplier supplier of the Redis token bucket store; may throw while the
   *     connection is not established yet
   */
  public RedisBucketResetHandler(Supplier<RedisTokenBucketStore> tokenBucketStoreSupplier) {
    this.tokenBucketStoreSupplier =
        Objects.requireNonNull(tokenBucketStoreSupplier, "tokenBucketStoreSupplier must not null");
  }

  @Override
  public void resetBuckets(String ruleSetId) {
    Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    RedisTokenBucketStore store = resolveStore("ruleSetId " + ruleSetId);
    if (store == null) {
      return;
    }
    log.info("Resetting token buckets for ruleSetId: {}", ruleSetId);
    long deleted = store.deleteBucketsByRuleSetId(ruleSetId);
    log.info("Reset complete: {} buckets deleted for ruleSetId: {}", deleted, ruleSetId);
  }

  @Override
  public void resetAllBuckets() {
    RedisTokenBucketStore store = resolveStore("all rule sets");
    if (store == null) {
      return;
    }
    log.info("Resetting all token buckets (full reset)");
    long deleted = store.deleteAllBuckets();
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

  /** Resolves the store, returning null and logging a warning when Redis is unavailable. */
  private RedisTokenBucketStore resolveStore(String what) { ... }
}
```

### 0.4에서 달라진 세 가지

**(1) 스토어를 `Supplier`로 해석합니다.** `LazyRedisRateLimiter` 때문에 스토어는 부팅 시점에
존재하지 않을 수 있습니다. 생성자에서 스토어를 직접 붙잡으면 Redis가 아직 안 붙은 상태에서
핸들러를 만들 수 없습니다. `Supplier`로 두면 리셋마다 해석하고, 그때도 Redis가 없으면
**WARN을 남기고 리셋을 건너뜁니다.** 리로드 자체가 실패하지는 않습니다.

**(2) 삭제 범위가 버킷 키로 한정됩니다.** Javadoc이 `Deleting only bucket keys is the store's
responsibility`라고 말하는 대로, 패턴은 스토어가 만듭니다.

| 메서드 | 패턴 | 0.3.x |
|-------|------|-------|
| `deleteBucketsByRuleSetId(id)` | `RedisRateLimiter.bucketKeyPattern(id)` → `fluxgate:bucket:{<id>:*` | `fluxgate:<id>:*` |
| `deleteAllBuckets()` | `fluxgate:bucket:*` | `fluxgate:*` ← **룰셋 정의까지 삭제** |

**(3) `KEYS` + `DEL` → `SCAN` + `UNLINK`.** 전체 리셋이 단일 스레드 Redis를 블로킹하지 않습니다.
자세한 내용은 [Storage Layer](storage-layer.ko.md#5-버킷-삭제-scan--unlink)에 있습니다.

### 인메모리 리셋 핸들러도 함께 등록됩니다

`fluxgate.ratelimit.fallback.mode=IN_MEMORY`면 Redis 핸들러와 인메모리 핸들러가 **공존합니다.**
규칙이 바뀌면 두 저장소를 모두 비워야 하기 때문입니다.

```java
// FluxgateRateLimiterAutoConfiguration.java - 실제 코드 주석
/**
 * <p>Exposed as its own bean rather than created inline, so the reload configuration's {@code
 * inMemoryBucketResetHandler} - conditional on a {@link Bucket4jRateLimiter} bean - can see it
 * and clear its buckets when a rule changes. An inline instance was invisible to every reset
 * handler and kept enforcing the superseded bands until its idle eviction.
 */
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

## 7. 전체 흐름 요약

### 리스너 등록 및 조합

```java
// FluxgateReloadAutoConfiguration.java - 실제 코드 (요지)
CachingRuleSetProvider cachingProvider = new CachingRuleSetProvider(ruleSetProvider, ruleCache);

RuleReloadStrategy reloadStrategy = reloadStrategyProvider.getIfAvailable();
if (reloadStrategy == null) {
  log.warn("No RuleReloadStrategy found; cache invalidation listeners will not be registered");
  return cachingProvider;
}

// Cache invalidation must run before bucket reset: the reverse order lets an in-flight request
// recreate a bucket from the stale rule after it has been deleted.
addListener(reloadStrategy, cachingProvider, AbstractReloadStrategy.ORDER_CACHE_INVALIDATION);

// Register every reset handler in order; both Redis and in-memory handlers coexist when
// fluxgate.ratelimit.fallback.mode=IN_MEMORY so that rule changes clear both stores.
bucketResetHandlerProvider
    .orderedStream()
    .filter(h -> h instanceof RuleReloadListener)
    .forEach(
        h -> {
          log.info("Registering {} as reload listener", h.getClass().getSimpleName());
          addListener(
              reloadStrategy,
              (RuleReloadListener) h,
              AbstractReloadStrategy.ORDER_BUCKET_RESET);
        });
```

### 순서가 왜 중요한가

```java
// AbstractReloadStrategy.java - 실제 코드
public static final int ORDER_CACHE_INVALIDATION = -100;
public static final int ORDER_BUCKET_RESET = 100;
```

낮은 값이 먼저 실행됩니다. 주석이 그 이유를 말합니다.

> Cache invalidation must run before bucket reset: the reverse order lets an in-flight request
> recreate a bucket from the stale rule after it has been deleted.

```
잘못된 순서 (버킷 리셋 → 캐시 무효화):
  1. 버킷 삭제
  2. (그 사이) in-flight 요청이 들어옴
     → 캐시에는 아직 낡은 규칙이 있음
     → 낡은 규칙(capacity 100)으로 버킷을 가득 찬 상태로 재생성
  3. 캐시 무효화
     → 이제 새 규칙(capacity 10)을 읽지만, 버킷에는 토큰 100개가 들어 있습니다

올바른 순서 (캐시 무효화 → 버킷 리셋):
  1. 캐시 무효화 → 다음 요청은 새 규칙을 읽습니다
  2. 버킷 삭제 → 재생성되는 버킷은 새 규칙(capacity 10)을 따릅니다
```

실패 처리도 이 계약을 따릅니다.

```java
// AbstractReloadStrategy.java - 실제 코드 Javadoc
/**
 * Notifies all registered listeners of a reload event, in ascending order.
 *
 * <p>Listeners sharing an order all run even if one of them fails; a failure then skips every
 * listener with a strictly higher order, so a failed cache invalidation never leads to a bucket
 * reset against stale rules.
 */
protected void notifyListeners(RuleReloadEvent event) { ... }
```

캐시 무효화가 실패하면 버킷 리셋은 **실행되지 않습니다.** 낡은 규칙으로 버킷이 재생성되는 것보다,
리셋이 한 주기 늦는 편이 안전합니다.

### Hot Reload 전체 흐름

```
+-----------------------------------------------------------------------+
|  Admin이 MongoDB에서 규칙 수정                                          |
|                   |                                                    |
|                   v                                                    |
|  Redis Pub/Sub으로 변경 이벤트 발행 (서명 포함)                          |
|    PUBLISH fluxgate:rule-reload                                        |
|      '{"version":2,"ruleSetId":"my-rule-set","fullReload":false,       |
|        "timestamp":1703001234567,"nonce":"<random>",                   |
|        "signature":"<HMAC-SHA256>"}'                                   |
|                   |                                                    |
|                   v                                                    |
|  +---------------------------------------------------------------+    |
|  |  모든 애플리케이션 인스턴스가 이벤트 수신                           |    |
|  |                                                                |    |
|  |  (1) RedisPubSubReloadStrategy.parseMessage()                 |    |
|  |      -> 스키마 버전 + (설정 시) HMAC 서명 · 재전송 창 검증        |    |
|  |      -> Optional<RuleReloadEvent>  (이해 못 하면 WARN + 무시)    |    |
|  |                                                                |    |
|  |  (2) 등록된 리스너들에게 순서대로 전파 (낮은 order 먼저)         |    |
|  |      |                                                         |    |
|  |      +-> [order -100] CachingRuleSetProvider.onReload()        |    |
|  |      |      -> cache.invalidate(ruleSetId)                     |    |
|  |      |      -> 캐시된 규칙 + 부정 캐시 항목 삭제                  |    |
|  |      |      (실패하면 아래 order는 건너뜁니다)                    |    |
|  |      |                                                         |    |
|  |      +-> [order  100] RedisBucketResetHandler.onReload()       |    |
|  |             -> tokenBucketStore.deleteBucketsByRuleSetId()     |    |
|  |             -> SCAN + UNLINK로 fluxgate:bucket:{<id>:* 삭제     |    |
|  |                                                                |    |
|  |  (2') Composite이면 폴링 백스톱의 versionMap도 동기화            |    |
|  |      -> polling.markSeen(ruleSetId)  (이중 리셋 방지)           |    |
|  |                                                                |    |
|  |  (3) 다음 요청 시 MongoDB에서 새 규칙 로드                       |    |
|  |      -> 새 규칙으로 Rate Limiting 적용                          |    |
|  +---------------------------------------------------------------+    |
+-----------------------------------------------------------------------+
```

---

## 전략 선택 가이드

| 상황 | 추천 설정 | 이유 |
|-----|----------|------|
| 프로덕션 (Redis 사용) | `strategy: PUBSUB` + `backstop-polling-interval: 60s` | 밀리초 전파 + 메시지 유실 시 60초 내 수렴. 스타터가 `CompositeReloadStrategy`로 조합합니다 |
| 공용 Redis / 다중 테넌트 | 위 + `pubsub.secret` 설정 | 서명 없는 메시지로 전체 버킷을 날리는 것을 막습니다 |
| Redis 없음 | `strategy: POLLING` | Redis 의존성 없음. interval만큼의 지연을 받아들이는 선택 |
| 개발/테스트 | `strategy: NONE` | 리로드 비활성화 |

> **Pub/Sub 단독은 권장하지 않습니다.** `backstop-polling-interval: 0`으로 백스톱을 끄면,
> 메시지 유실이 캐시 TTL(기본 5분)까지 낡은 규칙으로 이어집니다.

```java
// FluxgateReloadAutoConfiguration.java - 실제 코드 주석
/**
 * lost, so a dropped notification used to leave this instance on the old rules until the cache
 * TTL expired - and under the PUBSUB strategy there was no other path back to consistency at all.
 * The backstop polls at {@code fluxgate.reload.pubsub.backstop-polling-interval} (60s by default,
 * 0 disables) so the node converges regardless.
 */
```

---

## 관련 문서

- [Storage Layer Deep Dive](storage-layer.ko.md) - 버킷 삭제 (SCAN + UNLINK)
- [Engine Layer Deep Dive](engine-layer.ko.md) - CachingRuleSetProvider, 부정 캐싱
- [Redis RateLimiter Module Deep Dive](redis-ratelimiter.ko.md) - 버킷 키 레이아웃
- [아키텍처 개요](../README.ko.md)
