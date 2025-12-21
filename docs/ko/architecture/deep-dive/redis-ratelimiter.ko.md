# Redis RateLimiter Module Deep Dive

이 문서는 `fluxgate-redis-ratelimiter` 모듈을 **실제 소스코드**와 함께 상세히 설명합니다.

[< 아키텍처 개요로 돌아가기](../README.ko.md)

---

## 목차

1. [모듈 구조](#1-모듈-구조)
2. [RedisRateLimiter](#2-redisratelimiter)
3. [Redis Connection Layer](#3-redis-connection-layer)
4. [RedisRateLimiterConfig](#4-redisratelimiterconfig)
5. [사용 예제](#5-사용-예제)

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
│   └── RedisConnectionException.java  # 예외 클래스
├── store/
│   ├── RedisTokenBucketStore.java     # 토큰 버킷 저장소
│   ├── BucketState.java               # 버킷 상태 객체
│   ├── RedisRuleSetStore.java         # RuleSet 저장소
│   └── RuleSetData.java               # RuleSet 데이터 객체
├── script/
│   ├── LuaScriptLoader.java           # Lua 스크립트 로더
│   └── LuaScripts.java                # 스크립트 저장소
└── health/
    └── RedisHealthCheckerImpl.java    # 헬스체크 구현
```

### 의존성 관계

```
+-------------------+
|  RedisRateLimiter |  ← RateLimiter 인터페이스 구현
+-------------------+
         |
         | uses
         v
+------------------------+
|  RedisTokenBucketStore |  ← Lua 스크립트로 원자적 토큰 소비
+------------------------+
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

---

## 2. RedisRateLimiter

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/
└── RedisRateLimiter.java
```

`RateLimiter` 인터페이스의 Redis 기반 구현입니다.

```java
// RedisRateLimiter.java - 실제 코드
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "fluxgate";
    private final RedisTokenBucketStore tokenBucketStore;

    public RedisRateLimiter(RedisTokenBucketStore tokenBucketStore) {
        this.tokenBucketStore =
                Objects.requireNonNull(tokenBucketStore, "tokenBucketStore must not be null");
    }

    @Override
    public RateLimitResult tryConsume(
            RequestContext context, RateLimitRuleSet ruleSet, long permits) {

        List<RateLimitRule> rules = ruleSet.getRules();
        if (rules == null || rules.isEmpty()) {
            return RateLimitResult.allowedWithoutRule();
        }

        // Multi-Rule Rate Limiting
        // 각 Rule은 다른 LimitScope를 가질 수 있음 (PER_IP, PER_USER, PER_API_KEY 등)
        RateLimitRule matchedRule = null;
        RateLimitKey matchedKey = null;
        long minRemainingTokens = Long.MAX_VALUE;
        long maxNanosToWait = 0;
        boolean anyRejected = false;

        for (RateLimitRule rule : rules) {
            if (!rule.isEnabled()) continue;

            List<RateLimitBand> bands = rule.getBands();
            if (bands == null || bands.isEmpty()) continue;

            // 이 Rule의 LimitScope에 따라 Key 해석
            RateLimitKey logicalKey = ruleSet.getKeyResolver().resolve(context, rule);

            // 각 Band별 토큰 소비 시도
            for (RateLimitBand band : bands) {
                String bucketKey = buildBucketKey(ruleSet.getId(), rule.getId(), logicalKey, band);
                BucketState state = tokenBucketStore.tryConsume(bucketKey, band, permits);

                minRemainingTokens = Math.min(minRemainingTokens, state.remainingTokens());

                if (!state.consumed()) {
                    anyRejected = true;
                    maxNanosToWait = Math.max(maxNanosToWait, state.nanosToWaitForRefill());
                    matchedRule = rule;
                    matchedKey = logicalKey;
                }
            }
        }

        // 하나라도 거부되면 전체 요청 거부
        if (anyRejected) {
            return RateLimitResult.rejected(matchedKey, matchedRule, maxNanosToWait);
        } else {
            return RateLimitResult.allowed(matchedKey, matchedRule, minRemainingTokens, 0L);
        }
    }

    /**
     * Redis 키 형식: fluxgate:{ruleSetId}:{ruleId}:{keyValue}:{bandLabel}
     * 예: fluxgate:api-limits:per-ip-rule:192.168.1.100:100-per-minute
     */
    private String buildBucketKey(
            String ruleSetId, String ruleId, RateLimitKey key, RateLimitBand band) {
        String bandLabel = band.getLabel() != null ? band.getLabel() : "default";
        return String.format("%s:%s:%s:%s:%s", KEY_PREFIX, ruleSetId, ruleId, key.value(), bandLabel);
    }
}
```

### Multi-Rule / Multi-Band 처리 흐름

```
RuleSet (api-limits)
├── Rule 1: PER_IP (10 req/sec)
│   ├── Band: 10-per-second
│   └── Key: 192.168.1.100
├── Rule 2: PER_USER (100 req/min)
│   ├── Band: 100-per-minute
│   └── Key: user-123
└── Rule 3: GLOBAL (1000 req/hour)
    ├── Band: 1000-per-hour
    └── Key: global

처리 순서:
1. Rule 1 체크 → fluxgate:api-limits:rule-1:192.168.1.100:10-per-second
2. Rule 2 체크 → fluxgate:api-limits:rule-2:user-123:100-per-minute
3. Rule 3 체크 → fluxgate:api-limits:rule-3:global:1000-per-hour

결과: 하나라도 거부되면 전체 거부 (early return)
```

---

## 3. Redis Connection Layer

### RedisConnectionProvider (인터페이스)

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── RedisConnectionProvider.java
```

Standalone과 Cluster 모드를 통합하는 추상화 레이어입니다.

```java
// RedisConnectionProvider.java - 실제 코드
public interface RedisConnectionProvider extends AutoCloseable {

    /** 연결 모드 반환 */
    RedisMode getMode();

    /** 연결 상태 확인 */
    boolean isConnected();

    /** Lua 스크립트 로드 (Cluster: 모든 마스터 노드에 자동 배포) */
    String scriptLoad(String script);

    /** EVALSHA로 스크립트 실행 (효율적, 캐시된 스크립트 사용) */
    <T> T evalsha(String sha, String[] keys, String[] args);

    /** EVAL로 스크립트 실행 (NOSCRIPT 폴백용) */
    <T> T eval(String script, String[] keys, String[] args);

    // Hash 명령어
    boolean hset(String key, String field, String value);
    long hset(String key, Map<String, String> map);
    Map<String, String> hgetall(String key);

    // 기타 명령어
    long del(String... keys);
    boolean exists(String key);
    long ttl(String key);
    List<String> keys(String pattern);
    String ping();

    // Cluster 전용
    List<String> clusterNodes();

    @Override
    void close();

    enum RedisMode {
        STANDALONE,
        CLUSTER
    }
}
```

### StandaloneRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── StandaloneRedisConnection.java
```

단일 Redis 노드 연결을 처리합니다.

```java
// StandaloneRedisConnection.java - 실제 코드
public class StandaloneRedisConnection implements RedisConnectionProvider {

    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;

    public StandaloneRedisConnection(String redisUri, Duration timeout) {
        log.info("Creating standalone Redis connection to: {}", maskPassword(redisUri));

        RedisURI uri = RedisURI.create(redisUri);
        this.redisClient = RedisClient.create(uri);
        this.redisClient.setDefaultTimeout(timeout);

        try {
            this.connection = redisClient.connect();
            this.commands = connection.sync();
            log.info("Standalone Redis connection established successfully");
        } catch (Exception e) {
            redisClient.close();
            throw new RedisConnectionException("Failed to connect to Redis: " + maskPassword(redisUri), e);
        }
    }

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

    // ... 기타 명령어 구현
}
```

### ClusterRedisConnection

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── ClusterRedisConnection.java
```

Redis Cluster 연결을 처리합니다.

```java
// ClusterRedisConnection.java - 실제 코드
public class ClusterRedisConnection implements RedisConnectionProvider {

    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> connection;
    private final RedisAdvancedClusterCommands<String, String> commands;

    public ClusterRedisConnection(List<String> nodeUris, Duration timeout) {
        log.info("Creating Redis Cluster connection to {} nodes", nodeUris.size());

        List<RedisURI> redisUris = nodeUris.stream()
                .map(RedisURI::create)
                .collect(Collectors.toList());

        this.clusterClient = RedisClusterClient.create(redisUris);
        this.clusterClient.setDefaultTimeout(timeout);

        try {
            this.connection = clusterClient.connect();
            this.commands = connection.sync();

            String pong = commands.ping();
            int nodeCount = getClusterNodeCount();
            log.info("Redis Cluster connection established: {} nodes discovered, ping={}", nodeCount, pong);
        } catch (Exception e) {
            clusterClient.close();
            throw new RedisConnectionException("Failed to connect to Redis Cluster", e);
        }
    }

    @Override
    public RedisMode getMode() {
        return RedisMode.CLUSTER;
    }

    @Override
    public String scriptLoad(String script) {
        // Cluster 모드: Lettuce가 모든 마스터 노드에 자동 브로드캐스트
        String sha = commands.scriptLoad(script);
        log.debug("Lua script loaded to cluster, SHA: {}", sha);
        return sha;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T evalsha(String sha, String[] keys, String[] args) {
        // Lettuce가 키의 해시 슬롯에 따라 올바른 노드로 자동 라우팅
        return (T) commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    }

    // ... 기타 명령어 구현
}
```

### RedisConnectionFactory

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/connection/
└── RedisConnectionFactory.java
```

URI를 분석하여 적절한 연결 객체를 생성합니다.

```java
// RedisConnectionFactory.java - 실제 코드
public final class RedisConnectionFactory {

    /**
     * URI에서 모드를 자동 감지하여 연결 생성
     * - 쉼표가 있으면 Cluster 모드
     * - 없으면 Standalone 모드
     */
    public static RedisConnectionProvider create(String uri, Duration timeout) {
        Objects.requireNonNull(uri, "uri must not be null");

        if (uri.contains(",")) {
            List<String> nodes = parseClusterNodes(uri);
            log.info("Detected cluster mode with {} nodes", nodes.size());
            return new ClusterRedisConnection(nodes, timeout);
        }

        log.info("Using standalone mode");
        return new StandaloneRedisConnection(uri, timeout);
    }

    /**
     * 명시적 모드 선택으로 연결 생성
     */
    public static RedisConnectionProvider create(
            RedisConnectionProvider.RedisMode mode, List<String> uris, Duration timeout) {

        switch (mode) {
            case STANDALONE:
                if (uris.size() > 1) {
                    log.warn("Multiple URIs provided for standalone mode, using first: {}", uris.get(0));
                }
                return new StandaloneRedisConnection(uris.get(0), timeout);
            case CLUSTER:
                return new ClusterRedisConnection(uris, timeout);
            default:
                throw new IllegalArgumentException("Unknown Redis mode: " + mode);
        }
    }

    private static List<String> parseClusterNodes(String uri) {
        return Arrays.stream(uri.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
```

### Standalone vs Cluster 비교

| 항목 | Standalone | Cluster |
|-----|-----------|---------|
| 노드 수 | 1개 | 3개 이상 (권장 6개) |
| 데이터 분산 | 없음 | 해시 슬롯 기반 (16384개) |
| 고가용성 | 수동 Failover | 자동 Failover |
| 스크립트 로드 | 단일 노드 | 모든 마스터 노드 |
| EVALSHA 라우팅 | 해당 없음 | 키 해시 슬롯 기반 자동 라우팅 |
| URI 형식 | `redis://host:6379` | `redis://node1:6379,redis://node2:6379,...` |

---

## 4. RedisRateLimiterConfig

```
fluxgate-redis-ratelimiter/src/main/java/org/fluxgate/redis/config/
└── RedisRateLimiterConfig.java
```

Redis RateLimiter 초기화를 담당하는 설정 클래스입니다.

```java
// RedisRateLimiterConfig.java - 실제 코드
public final class RedisRateLimiterConfig implements AutoCloseable {

    private final RedisConnectionProvider connectionProvider;
    private final RedisTokenBucketStore tokenBucketStore;
    private final RedisRuleSetStore ruleSetStore;

    public RedisRateLimiterConfig(String redisUri) throws IOException {
        this(redisUri, Duration.ofSeconds(5));
    }

    public RedisRateLimiterConfig(String redisUri, Duration timeout) throws IOException {
        log.info("Initializing Redis RateLimiter with URI: {}", maskPassword(redisUri));

        // (1) 연결 생성 (모드 자동 감지)
        this.connectionProvider = RedisConnectionFactory.create(redisUri, timeout);

        // (2) Lua 스크립트 로드
        LuaScriptLoader.loadScripts(connectionProvider);

        // (3) TokenBucketStore 생성
        this.tokenBucketStore = new RedisTokenBucketStore(connectionProvider);

        // (4) RuleSetStore 생성
        this.ruleSetStore = new RedisRuleSetStore(connectionProvider);

        logInitializationSuccess();
    }

    private void logInitializationSuccess() {
        log.info("Redis RateLimiter initialized successfully");
        log.info("  Mode: {}", connectionProvider.getMode());
        log.info("Production features enabled:");
        log.info("  - Uses Redis TIME (no clock drift)");
        log.info("  - Integer arithmetic only (no precision loss)");
        log.info("  - Read-only on rejection (fair rate limiting)");
        log.info("  - TTL safety margin + max cap");
    }

    public RedisTokenBucketStore getTokenBucketStore() {
        return tokenBucketStore;
    }

    public RedisRuleSetStore getRuleSetStore() {
        return ruleSetStore;
    }

    @Override
    public void close() {
        if (tokenBucketStore != null) tokenBucketStore.close();
        if (connectionProvider != null) connectionProvider.close();
    }
}
```

### 초기화 흐름

```
RedisRateLimiterConfig 생성
         |
         v
+----------------------------+
| (1) RedisConnectionFactory |
|     - URI 파싱              |
|     - 모드 감지 (쉼표 유무)   |
|     - 연결 생성              |
+----------------------------+
         |
         v
+----------------------------+
| (2) LuaScriptLoader        |
|     - classpath에서 스크립트 로드 |
|     - Redis에 SCRIPT LOAD   |
|     - SHA 저장              |
+----------------------------+
         |
         v
+----------------------------+
| (3) RedisTokenBucketStore  |
|     - 스크립트 로드 확인     |
|     - tryConsume() 준비     |
+----------------------------+
         |
         v
+----------------------------+
| (4) RedisRuleSetStore      |
|     - RuleSet 저장/조회     |
+----------------------------+
```

---

## 5. 사용 예제

### Standalone 모드

```java
// 단일 Redis 서버 연결
RedisRateLimiterConfig config = new RedisRateLimiterConfig("redis://localhost:6379");

// RateLimiter 생성
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());

// Rate Limiting 수행
RateLimitResult result = rateLimiter.tryConsume(context, ruleSet, 1);

if (result.isAllowed()) {
    // 요청 허용
} else {
    // 요청 거부, result.getNanosToWaitForRefill() 후 재시도
}

// 종료 시 리소스 정리
config.close();
```

### Cluster 모드

```java
// 쉼표로 구분된 노드 URI (자동 Cluster 모드 감지)
String clusterUri = "redis://node1:6379,redis://node2:6379,redis://node3:6379";
RedisRateLimiterConfig config = new RedisRateLimiterConfig(clusterUri);

// 또는 명시적 Cluster 모드
RedisRateLimiterConfig config = new RedisRateLimiterConfig(
    RedisMode.CLUSTER,
    List.of("redis://node1:6379", "redis://node2:6379", "redis://node3:6379"),
    Duration.ofSeconds(5)
);

// 이후 사용법은 동일
RedisRateLimiter rateLimiter = new RedisRateLimiter(config.getTokenBucketStore());
```

### Spring Boot 통합

```yaml
# application.yml
fluxgate:
  redis:
    enabled: true
    uri: redis://localhost:6379  # Standalone
    # uri: redis://node1:6379,redis://node2:6379,redis://node3:6379  # Cluster
    timeout: 5s
```

```java
// Spring Boot AutoConfiguration이 자동으로 빈 생성
@Autowired
private RateLimiter rateLimiter;  // RedisRateLimiter 주입됨

@Autowired
private RedisTokenBucketStore tokenBucketStore;  // 직접 접근도 가능
```

---

## 관련 문서

- [Storage Layer Deep Dive](storage-layer.ko.md) - RedisTokenBucketStore, Lua 스크립트 상세
- [RateLimiter Layer Deep Dive](ratelimiter-layer.ko.md) - RateLimiter 인터페이스
- [아키텍처 개요](../README.ko.md)
