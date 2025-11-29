package org.openfluxgate.core;

import org.junit.jupiter.api.Test;
import org.openfluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.openfluxgate.core.config.LimitScope;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenFluxGate Core Feature Demo Tests
 *
 * Demonstrates what features this module actually provides.
 */
class FeatureDemoTest {

    private final RateLimiter rateLimiter = new Bucket4jRateLimiter();

    @Test
    void feature1_BasicIPBasedRateLimiting() {
        System.out.println("\n=== Feature 1: IP-based Rate Limiting ===");

        // Configuration: Limit to 3 requests per minute per IP
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 3)
                .label("1min-3req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("ip-limit")
                .name("IP-based Limit")
                .scope(LimitScope.PER_IP)
                .addBand(band)
                .build();

        KeyResolver ipResolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("ip-limiter")
                .description("3 requests per minute per IP")
                .rules(List.of(rule))
                .keyResolver(ipResolver)
                .build();

        // Test
        RequestContext ctx = RequestContext.builder()
                .clientIp("192.168.1.100")
                .endpoint("/api/data")
                .build();

        System.out.println("IP: " + ctx.getClientIp());

        for (int i = 1; i <= 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet);
            System.out.printf("Request %d: %s, Remaining tokens: %d%n",
                i,
                result.isAllowed() ? "✓ Allowed" : "✗ Rejected",
                result.getRemainingTokens());
        }

