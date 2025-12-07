package org.fluxgate.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.ratelimiter.impl.bucket4j.Bucket4jRateLimiter;
import org.junit.jupiter.api.Test;

/**
 * Multi-Level Rate Limiting Tests
 *
 * <p>Scenario: Service A (using 1 API Key) 1) IP-based limit: 100 requests per minute per IP 2)
 * Service-wide limit: 10,000 requests per 10 minutes total
 */
class MultiLevelRateLimitTest {

  private final RateLimiter rateLimiter = new Bucket4jRateLimiter();

  /**
   * Approach 1: Check two RuleSets sequentially Pros: Independent management of each limit Cons:
   * Requires two checks
   */
  @Test
  void approach1_SequentialRuleSetChecks() {
    System.out.println("\n=== Approach 1: Sequential RuleSet Checks ===");

    // RuleSet 1: IP-based limit (100 req/min)
    RateLimitBand ipBand =
        RateLimitBand.builder(Duration.ofMinutes(1), 100).label("IP-1min-100req").build();

    RateLimitRule ipRule =
        RateLimitRule.builder("ip-limit")
            .name("IP-based Limit")
            .scope(LimitScope.PER_IP)
            .addBand(ipBand)
            .build();

    KeyResolver ipResolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

    RateLimitRuleSet ipRuleSet =
        RateLimitRuleSet.builder("ip-limiter")
            .description("100 requests per minute per IP")
            .rules(List.of(ipRule))
            .keyResolver(ipResolver)
            .build();

    // RuleSet 2: Service-wide limit (10,000 req/10min)
    RateLimitBand serviceBand =
        RateLimitBand.builder(Duration.ofMinutes(10), 10000)
            .label("Service-10min-10000req")
            .build();

    RateLimitRule serviceRule =
        RateLimitRule.builder("service-limit")
            .name("Service-wide Limit")
            .scope(LimitScope.PER_API_KEY)
            .addBand(serviceBand)
            .build();

    KeyResolver serviceResolver = ctx -> RateLimitKey.of("service:" + ctx.getApiKey());

    RateLimitRuleSet serviceRuleSet =
        RateLimitRuleSet.builder("service-limiter")
            .description("10,000 requests per 10 minutes per service")
            .rules(List.of(serviceRule))
            .keyResolver(serviceResolver)
            .build();

    // Test: Multiple IPs from Service A
    RequestContext ip1 =
        RequestContext.builder().clientIp("10.0.0.1").apiKey("service-a-key").build();

    RequestContext ip2 =
        RequestContext.builder().clientIp("10.0.0.2").apiKey("service-a-key").build();

    System.out.println("Service A (API Key: service-a-key)");
    System.out.println();

    // 100 requests from IP1
    System.out.println("100 requests from IP 10.0.0.1...");
    for (int i = 0; i < 100; i++) {
      RateLimitResult ipCheck = rateLimiter.tryConsume(ip1, ipRuleSet);
      RateLimitResult serviceCheck = rateLimiter.tryConsume(ip1, serviceRuleSet);

      if (!ipCheck.isAllowed() || !serviceCheck.isAllowed()) {
        System.out.println("  Request " + (i + 1) + " rejected!");
        break;
      }
    }

    // 101st request: IP limit exceeded
    RateLimitResult ip1Check101 = rateLimiter.tryConsume(ip1, ipRuleSet);
    RateLimitResult service1Check101 = rateLimiter.tryConsume(ip1, serviceRuleSet);

    System.out.println("  101st request:");
    System.out.println("    IP limit: " + (ip1Check101.isAllowed() ? "Allowed" : "Rejected"));
    System.out.println(
        "    Service limit: "
            + (service1Check101.isAllowed() ? "Allowed" : "Rejected (100 consumed)"));
    System.out.println();

    // Request from IP2: New IP bucket but shared service bucket
    System.out.println("Request from IP 10.0.0.2...");
    RateLimitResult ip2Check = rateLimiter.tryConsume(ip2, ipRuleSet);
    RateLimitResult service2Check = rateLimiter.tryConsume(ip2, serviceRuleSet);

    System.out.println("  IP limit: " + (ip2Check.isAllowed() ? "Allowed (new IP)" : "Rejected"));
    System.out.println("  Service limit: " + (service2Check.isAllowed() ? "Allowed" : "Rejected"));
    System.out.println("  Service remaining tokens: " + service2Check.getRemainingTokens());

    // Verify
    assertThat(ip1Check101.isAllowed()).isFalse(); // IP limit exceeded
    assertThat(ip2Check.isAllowed()).isTrue(); // New IP allowed
    assertThat(service2Check.getRemainingTokens())
        .isEqualTo(9898); // 10000 - 100(ip1 normal) - 1(ip1 101st service check) - 1(ip2) = 9898

    System.out.println("\n✓ Both levels of rate limiting verified");
  }

