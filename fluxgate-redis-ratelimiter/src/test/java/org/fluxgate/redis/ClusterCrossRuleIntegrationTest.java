package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.cluster.SlotHash;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitAlgorithm;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link CrossRuleAtomicityContract} against a real Redis Cluster, plus the cluster-only cases:
 * rules whose keys live in different hash slots (compensation), rules whose keys happen to share a
 * slot (one script call), and the FIXED_WINDOW counter hash.
 *
 * <p>Requires a cluster on localhost:7100-7105 and the {@code redis-cluster-it} profile:
 *
 * <pre>
 * docker compose -p fluxgate-atomicity -f docker/redis-cluster.yml up -d
 * ./mvnw -pl fluxgate-redis-ratelimiter -Predis-cluster-it verify
 * </pre>
 */
@EnabledIfSystemProperty(named = "fluxgate.redis.cluster.tests", matches = "true")
class ClusterCrossRuleIntegrationTest extends CrossRuleAtomicityContract {

  private static final String CLUSTER_URI =
      "redis://127.0.0.1:7100,redis://127.0.0.1:7101,redis://127.0.0.1:7102";

  @BeforeAll
  static void setUp() {
    try {
      connect(CLUSTER_URI);
    } catch (RuntimeException e) {
      Assumptions.abort("No Redis Cluster reachable at " + CLUSTER_URI + ": " + e.getMessage());
    }
    assertThat(redis().getMode()).isEqualTo(RedisMode.CLUSTER);
  }

  @Test
  @DisplayName("Rules in different slots: compensation keeps the earlier rule whole")
  void rulesInDifferentSlotsAreCompensated() {
    String ruleSetId = "xslot-" + RUN_ID;
    RateLimitRule first = rule("a-first", ruleSetId, LimitScope.PER_IP, bands("MULTI", 3));
    RateLimitRule second = rule("b-second", ruleSetId, LimitScope.GLOBAL, bands("MULTI", 1));

    String firstKey = bucketKey(ruleSetId, "a-first", "ip:10.1.2.3", "tb");
    String secondKey = bucketKey(ruleSetId, "b-second", "global", "tb");
    assertThat(SlotHash.getSlot(firstKey)).isNotEqualTo(SlotHash.getSlot(secondKey));
    RedisTokenBucketStore store = new RedisTokenBucketStore(redis());
    assertThat(store.canEvaluateAtomically(List.of(firstKey, secondKey))).isFalse();

    assertRejectionsCostNothing(new RedisRateLimiter(store), ruleSetId, first, second);
  }

  @Test
  @DisplayName("Rules that share a slot: evaluated in one script call, all-or-nothing")
  void rulesInOneSlotAreEvaluatedTogether() {
    String ruleSetId = "sameslot-" + RUN_ID;
    int target = SlotHash.getSlot(bucketKey(ruleSetId, "a-first", "ip:10.1.2.3", "tb"));
    // find a second rule id whose hash tag lands in the same slot
    String secondId = null;
    for (int i = 0; secondId == null; i++) {
      String candidate = "b-second-" + i;
      if (SlotHash.getSlot(bucketKey(ruleSetId, candidate, "global", "tb")) == target) {
        secondId = candidate;
      }
    }
    RateLimitRule first = rule("a-first", ruleSetId, LimitScope.PER_IP, bands("MULTI", 3));
    RateLimitRule second = rule(secondId, ruleSetId, LimitScope.GLOBAL, bands("MULTI", 1));

    RedisTokenBucketStore store = new RedisTokenBucketStore(redis());
    assertThat(
            store.canEvaluateAtomically(
                List.of(
                    bucketKey(ruleSetId, "a-first", "ip:10.1.2.3", "tb"),
                    bucketKey(ruleSetId, secondId, "global", "fw:fw"))))
        .isTrue();

    assertRejectionsCostNothing(new RedisRateLimiter(store), ruleSetId, first, second);
  }

  @Test
  @DisplayName("FIXED_WINDOW: the counter is a {count, window end} hash in the rule's slot")
  void fixedWindowCounterIsAHashInTheRulesSlot() {
    String ruleSetId = "fw-layout-" + RUN_ID;
    RateLimitRule rule =
        rule(
            "fw-rule",
            ruleSetId,
            LimitScope.PER_IP,
            List.of(
                RateLimitBand.builder(Duration.ofMinutes(1), 3).label("tb").build(),
                RateLimitBand.builder(Duration.ofMinutes(1), 2)
                    .label("fw")
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .build()));
    RateLimitRuleSet ruleSet = ruleSet(ruleSetId, rule);
    RedisRateLimiter limiter = new RedisRateLimiter(new RedisTokenBucketStore(redis()));

    assertThat(limiter.tryConsume(context("10.9.9.9"), ruleSet, 1).isAllowed()).isTrue();

    String fwKey = bucketKey(ruleSetId, "fw-rule", "ip:10.9.9.9", "fw:fw");
    String tbKey = bucketKey(ruleSetId, "fw-rule", "ip:10.9.9.9", "tb");
    assertThat(SlotHash.getSlot(fwKey)).isEqualTo(SlotHash.getSlot(tbKey));
    Map<String, String> counter = redis().hgetall(fwKey);
    assertThat(counter.get("count")).isEqualTo("1");
    long windowEnd = Long.parseLong(counter.get("window_end_micros"));
    assertThat(windowEnd % (Duration.ofMinutes(1).toNanos() / 1000L)).isZero();
  }

  @Test
  @DisplayName("FIXED_WINDOW: a previous window's counter still visible is not carried over")
  void fixedWindowBoundaryInCluster() {
    String ruleSetId = "fw-boundary-" + RUN_ID;
    RateLimitRule rule =
        rule(
            "fw-rule",
            ruleSetId,
            LimitScope.PER_IP,
            List.of(
                RateLimitBand.builder(Duration.ofMinutes(1), 2)
                    .label("fw")
                    .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                    .build()));
    RateLimitRuleSet ruleSet = ruleSet(ruleSetId, rule);
    RedisRateLimiter limiter = new RedisRateLimiter(new RedisTokenBucketStore(redis()));
    String fwKey = bucketKey(ruleSetId, "fw-rule", "ip:10.8.8.8", "fw:fw");

    // A full counter of the window that just ended, kept visible past its end on purpose. The
    // window end comes from the TIME of the node that owns the key, as the consume script's does.
    List<Long> written =
        redis()
            .eval(
                "local t = redis.call('TIME')\n"
                    + "local now = tonumber(t[1]) * 1000000 + tonumber(t[2])\n"
                    + "local prev = math.floor(now / 60000000) * 60000000\n"
                    + "redis.call('HSET', KEYS[1], 'count', '2',"
                    + " 'window_end_micros', string.format('%.0f', prev))\n"
                    + "redis.call('PEXPIRE', KEYS[1], 60000)\n"
                    + "return {1}",
                new String[] {fwKey}, new String[0]);
    assertThat(written).containsExactly(1L);

    RateLimitResult result = limiter.tryConsume(context("10.8.8.8"), ruleSet, 1);

    assertThat(result.isAllowed()).isTrue();
    assertThat(redis().hgetall(fwKey).get("count")).isEqualTo("1");
  }

  private static String bucketKey(String ruleSetId, String ruleId, String keyValue, String label) {
    return RedisRateLimiter.BUCKET_KEY_PREFIX
        + "{"
        + ruleSetId
        + ":"
        + ruleId
        + ":"
        + keyValue
        + "}:"
        + label;
  }
}
