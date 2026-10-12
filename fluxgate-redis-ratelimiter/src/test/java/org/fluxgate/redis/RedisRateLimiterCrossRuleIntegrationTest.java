package org.fluxgate.redis;

import org.fluxgate.redis.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeAll;

/**
 * {@link CrossRuleAtomicityContract} against a standalone Redis.
 *
 * <p>The target comes from {@link RedisContainerSupport}: a supplied {@code FLUXGATE_REDIS_URI}, a
 * Testcontainers {@code redis:7-alpine}, or the test is skipped. On a standalone Redis {@link
 * CrossRuleAtomicityContract.Strategy#AUTO} always evaluates all rules in one script call.
 */
class RedisRateLimiterCrossRuleIntegrationTest extends CrossRuleAtomicityContract {

  @BeforeAll
  static void setUp() {
    connect(RedisContainerSupport.redisUri());
  }
}