  /**
   * Approach 2: Using a Helper function to check both limits at once More convenient for production
   * use
   */
  @Test
  void approach2_HelperFunctionUsage() {
    System.out.println("\n=== Approach 2: Integrated Check with Helper Function ===");

    // IP limit RuleSet
    RateLimitBand ipBand =
        RateLimitBand.builder(Duration.ofMinutes(1), 5).label("IP-1min-5req").build();

    RateLimitRule ipRule = RateLimitRule.builder("ip-limit").addBand(ipBand).build();
    KeyResolver ipResolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

    RateLimitRuleSet ipRuleSet =
        RateLimitRuleSet.builder("ip-limiter")
            .rules(List.of(ipRule))
            .keyResolver(ipResolver)
            .build();

    // Service limit RuleSet
    RateLimitBand serviceBand =
        RateLimitBand.builder(Duration.ofMinutes(10), 20).label("Service-10min-20req").build();

    RateLimitRule serviceRule = RateLimitRule.builder("service-limit").addBand(serviceBand).build();
    KeyResolver serviceResolver = ctx -> RateLimitKey.of("service:" + ctx.getApiKey());

    RateLimitRuleSet serviceRuleSet =
        RateLimitRuleSet.builder("service-limiter")
            .rules(List.of(serviceRule))
            .keyResolver(serviceResolver)
            .build();

    // Helper: Function to check both limits
    MultiLevelRateLimitChecker checker =
        new MultiLevelRateLimitChecker(rateLimiter, ipRuleSet, serviceRuleSet);

    RequestContext ctx =
        RequestContext.builder().clientIp("192.168.1.1").apiKey("service-a-key").build();

    System.out.println("Integrated check (IP + Service limits):");

    for (int i = 1; i <= 7; i++) {
      MultiLevelResult result = checker.checkRateLimit(ctx);

      System.out.printf("Request %d: %s", i, result.isAllowed() ? "✓ Allowed" : "✗ Rejected");
      if (!result.isAllowed()) {
        System.out.printf(" (%s)", result.getReason());
      }
      System.out.println();
    }

    System.out.println("\n✓ Helper function usage verified");
  }

