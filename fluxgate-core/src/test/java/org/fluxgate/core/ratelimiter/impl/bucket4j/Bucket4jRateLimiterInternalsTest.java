package org.fluxgate.core.ratelimiter.impl.bucket4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bucket4jRateLimiter internals")
class Bucket4jRateLimiterInternalsTest {

  private static final RequestContext CTX =
      RequestContext.builder().clientIp("10.0.0.1").endpoint("/api").method("GET").build();

  private static RateLimitRuleSet ruleSet(Duration window, long capacity) {
    RateLimitRule rule =
        RateLimitRule.builder("rule-1")
            .scope(LimitScope.GLOBAL)
            .addBand(RateLimitBand.builder(window, capacity).label("band").build())
            .build();
    return RateLimitRuleSet.builder("rs-1")
        .rules(List.of(rule))
        .keyResolver((ctx, r) -> RateLimitKey.of("k"))
        .build();
  }

  @Test
  @DisplayName("a bucket reset between lookup and lock is looked up again, not charged detached")
  void resetBetweenLookupAndLockChargesTheCurrentBucket() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet rs = ruleSet(Duration.ofHours(1), 1);
    AtomicBoolean fired = new AtomicBoolean();
    limiter.beforeLockHook =
        () -> {
          if (fired.compareAndSet(false, true)) {
            limiter.reset("rs-1");
          }
        };

    assertThat(limiter.tryConsume(CTX, rs, 1).isAllowed()).isTrue();
    assertThat(fired).isTrue();
    limiter.beforeLockHook = null;

    // the single permit went to the bucket every later request sees
    assertThat(limiter.tryConsume(CTX, rs, 1).isAllowed())
        .as("the first request charged the live bucket, not the one reset under it")
        .isFalse();
  }

  @Test
  @DisplayName("a window too long for nanoseconds still yields a reset time")
  void hugeWindowDoesNotOverflow() {
    Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    RateLimitRuleSet rs = ruleSet(Duration.ofMillis(Long.MAX_VALUE), 1);
    long before = System.currentTimeMillis();

    RateLimitResult allowed = limiter.tryConsume(CTX, rs, 1);
    RateLimitResult rejected = limiter.tryConsume(CTX, rs, 1);

    assertThat(allowed.isAllowed()).isTrue();
    assertThat(allowed.getResetTimeMillis()).isGreaterThan(before);
    assertThat(rejected.isAllowed()).isFalse();
    assertThat(rejected.getResetTimeMillis()).isGreaterThan(before);
  }
}