        // Verify
        assertThat(rateLimiter.tryConsume(ctx, ruleSet).isAllowed()).isFalse();
        System.out.println("✓ IP-based Rate Limiting verified");
    }

    @Test
    void feature2_APIKeyBasedRateLimiting() {
        System.out.println("\n=== Feature 2: API Key-based Rate Limiting ===");

        // Configuration: 5 requests per minute per API Key
        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5)
                .label("1min-5req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("apikey-limit")
                .scope(LimitScope.PER_API_KEY)
                .keyStrategyId("apiKey")
                .addBand(band)
                .build();

        KeyResolver apiKeyResolver = ctx -> RateLimitKey.of("apikey:" + ctx.getApiKey());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("apikey-limiter")
                .rules(List.of(rule))
                .keyResolver(apiKeyResolver)
                .build();

        // Test with different API Keys
        RequestContext key1 = RequestContext.builder()
                .apiKey("key-abc-123")
                .endpoint("/api/premium")
                .build();

        RequestContext key2 = RequestContext.builder()
                .apiKey("key-xyz-789")
                .endpoint("/api/premium")
                .build();

        System.out.println("API Key 1: " + key1.getApiKey());
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(key1, ruleSet);
        }

        RateLimitResult key1Result = rateLimiter.tryConsume(key1, ruleSet);
        System.out.println("  6th request: " + (key1Result.isAllowed() ? "Allowed" : "Rejected"));

        System.out.println("API Key 2: " + key2.getApiKey());
        RateLimitResult key2Result = rateLimiter.tryConsume(key2, ruleSet);
        System.out.println("  1st request: " + (key2Result.isAllowed() ? "Allowed" : "Rejected"));

        // Verify: different keys are independent
        assertThat(key1Result.isAllowed()).isFalse();
        assertThat(key2Result.isAllowed()).isTrue();
        System.out.println("✓ Independent Rate Limiting per API Key verified");
    }

    @Test
    void feature3_MultiBandRateLimiting() {
        System.out.println("\n=== Feature 3: Multi-Band (Multiple Time Windows) Rate Limiting ===");

        // Configuration: 10 requests/minute AND 100 requests/hour
        RateLimitBand shortBand = RateLimitBand.builder(Duration.ofMinutes(1), 10)
                .label("1min-10req")
                .build();

        RateLimitBand longBand = RateLimitBand.builder(Duration.ofHours(1), 100)
                .label("1hour-100req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("multi-band")
                .addBand(shortBand)
                .addBand(longBand)
                .build();

        KeyResolver resolver = ctx -> RateLimitKey.of("user:" + ctx.getUserId());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("multi-band-limiter")
                .rules(List.of(rule))
                .keyResolver(resolver)
                .build();

        RequestContext ctx = RequestContext.builder()
                .userId("user-001")
                .build();

        System.out.println("User ID: " + ctx.getUserId());
        System.out.println("Band 1: 10 requests/minute");
        System.out.println("Band 2: 100 requests/hour");

        // Make 10 requests
        for (int i = 1; i <= 10; i++) {
            RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet);
            if (i == 1 || i == 10) {
                System.out.printf("Request %d: %s, Remaining: %d%n",
                    i, result.isAllowed() ? "Allowed" : "Rejected", result.getRemainingTokens());
            }
        }

        RateLimitResult eleventhResult = rateLimiter.tryConsume(ctx, ruleSet);
        System.out.printf("Request 11: %s%n", eleventhResult.isAllowed() ? "Allowed" : "Rejected");

        assertThat(eleventhResult.isAllowed()).isFalse();
        System.out.println("✓ Multi-Band Rate Limiting verified (short window limit)");
    }

    @Test
    void feature4_UserBasedRateLimiting() {
        System.out.println("\n=== Feature 4: User-based Rate Limiting ===");

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 3)
                .label("per-user-1min-3req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("user-limit")
                .scope(LimitScope.PER_USER)
                .addBand(band)
                .build();

        KeyResolver userResolver = ctx -> RateLimitKey.of("user:" + ctx.getUserId());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("user-limiter")
                .rules(List.of(rule))
                .keyResolver(userResolver)
                .build();

        // Different users from same IP
        RequestContext user1 = RequestContext.builder()
                .clientIp("10.0.0.1")
                .userId("alice")
                .build();

        RequestContext user2 = RequestContext.builder()
                .clientIp("10.0.0.1") // Same IP
                .userId("bob")
                .build();

        System.out.println("Testing different users from same IP");
        System.out.println("User: alice");
        for (int i = 0; i < 3; i++) {
            rateLimiter.tryConsume(user1, ruleSet);
        }
        RateLimitResult alice4th = rateLimiter.tryConsume(user1, ruleSet);
        System.out.println("  4th request: " + (alice4th.isAllowed() ? "Allowed" : "Rejected"));

        System.out.println("User: bob");
        RateLimitResult bob1st = rateLimiter.tryConsume(user2, ruleSet);
        System.out.println("  1st request: " + (bob1st.isAllowed() ? "Allowed" : "Rejected"));

        assertThat(alice4th.isAllowed()).isFalse();
        assertThat(bob1st.isAllowed()).isTrue();
        System.out.println("✓ Independent Rate Limiting per User verified");
    }

    @Test
    void feature5_GlobalRateLimiting() {
        System.out.println("\n=== Feature 5: Global Rate Limiting ===");

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5)
                .label("global-1min-5req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("global-limit")
                .scope(LimitScope.GLOBAL)
                .addBand(band)
                .build();

        KeyResolver globalResolver = ctx -> RateLimitKey.of("global");

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("global-limiter")
                .rules(List.of(rule))
                .keyResolver(globalResolver)
                .build();

        // Different IPs, different users
        RequestContext req1 = RequestContext.builder()
                .clientIp("1.1.1.1")
                .userId("user1")
                .build();

        RequestContext req2 = RequestContext.builder()
                .clientIp("2.2.2.2")
                .userId("user2")
                .build();

        System.out.println("All requests share one global bucket");

        for (int i = 0; i < 3; i++) {
            rateLimiter.tryConsume(req1, ruleSet);
        }
        System.out.println("3 requests from IP 1.1.1.1");

        for (int i = 0; i < 2; i++) {
            rateLimiter.tryConsume(req2, ruleSet);
        }
        System.out.println("2 requests from IP 2.2.2.2");
        System.out.println("Total 5 consumed");

        RateLimitResult nextResult = rateLimiter.tryConsume(req2, ruleSet);
        System.out.println("Next request: " + (nextResult.isAllowed() ? "Allowed" : "Rejected"));

        assertThat(nextResult.isAllowed()).isFalse();
        System.out.println("✓ Global Rate Limiting verified");
    }

    @Test
    void feature6_MetricsRecording() {
        System.out.println("\n=== Feature 6: Metrics Recording ===");

        // Metrics collector
        List<String> metricsLog = new ArrayList<>();
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        RateLimitMetricsRecorder recorder = (ctx, result) -> {
            String status = result.isAllowed() ? "ALLOWED" : "REJECTED";
            String log = String.format("[%s] IP=%s, Remaining=%d",
                status, ctx.getClientIp(), result.getRemainingTokens());
            metricsLog.add(log);

            if (result.isAllowed()) {
                allowedCount.incrementAndGet();
            } else {
                rejectedCount.incrementAndGet();
            }
        };

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 3)
                .label("3req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("metric-test")
                .addBand(band)
                .build();

        KeyResolver resolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("metric-limiter")
                .rules(List.of(rule))
                .keyResolver(resolver)
                .metricsRecorder(recorder)
                .build();

        RequestContext ctx = RequestContext.builder()
                .clientIp("203.0.113.1")
                .build();

        // Make 5 requests
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(ctx, ruleSet);
        }

        System.out.println("Collected metrics:");
        metricsLog.forEach(log -> System.out.println("  " + log));

        System.out.println("\nMetrics statistics:");
        System.out.println("  Allowed requests: " + allowedCount.get());
        System.out.println("  Rejected requests: " + rejectedCount.get());

        assertThat(allowedCount.get()).isEqualTo(3);
        assertThat(rejectedCount.get()).isEqualTo(2);
        assertThat(metricsLog).hasSize(5);
        System.out.println("✓ Metrics Recording verified");
    }

    @Test
    void feature7_CustomKeyResolverStrategy() {
        System.out.println("\n=== Feature 7: Custom KeyResolver Strategy ===");

        // Composite key strategy: region + userId
        KeyResolver customResolver = ctx -> {
            String region = (String) ctx.getAttributes().get("region");
            String userId = ctx.getUserId();
            return RateLimitKey.of(region + ":" + userId);
        };

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 3)
                .label("per-region-user-3req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("region-user-limit")
                .scope(LimitScope.CUSTOM)
                .addBand(band)
                .build();

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("custom-limiter")
                .rules(List.of(rule))
                .keyResolver(customResolver)
                .build();

        // user1 in Asia region
        RequestContext asiaUser1 = RequestContext.builder()
                .userId("user1")
                .attribute("region", "asia")
                .build();

        // user1 in US region (same user, different region)
        RequestContext usUser1 = RequestContext.builder()
                .userId("user1")
                .attribute("region", "us")
                .build();

        System.out.println("Composite key strategy: region + userId");

        for (int i = 0; i < 3; i++) {
            rateLimiter.tryConsume(asiaUser1, ruleSet);
        }
        System.out.println("asia:user1 - 3 requests");

        RateLimitResult asiaResult = rateLimiter.tryConsume(asiaUser1, ruleSet);
        System.out.println("asia:user1 - 4th: " + (asiaResult.isAllowed() ? "Allowed" : "Rejected"));

        RateLimitResult usResult = rateLimiter.tryConsume(usUser1, ruleSet);
        System.out.println("us:user1 - 1st: " + (usResult.isAllowed() ? "Allowed" : "Rejected"));

        assertThat(asiaResult.isAllowed()).isFalse();
        assertThat(usResult.isAllowed()).isTrue();
        System.out.println("✓ Custom KeyResolver Strategy verified");
    }

    @Test
    void feature8_ConsumeMultiplePermits() {
        System.out.println("\n=== Feature 8: Consuming Multiple Permits ===");

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 10)
                .label("10 permits")
                .build();

        RateLimitRule rule = RateLimitRule.builder("bulk-limit")
                .addBand(band)
                .build();

        KeyResolver resolver = ctx -> RateLimitKey.of("user:" + ctx.getUserId());

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("bulk-limiter")
                .rules(List.of(rule))
                .keyResolver(resolver)
                .build();

        RequestContext ctx = RequestContext.builder()
                .userId("bulk-user")
                .build();

        System.out.println("Out of 10 total permits:");

        // Consume 3 at once
        RateLimitResult result1 = rateLimiter.tryConsume(ctx, ruleSet, 3);
        System.out.println("Consume 3 -> Remaining: " + result1.getRemainingTokens());

        // Consume 5 at once
        RateLimitResult result2 = rateLimiter.tryConsume(ctx, ruleSet, 5);
        System.out.println("Consume 5 -> Remaining: " + result2.getRemainingTokens());

        // Try to consume 3 (only 2 remaining)
        RateLimitResult result3 = rateLimiter.tryConsume(ctx, ruleSet, 3);
        System.out.println("Try consume 3 -> " + (result3.isAllowed() ? "Allowed" : "Rejected"));

        assertThat(result1.isAllowed()).isTrue();
        assertThat(result1.getRemainingTokens()).isEqualTo(7);
        assertThat(result2.isAllowed()).isTrue();
        assertThat(result2.getRemainingTokens()).isEqualTo(2);
        assertThat(result3.isAllowed()).isFalse();
        System.out.println("✓ Multiple Permit Consumption verified");
    }

    @Test
    void feature9_WaitTimeInformation() {
        System.out.println("\n=== Feature 9: Wait Time Information ===");

        RateLimitBand band = RateLimitBand.builder(Duration.ofSeconds(10), 2)
                .label("10sec-2req")
                .build();

        RateLimitRule rule = RateLimitRule.builder("wait-test")
                .addBand(band)
                .build();

        KeyResolver resolver = ctx -> RateLimitKey.of("test");

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("wait-limiter")
                .rules(List.of(rule))
                .keyResolver(resolver)
                .build();

        RequestContext ctx = RequestContext.builder().build();

        // Consume 2 times
        rateLimiter.tryConsume(ctx, ruleSet);
        rateLimiter.tryConsume(ctx, ruleSet);

        // 3rd attempt
        RateLimitResult rejected = rateLimiter.tryConsume(ctx, ruleSet);

        System.out.println("When limit exceeded:");
        System.out.println("  Allowed: " + rejected.isAllowed());
        System.out.println("  Wait time (nanoseconds): " + rejected.getNanosToWaitForRefill());
        System.out.println("  Wait time (seconds): " + rejected.getNanosToWaitForRefill() / 1_000_000_000.0);

        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getNanosToWaitForRefill()).isGreaterThan(0);
        System.out.println("✓ Wait Time Information verified");
    }

    @Test
    void feature10_DetailedResultInformation() {
        System.out.println("\n=== Feature 10: Detailed Result Information ===");

        RateLimitBand band = RateLimitBand.builder(Duration.ofMinutes(1), 5)
                .label("test-band")
                .build();

        RateLimitRule rule = RateLimitRule.builder("info-test")
                .name("Information Test Rule")
                .addBand(band)
                .build();

        KeyResolver resolver = ctx -> RateLimitKey.of("test-key");

        RateLimitRuleSet ruleSet = RateLimitRuleSet.builder("info-limiter")
                .rules(List.of(rule))
                .keyResolver(resolver)
                .build();

        RequestContext ctx = RequestContext.builder()
                .clientIp("192.168.1.1")
                .userId("test-user")
                .build();

        RateLimitResult result = rateLimiter.tryConsume(ctx, ruleSet);

        System.out.println("RateLimitResult information:");
        System.out.println("  Allowed: " + result.isAllowed());
        System.out.println("  Remaining tokens: " + result.getRemainingTokens());
        System.out.println("  Wait time: " + result.getNanosToWaitForRefill() + "ns");
        System.out.println("  Applied key: " + result.getKey());
        System.out.println("  Matched rule: " + result.getMatchedRule());
        System.out.println("  Rule name: " + result.getMatchedRule().getName());

        assertThat(result.getKey()).isNotNull();
        assertThat(result.getMatchedRule()).isNotNull();
        assertThat(result.getMatchedRule().getName()).isEqualTo("Information Test Rule");
        System.out.println("✓ Detailed Result Information verified");
    }
}
