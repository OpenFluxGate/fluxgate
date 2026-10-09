package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.cluster.SlotHash;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.ScriptExecutionException;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.Test;

class PolicyCounterLifecycleIntegrationTest {
  private static final String RUN = RedisContainerSupport.newRunId();
  private final RequestContext context = RequestContext.builder().endpoint("/api").build();

  @Test
  void decreaseIncreaseAndRollbackPreserveUsageDebt() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
      String id = RUN + "-debt";
      assertThat(limiter.tryConsume(context, policy(id, 100, 1, "legacy"), 80).getRemainingTokens())
          .isEqualTo(20);
      assertThat(limiter.tryConsume(context, policy(id, 50, 2, "legacy"), 1).isAllowed()).isFalse();
      assertThat(limiter.tryConsume(context, policy(id, 100, 3, "legacy"), 1).getRemainingTokens())
          .isEqualTo(19);
      assertThat(limiter.tryConsume(context, policy(id, 100, 4, "legacy"), 1).getRemainingTokens())
          .isEqualTo(18);
      cleanup(config);
    }
  }

  @Test
  void staleRevisionCannotResurrectQuotaAndExplicitEpochCanReset() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
      String id = RUN + "-stale";
      limiter.tryConsume(context, policy(id, 3, 2, "legacy"), 2);
      assertThatThrownBy(() -> limiter.tryConsume(context, policy(id, 100, 1, "legacy"), 1))
          .isInstanceOf(ScriptExecutionException.class)
          .hasMessageContaining("STALE_POLICY")
          .satisfies(
              error -> assertThat(((ScriptExecutionException) error).isRetryable()).isFalse());
      assertThat(limiter.tryConsume(context, policy(id, 3, 2, "legacy"), 1).getRemainingTokens())
          .isZero();
      assertThat(
              limiter.tryConsume(context, policy(id, 3, 3, "reset-epoch"), 1).getRemainingTokens())
          .isEqualTo(2);
      cleanup(config);
    }
  }

  @Test
  void versionlessJavaConsumerCannotBypassFence() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      String key = "fluxgate:bucket:{" + RUN + "-versionless}:stable";
      RateLimitBand band = RateLimitBand.builder(Duration.ofHours(1), 3).label("stable").build();
      RedisTokenBucketStore store = config.getTokenBucketStore();
      store.tryConsume(List.of(key), List.of(band), 2, 2);
      Map<String, String> before = config.getConnectionProvider().hgetall(key);
      assertThatThrownBy(() -> store.tryConsume(key, band, 1)).hasMessageContaining("STALE_POLICY");
      assertThat(config.getConnectionProvider().hgetall(key)).isEqualTo(before);
      cleanup(config);
    }
  }

  @Test
  void fixedAndSlidingWindowsRetainCountsAboveDecreasedCapacity() {
    for (RateLimitAlgorithm algorithm :
        List.of(RateLimitAlgorithm.FIXED_WINDOW, RateLimitAlgorithm.SLIDING_WINDOW)) {
      try (RedisRateLimiterConfig config =
          new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
        String key = "fluxgate:bucket:{" + RUN + "-" + algorithm + "}:stable";
        RedisTokenBucketStore store = config.getTokenBucketStore();
        store.tryConsume(List.of(key), List.of(band(100, algorithm)), 80, 1);
        var denied = store.tryConsume(List.of(key), List.of(band(50, algorithm)), 1, 2);
        assertThat(denied.consumed()).isFalse();
        assertThat(denied.remainingTokens()).isZero();
        assertThat(
                store
                    .tryConsume(List.of(key), List.of(band(100, algorithm)), 1, 3)
                    .remainingTokens())
            .isEqualTo(19);
        cleanup(config);
      }
    }
  }

  @Test
  void aStaleBandCannotWriteAnyOfTheOtherBandsOrTheirMetadata() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      String first = "fluxgate:bucket:{" + RUN + "-atomic}:first";
      String second = "fluxgate:bucket:{" + RUN + "-atomic}:second";
      List<String> keys = List.of(first, second);
      List<RateLimitBand> bands =
          List.of(
              band(10, RateLimitAlgorithm.TOKEN_BUCKET), band(10, RateLimitAlgorithm.TOKEN_BUCKET));
      RedisTokenBucketStore store = config.getTokenBucketStore();
      store.tryConsume(keys, bands, 1, 2);
      var provider = config.getConnectionProvider();
      List<String> stateKeys =
          List.of(
              first,
              second,
              RedisTokenBucketStore.metadataKey(first),
              RedisTokenBucketStore.metadataKey(second));
      List<Map<String, String>> before =
          stateKeys.stream().map(provider::hgetall).collect(java.util.stream.Collectors.toList());
      assertThatThrownBy(() -> store.tryConsume(keys, bands, 1, 1))
          .hasMessageContaining("STALE_POLICY");
      assertThat(
              stateKeys.stream()
                  .map(provider::hgetall)
                  .collect(java.util.stream.Collectors.toList()))
          .isEqualTo(before);
      cleanup(config);
    }
  }

  @Test
  void revisionRaceHasMonotonicMetadataAndNoStaleConsumption() throws Exception {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      String key = "fluxgate:bucket:{" + RUN + "-race}:stable";
      RedisTokenBucketStore store = config.getTokenBucketStore();
      RateLimitBand band = band(10, RateLimitAlgorithm.TOKEN_BUCKET);
      store.tryConsume(List.of(key), List.of(band), 1, 1);
      CyclicBarrier barrier = new CyclicBarrier(2);
      var executor = Executors.newFixedThreadPool(2);
      try {
        var older =
            executor.submit(
                () -> {
                  barrier.await();
                  try {
                    store.tryConsume(List.of(key), List.of(band), 1, 2);
                    return true;
                  } catch (ScriptExecutionException expected) {
                    assertThat(expected).hasMessageContaining("STALE_POLICY");
                    return false;
                  }
                });
        var newer =
            executor.submit(
                () -> {
                  barrier.await();
                  return store.tryConsume(List.of(key), List.of(band), 1, 3);
                });
        boolean oldConsumed = older.get(10, TimeUnit.SECONDS);
        newer.get(10, TimeUnit.SECONDS);
        assertThat(
                config
                    .getConnectionProvider()
                    .hgetall(RedisTokenBucketStore.metadataKey(key))
                    .get("revision"))
            .isEqualTo("3");
        assertThat(config.getConnectionProvider().hgetall(key).get("tokens"))
            .isEqualTo(oldConsumed ? "7" : "8");
      } finally {
        executor.shutdownNow();
      }
      cleanup(config);
    }
  }

  @Test
  void debtAndMetadataHaveCoordinatedBoundedTtls() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      String key = "fluxgate:bucket:{" + RUN + "-ttl}:stable";
      RedisTokenBucketStore store = config.getTokenBucketStore();
      store.tryConsume(List.of(key), List.of(band(100, RateLimitAlgorithm.TOKEN_BUCKET)), 80, 1);
      store.tryConsume(List.of(key), List.of(band(10, RateLimitAlgorithm.TOKEN_BUCKET)), 1, 2);
      var provider = config.getConnectionProvider();
      assertThat(provider.hgetall(key).get("tokens")).isEqualTo("-70");
      assertThat(provider.ttl(key))
          .isGreaterThan(7 * 3600)
          .isLessThanOrEqualTo(store.getMaxBucketTtl().getSeconds());
      assertThat(provider.ttl(RedisTokenBucketStore.metadataKey(key)))
          .isGreaterThanOrEqualTo(provider.ttl(key));
      assertThat(provider.ttl(RedisTokenBucketStore.revisionKey(key)))
          .isGreaterThanOrEqualTo(provider.ttl(RedisTokenBucketStore.metadataKey(key)));
      cleanup(config);
    }
  }

  @Test
  void rawKeysIncludingEmptyAndMalformedHashTagsKeepMetadataInTheirSlot() {
    for (String key :
        List.of("raw-key", "{}empty", "prefix{missing", "prefix{}then{valid}", "{ok}:raw")) {
      assertThat(SlotHash.getSlot(RedisTokenBucketStore.metadataKey(key)))
          .as("metadata slot for %s", key)
          .isEqualTo(SlotHash.getSlot(key));
      assertThat(SlotHash.getSlot(RedisTokenBucketStore.revisionKey(key)))
          .as("fence slot for %s", key)
          .isEqualTo(SlotHash.getSlot(key));
    }
  }

  @Test
  void legacyBandLabelsCannotChangeTheRuleFenceNamespace() {
    assertThat(RedisTokenBucketStore.revisionKey("fluxgate:bucket:{set:rule:key}:a:epoch:label"))
        .isEqualTo(RedisTokenBucketStore.revisionKey("fluxgate:bucket:{set:rule:key}:b"));
  }

  @Test
  void explicitEpochAndEveryDeclaredMetadataKeyShareTheClusterSlot() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      String id = RUN + "-slot";
      RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
      limiter.tryConsume(context, policy(id, 3, 1, "reset:{untrusted}*"), 1);
      List<String> keys =
          config.getConnectionProvider().scanKeys("fluxgate:bucket:*" + id + "*", 100);
      keys = new java.util.ArrayList<>(keys);
      keys.addAll(config.getConnectionProvider().scanKeys("fluxgate:policy:*" + id + "*", 100));
      assertThat(keys).hasSize(3);
      int slot = SlotHash.getSlot(keys.get(0));
      assertThat(keys).allSatisfy(key -> assertThat(SlotHash.getSlot(key)).isEqualTo(slot));
      assertThat(keys)
          .allSatisfy(key -> assertThat(key).doesNotContain("untrusted").doesNotContain("*"));
      cleanup(config);
    }
  }

  private RateLimitBand band(long capacity, RateLimitAlgorithm algorithm) {
    return RateLimitBand.builder(Duration.ofHours(1), capacity)
        .label("stable")
        .algorithm(algorithm)
        .build();
  }

  private void cleanup(RedisRateLimiterConfig config) {
    RedisContainerSupport.deleteKeys(
        config.getConnectionProvider(), "fluxgate:bucket:*" + RUN + "*");
    RedisContainerSupport.deleteKeys(
        config.getConnectionProvider(), "fluxgate:policy:*" + RUN + "*");
  }

  private RateLimitRuleSet policy(String id, long capacity, long revision, String epoch) {
    RateLimitRule rule =
        RateLimitRule.builder("quota")
            .scope(LimitScope.GLOBAL)
            .keyStrategyId("global")
            .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
            .ruleSetId(id)
            .attribute("fluxgate.counterRevision", revision)
            .attribute("fluxgate.counterEpoch", epoch)
            .addBand(
                RateLimitBand.builder(Duration.ofHours(1), capacity).label("stable-band").build())
            .build();
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(java.util.Collections.singletonList(rule))
        .build();
  }
}
