package org.openfluxgate.core.bucket;

import org.junit.jupiter.api.Test;
import org.openfluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.openfluxgate.core.config.LimitScope;
import org.openfluxgate.core.config.OnLimitExceedPolicy;
import org.openfluxgate.core.config.RateLimitBand;
import org.openfluxgate.core.config.RateLimitRule;
import org.openfluxgate.core.context.RequestContext;
import org.openfluxgate.core.key.KeyResolver;
import org.openfluxgate.core.key.RateLimitKey;
import org.openfluxgate.core.metrics.RateLimitMetricsRecorder;
import org.openfluxgate.core.ratelimiter.RateLimitResult;
import org.openfluxgate.core.ratelimiter.RateLimitRuleSet;
import org.openfluxgate.core.ratelimiter.RateLimiter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Bucket4jRateLimiterTest {

    private final RateLimiter rateLimiter = new Bucket4jRateLimiter();

    // --- Helper: Create common RuleSet configuration ----------------------

    private RateLimitRuleSet createSimpleRuleSet(KeyResolver keyResolver,
                                                 RateLimitMetricsRecorder metricsRecorder) {

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5)
                .label("1m-5req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("TEST_RULE")
                .scope(LimitScope.GLOBAL)
                .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
                .addBand(band).build();

        return RateLimitRuleSet.builder("auth-api-default")
                .description("1m 5req global limit for testing")
                .rules(List.of(rule))
                .keyResolver(keyResolver)
                .metricsRecorder(metricsRecorder)
                .build();
    }

    private RequestContext createRequestContext(String clientIp, String userId) {
        return RequestContext.builder()
                .clientIp(clientIp)
                .userId(userId)
                .endpoint("/api/test")
                .method("GET")
                .build();
    }

    private RateLimitKey ipKey(String ip) {
        return RateLimitKey.of(ip);
    }

    // --- 1) Basic behavior test -------------------------------------------

    @Test
    void should_allow_within_capacity_and_reject_after() {
        // given
        KeyResolver keyResolver = ctx -> ipKey(ctx.getClientIp());
        RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, null);

        RequestContext ctx = createRequestContext("127.0.0.1", "user-1");

        // when & then
        for (int i = 1; i <= 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);
            assertThat(result.isAllowed())
                    .as("request %s should be allowed", i)
                    .isTrue();
        }

        RateLimitResult sixth = rateLimiter.tryConsume(ctx, ruleSet, 1);

        // then
        assertThat(sixth.isAllowed()).isFalse();
        assertThat(sixth.getNanosToWaitForRefill())
                .as("should indicate wait time until refill")
                .isGreaterThan(0L);
    }

    // --- 2) Different keys should have independent buckets ----------------

    @Test
    void differentKeysShouldHaveIndependentBuckets() {
        // given
        KeyResolver keyResolver = ctx -> ipKey(ctx.getClientIp());
        RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, null);

        RequestContext ip1 = createRequestContext("10.0.0.1", "user-1");
        RequestContext ip2 = createRequestContext("10.0.0.2", "user-2");

        // ip1: consume 5 times
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume(ip1, ruleSet, 1);
            assertThat(result.isAllowed()).isTrue();
        }
        // ip1: 6th request should be rejected
        RateLimitResult ip1Sixth = rateLimiter.tryConsume(ip1, ruleSet, 1);
        assertThat(ip1Sixth.isAllowed()).isFalse();

        // ip2: should have new bucket, so allowed up to 5 times again
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume(ip2, ruleSet, 1);
            assertThat(result.isAllowed())
                    .as("ip2 should have its own quota")
                    .isTrue();
        }
    }

    // --- 3) Metrics hook invocation verification --------------------------

    @Test
    void metricsRecorderShouldBeCalled() {
        // given
        AtomicReference<RateLimitResult> lastRecorded = new AtomicReference<>();

        RateLimitMetricsRecorder recorder = (ctx, result) -> {
            lastRecorded.set(result);
        };

        KeyResolver keyResolver = ctx -> ipKey(ctx.getClientIp());
        RateLimitRuleSet ruleSet = createSimpleRuleSet(keyResolver, recorder);
        RequestContext ctx = createRequestContext("192.168.0.10", "user-123");

        // when
        RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet, 1);

        // then
        assertThat(result.isAllowed()).isTrue();
        assertThat(lastRecorded.get())
                .as("metrics recorder should receive the same result")
                .isNotNull()
                .isSameAs(result);
    }
}