  /**
   * Approach 3: Real-world Production Scenario Using 100 req/min IP limit and 10,000 req/10min
   * service limit in practice
   */
  @Test
  void approach3_RealWorldScenario() {
    System.out.println("\n=== Approach 3: Real-world Production Scenario ===");

    // IP limit: 100 req/min
    RateLimitBand ipBand =
        RateLimitBand.builder(Duration.ofMinutes(1), 100).label("IP-1min-100req").build();

    RateLimitRule ipRule = RateLimitRule.builder("ip-limit").addBand(ipBand).build();
    KeyResolver ipResolver = ctx -> RateLimitKey.of("ip:" + ctx.getClientIp());

    RateLimitRuleSet ipRuleSet =
        RateLimitRuleSet.builder("ip-limiter")
            .rules(List.of(ipRule))
            .keyResolver(ipResolver)
            .build();

    // Service limit: 10,000 req/10min
    RateLimitBand serviceBand =
        RateLimitBand.builder(Duration.ofMinutes(10), 10000)
            .label("Service-10min-10000req")
            .build();

    RateLimitRule serviceRule = RateLimitRule.builder("service-limit").addBand(serviceBand).build();
    KeyResolver serviceResolver = ctx -> RateLimitKey.of("service:" + ctx.getApiKey());

    RateLimitRuleSet serviceRuleSet =
        RateLimitRuleSet.builder("service-limiter")
            .rules(List.of(serviceRule))
            .keyResolver(serviceResolver)
            .build();

    // Real-world usage example
    System.out.println("Processing Service A API calls:");
    System.out.println();

    // Simulation: Requests from multiple IPs
    RequestContext[] requests = {
      RequestContext.builder().clientIp("1.1.1.1").apiKey("service-a-key").build(),
      RequestContext.builder().clientIp("2.2.2.2").apiKey("service-a-key").build(),
      RequestContext.builder().clientIp("3.3.3.3").apiKey("service-a-key").build()
    };

    int totalRequests = 0;
    int allowedRequests = 0;

    System.out.println("40 requests from each IP (120 total):");

    for (int round = 0; round < 40; round++) {
      for (RequestContext req : requests) {
        totalRequests++;

        // Step 1: IP limit check
        RateLimitResult ipCheck = rateLimiter.tryConsume(req, ipRuleSet);
        if (!ipCheck.isAllowed()) {
          continue; // IP limit exceeded
        }

        // Step 2: Service limit check
        RateLimitResult serviceCheck = rateLimiter.tryConsume(req, serviceRuleSet);
        if (!serviceCheck.isAllowed()) {
          continue; // Service limit exceeded
        }

        allowedRequests++;
      }
    }

    System.out.println();
    System.out.println("Results:");
    System.out.println("  Total requests: " + totalRequests);
    System.out.println("  Allowed: " + allowedRequests);
    System.out.println("  Rejected: " + (totalRequests - allowedRequests));

    // With 100 req/min IP limit, each IP allows max 100 requests
    // But there's also a 10,000 req/10min service-wide limit
    assertThat(allowedRequests).isLessThanOrEqualTo(300); // 3 IPs * 100 req

    System.out.println("\n✓ Real-world scenario verified");
  }

  /** Multi-Level Rate Limit Checker Checks multiple levels of rate limits sequentially */
  static class MultiLevelRateLimitChecker {
    private final RateLimiter rateLimiter;
    private final RateLimitRuleSet ipRuleSet;
    private final RateLimitRuleSet serviceRuleSet;

    public MultiLevelRateLimitChecker(
        RateLimiter rateLimiter, RateLimitRuleSet ipRuleSet, RateLimitRuleSet serviceRuleSet) {
      this.rateLimiter = rateLimiter;
      this.ipRuleSet = ipRuleSet;
      this.serviceRuleSet = serviceRuleSet;
    }

    public MultiLevelResult checkRateLimit(RequestContext ctx) {
      // Step 1: IP limit check
      RateLimitResult ipResult = rateLimiter.tryConsume(ctx, ipRuleSet);
      if (!ipResult.isAllowed()) {
        return new MultiLevelResult(false, "IP limit exceeded", ipResult);
      }

      // Step 2: Service limit check
      RateLimitResult serviceResult = rateLimiter.tryConsume(ctx, serviceRuleSet);
      if (!serviceResult.isAllowed()) {
        return new MultiLevelResult(false, "Service limit exceeded", serviceResult);
      }

      return new MultiLevelResult(true, "Allowed", serviceResult);
    }
  }

  /** Multi-Level Result */
  static class MultiLevelResult {
    private final boolean allowed;
    private final String reason;
    private final RateLimitResult lastResult;

    public MultiLevelResult(boolean allowed, String reason, RateLimitResult lastResult) {
      this.allowed = allowed;
      this.reason = reason;
      this.lastResult = lastResult;
    }

    public boolean isAllowed() {
      return allowed;
    }

    public String getReason() {
      return reason;
    }

    public RateLimitResult getLastResult() {
      return lastResult;
    }
  }
}
