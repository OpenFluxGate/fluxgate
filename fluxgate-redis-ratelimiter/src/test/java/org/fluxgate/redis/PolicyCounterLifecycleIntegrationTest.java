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
  void malformedFractionalOrOverflowingSlidingCountCannotPartiallyMigrateEarlierBand() {
    for (String invalid :
        List.of("malformed", "1.5", "9223372036854775807", "9223372036854775808")) {
      try (RedisRateLimiterConfig config =
          new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
        RedisTokenBucketStore store = config.getTokenBucketStore();
        var provider = config.getConnectionProvider();
        String first = "fluxgate:bucket:{" + RUN + "-invalid-sw-" + invalid + "}:first";
        String later = "fluxgate:bucket:{" + RUN + "-invalid-sw-" + invalid + "}:later";
        RateLimitBand sliding = band(Long.MAX_VALUE, RateLimitAlgorithm.SLIDING_WINDOW);
        List<String> keys = List.of(first, later);
        store.tryConsume(keys, List.of(band(100, RateLimitAlgorithm.TOKEN_BUCKET), sliding), 10, 1);
        String field = provider.hgetall(later).keySet().iterator().next();
        provider.hset(later, field, invalid);
        List<String> tracked =
            List.of(
                first,
                later,
                RedisTokenBucketStore.metadataKey(first),
                RedisTokenBucketStore.metadataKey(later));
        var before =
            tracked.stream().map(provider::hgetall).collect(java.util.stream.Collectors.toList());
        var ttls =
            tracked.stream().map(provider::ttl).collect(java.util.stream.Collectors.toList());
        String fence = RedisTokenBucketStore.revisionKey(first);
        long fenceTtl = provider.ttl(fence);
        assertThatThrownBy(
                () ->
                    store.tryConsume(
                        keys, List.of(band(50, RateLimitAlgorithm.TOKEN_BUCKET), sliding), 1, 2))
            .hasMessageContaining("INVALID_POLICY_STATE");
        assertThat(
                tracked.stream()
                    .map(provider::hgetall)
                    .collect(java.util.stream.Collectors.toList()))
            .isEqualTo(before);
        for (int index = 0; index < tracked.size(); index++) {
          assertThat(provider.ttl(tracked.get(index))).isLessThanOrEqualTo(ttls.get(index));
        }
        assertThat(
                provider.<List<Long>>eval(
                    "return {tonumber(redis.call('GET', KEYS[1]))}",
                    new String[] {fence},
                    new String[0]))
            .containsExactly(1L);
        assertThat(provider.ttl(fence)).isLessThanOrEqualTo(fenceTtl);
        cleanup(config);
      }
    }
  }

  @Test
  void capacityMigrationCannotExpireUsageDebtBeforeItRecovers() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisTokenBucketStore store =
          new RedisTokenBucketStore(config.getConnectionProvider(), Duration.ofSeconds(2));
      String key = "fluxgate:bucket:{" + RUN + "-debt-lifetime}:stable";
      RateLimitBand original =
          RateLimitBand.builder(Duration.ofSeconds(1), 1000).label("stable").build();
      RateLimitBand decreased =
          RateLimitBand.builder(Duration.ofSeconds(1), 1).label("stable").build();
      store.tryConsume(List.of(key), List.of(original), 1000, 1);
      var provider = config.getConnectionProvider();
      var before = provider.hgetall(key);
      var metadata = provider.hgetall(RedisTokenBucketStore.metadataKey(key));
      String fence = RedisTokenBucketStore.revisionKey(key);
      long ttl = provider.ttl(key);
      assertThatThrownBy(() -> store.tryConsume(List.of(key), List.of(decreased), 1, 2))
          .hasMessageContaining("POLICY_RESET_REQUIRED");
      assertThat(provider.hgetall(key)).isEqualTo(before);
      assertThat(provider.hgetall(RedisTokenBucketStore.metadataKey(key))).isEqualTo(metadata);
      assertThat(
              provider.<List<Long>>eval(
                  "return {tonumber(redis.call('GET', KEYS[1]))}",
                  new String[] {fence},
                  new String[0]))
          .containsExactly(1L);
      assertThat(provider.ttl(key)).isLessThanOrEqualTo(ttl);
      assertThat(
              store
                  .tryConsume(
                      List.of(key + ":epoch:explicit"), List.of(decreased), 1, 2, "explicit")
                  .consumed())
          .isTrue();
      assertThatThrownBy(() -> store.tryConsume(List.of(key), List.of(original), 1, 1))
          .hasMessageContaining("STALE_POLICY");
      cleanup(config);
    }
  }

  @Test
  void malformedLaterBucketCannotPartiallyMigrateAnyEarlierStateOrFence() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisTokenBucketStore store = config.getTokenBucketStore();
      var provider = config.getConnectionProvider();
      String first = "fluxgate:bucket:{" + RUN + "-bad-later}:first";
      String later = "fluxgate:bucket:{" + RUN + "-bad-later}:later";
      List<String> keys = List.of(first, later);
      List<RateLimitBand> oldBands =
          List.of(
              band(100, RateLimitAlgorithm.TOKEN_BUCKET),
              band(100, RateLimitAlgorithm.TOKEN_BUCKET));
      store.tryConsume(keys, oldBands, 80, 1);
      provider.hset(later, "tokens", "malformed");
      List<String> tracked =
          List.of(
              first,
              later,
              RedisTokenBucketStore.metadataKey(first),
              RedisTokenBucketStore.metadataKey(later));
      var before =
          tracked.stream().map(provider::hgetall).collect(java.util.stream.Collectors.toList());
      String fence = RedisTokenBucketStore.revisionKey(first);
      long beforeFenceTtl = provider.ttl(fence);
      List<RateLimitBand> changed =
          List.of(
              band(50, RateLimitAlgorithm.TOKEN_BUCKET), band(50, RateLimitAlgorithm.TOKEN_BUCKET));
      assertThatThrownBy(() -> store.tryConsume(keys, changed, 1, 2))
          .hasMessageContaining("INVALID_POLICY_STATE");
      assertThat(
              tracked.stream().map(provider::hgetall).collect(java.util.stream.Collectors.toList()))
          .isEqualTo(before);
      assertThat(
              provider.<List<Long>>eval(
                  "return {tonumber(redis.call('GET', KEYS[1]))}",
                  new String[] {fence},
                  new String[0]))
          .containsExactly(1L);
      assertThat(provider.ttl(fence)).isLessThanOrEqualTo(beforeFenceTtl);
      provider.eval(
          "redis.call('DEL', KEYS[1]); redis.call('SET', KEYS[1], 'wrong-type'); return 1",
          new String[] {later},
          new String[0]);
      assertThatThrownBy(() -> store.tryConsume(keys, changed, 1, 2))
          .hasMessageContaining("WRONGTYPE");
      assertThat(provider.hgetall(first)).isEqualTo(before.get(0));
      assertThat(provider.hgetall(RedisTokenBucketStore.metadataKey(first)))
          .isEqualTo(before.get(2));
      assertThat(
              provider.<List<Long>>eval(
                  "return {tonumber(redis.call('GET', KEYS[1]))}",
                  new String[] {fence},
                  new String[0]))
          .containsExactly(1L);
      assertThat(provider.ttl(fence)).isLessThanOrEqualTo(beforeFenceTtl);
      cleanup(config);
    }
  }

  @Test
  void laterRuleDenialLeavesEarlierRuleQuotaIntactWithPublishedRevisions() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
      String id = RUN + "-published-atomic";
      RateLimitRuleSet two = twoRules(id, 1, 10, 1);
      assertThat(limiter.tryConsume(context, two, 1).isAllowed()).isTrue();
      assertThat(limiter.tryConsume(context, two, 1).isAllowed()).isFalse();
      RateLimitRuleSet firstOnly =
          RateLimitRuleSet.builder(id)
              .keyResolver(new LimitScopeKeyResolver())
              .rules(List.of(two.getRules().get(0)))
              .build();
      assertThat(limiter.tryConsume(context, firstOnly, 1).getRemainingTokens()).isEqualTo(8);
      cleanup(config);
    }
  }

  @Test
  void staleLaterRuleRefundsEarlierPublishedRuleInCompensationPath() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisTokenBucketStore store = org.mockito.Mockito.spy(config.getTokenBucketStore());
      org.mockito.Mockito.doReturn(false)
          .when(store)
          .canEvaluateAtomically(org.mockito.ArgumentMatchers.anyCollection());
      RedisRateLimiter limiter = new RedisRateLimiter(store);
      String id = RUN + "-stale-compensation";
      RateLimitRuleSet newer = twoRules(id, 3, 10, 10);
      RateLimitRuleSet laterOnly =
          RateLimitRuleSet.builder(id)
              .keyResolver(new LimitScopeKeyResolver())
              .rules(List.of(newer.getRules().get(1)))
              .build();
      limiter.tryConsume(context, laterOnly, 1);
      RateLimitRuleSet older = twoRules(id, 2, 10, 10);
      assertThatThrownBy(() -> limiter.tryConsume(context, older, 1))
          .hasMessageContaining("STALE_POLICY");
      RateLimitRuleSet firstOnly =
          RateLimitRuleSet.builder(id)
              .keyResolver(new LimitScopeKeyResolver())
              .rules(List.of(older.getRules().get(0)))
              .build();
      assertThat(limiter.tryConsume(context, firstOnly, 1).getRemainingTokens()).isEqualTo(9);
      cleanup(config);
    }
  }

  @Test
  void staleLaterRuleCannotConsumeEarlierRuleInAtomicPath() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisRateLimiter limiter = new RedisRateLimiter(config.getTokenBucketStore());
      String id = RUN + "-stale-atomic-rules";
      RateLimitRuleSet newer = twoRules(id, 3, 10, 10);
      limiter.tryConsume(
          context,
          RateLimitRuleSet.builder(id)
              .keyResolver(new LimitScopeKeyResolver())
              .rules(List.of(newer.getRules().get(1)))
              .build(),
          1);
      RateLimitRuleSet older = twoRules(id, 2, 10, 10);
      assertThatThrownBy(() -> limiter.tryConsume(context, older, 1))
          .hasMessageContaining("STALE_POLICY");
      assertThat(
              limiter
                  .tryConsume(
                      context,
                      RateLimitRuleSet.builder(id)
                          .keyResolver(new LimitScopeKeyResolver())
                          .rules(List.of(older.getRules().get(0)))
                          .build(),
                      1)
                  .getRemainingTokens())
          .isEqualTo(9);
      cleanup(config);
    }
  }

  @Test
  void compensationUsesCurrentCapacityAfterPublicationChangesIt() {
    try (RedisRateLimiterConfig config =
        new RedisRateLimiterConfig(RedisContainerSupport.redisUri())) {
      RedisTokenBucketStore store = config.getTokenBucketStore();
      String key = "fluxgate:bucket:{" + RUN + "-refund-capacity}:stable";
      RateLimitBand original = band(100, RateLimitAlgorithm.TOKEN_BUCKET);
      var consumed = store.tryConsume(List.of(key), List.of(original), 80, 1);
      RateLimitBand decreased = band(50, RateLimitAlgorithm.TOKEN_BUCKET);
      assertThat(store.tryConsume(List.of(key), List.of(decreased), 1, 2).consumed()).isFalse();
      assertThat(store.refund(List.of(key), List.of(original), 80, consumed.redisTimeMicros()))
          .containsExactly(80L);
      assertThat(store.tryConsume(List.of(key), List.of(decreased), 50, 2).remainingTokens())
          .isZero();
      cleanup(config);
    }
  }

  private RateLimitRuleSet twoRules(String id, long revision, long first, long later) {
    List<RateLimitRule> rules = new java.util.ArrayList<>();
    for (int index = 0; index < 2; index++) {
      rules.add(
          RateLimitRule.builder("rule" + index)
              .scope(LimitScope.GLOBAL)
              .keyStrategyId("global")
              .ruleSetId(id)
              .priority(2 - index)
              .attribute("fluxgate.counterRevision", revision)
              .attribute("fluxgate.counterEpoch", "legacy")
              .addBand(band(index == 0 ? first : later, RateLimitAlgorithm.TOKEN_BUCKET))
              .build());
    }
    return RateLimitRuleSet.builder(id)
        .keyResolver(new LimitScopeKeyResolver())
        .rules(rules)
        .build();
  }

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
      assertThatThrownBy(() -> limiter.tryConsume(context, policy(id, 3, 2, "legacy"), 1))
          .hasMessageContaining("STALE_POLICY");
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
