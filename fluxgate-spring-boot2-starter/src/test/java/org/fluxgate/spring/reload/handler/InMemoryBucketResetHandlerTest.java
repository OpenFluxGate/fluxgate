package org.fluxgate.spring.reload.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.fluxgate.core.reload.ReloadSource;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link InMemoryBucketResetHandler}. */
class InMemoryBucketResetHandlerTest {

  private Bucket4jRateLimiter rateLimiter;
  private InMemoryBucketResetHandler handler;

  @BeforeEach
  void setUp() {
    rateLimiter = new Bucket4jRateLimiter(100L, Duration.ofMinutes(5));
    handler = new InMemoryBucketResetHandler(rateLimiter);
  }

  private static RateLimitRuleSet ruleSet(String id) {
    return RateLimitRuleSet.builder(id)
        .rules(
            List.of(
                RateLimitRule.builder(id + "-rule")
                    .name(id)
                    .enabled(true)
                    .ruleSetId(id)
                    .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 1).build())
                    .build()))
        .keyResolver((ctx, rule) -> RateLimitKey.of("ip:10.0.0.1"))
        .build();
  }

  @Test
  void shouldDropTheBucketsOfOneRuleSetOnAReloadEvent() {
    RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
    assertThat(rateLimiter.tryConsume(context, ruleSet("orders"), 1L).isAllowed()).isTrue();
    assertThat(rateLimiter.tryConsume(context, ruleSet("orders"), 1L).isAllowed()).isFalse();

    handler.onReload(RuleReloadEvent.forRuleSet("orders", ReloadSource.PUBSUB));

    assertThat(rateLimiter.tryConsume(context, ruleSet("orders"), 1L).isAllowed()).isTrue();
  }

  @Test
  void shouldDropEveryBucketOnAFullReload() {
    RequestContext context = RequestContext.builder().clientIp("10.0.0.1").build();
    rateLimiter.tryConsume(context, ruleSet("orders"), 1L);
    rateLimiter.tryConsume(context, ruleSet("payments"), 1L);
    assertThat(rateLimiter.size()).isPositive();

    handler.onReload(RuleReloadEvent.fullReload(ReloadSource.MANUAL));

    assertThat(rateLimiter.size()).isZero();
  }
}
