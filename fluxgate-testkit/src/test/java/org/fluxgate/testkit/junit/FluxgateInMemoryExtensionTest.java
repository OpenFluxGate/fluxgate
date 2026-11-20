package org.fluxgate.testkit.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.testkit.support.FluxgateTestRules;
import org.fluxgate.testkit.support.InMemoryRateLimitHandler;
import org.fluxgate.testkit.support.RateLimitAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The extension's own contract is "each test starts with full buckets", which only a suite of more
 * than one test method can demonstrate. The methods are ordered on purpose: the first one empties
 * the bucket, the second one proves it was refilled.
 */
@DisplayName("FluxgateInMemoryExtension")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FluxgateInMemoryExtensionTest {

  private static final String RULE_SET = "api-limits";

  /** Static, so one handler and one bucket cache is shared across the class. */
  @RegisterExtension
  static final FluxgateInMemoryExtension fluxgate =
      FluxgateInMemoryExtension.withRuleSet(
          FluxgateTestRules.rule("per-ip")
              .perIp()
              .band(Duration.ofHours(1), 1)
              .toRuleSet(RULE_SET));

  private static final AtomicInteger observedHandlers = new AtomicInteger();

  private static RequestContext context() {
    return RequestContext.builder().clientIp("10.0.0.1").build();
  }

  @Test
  @Order(1)
  @DisplayName("should inject the handler as a parameter and drain its only token")
  void shouldInjectHandlerAndDrainToken(InMemoryRateLimitHandler handler) {
    assertThat(handler).isNotNull().isSameAs(fluxgate.getHandler());
    observedHandlers.incrementAndGet();

    RateLimitAssertions.assertAllowed(handler.tryConsume(context(), RULE_SET));
    RateLimitAssertions.assertRejected(handler.tryConsume(context(), RULE_SET));
  }

  @Test
  @Order(2)
  @DisplayName("should reset buckets before the next test, despite a one hour window")
  void shouldResetBucketsBetweenTests(InMemoryRateLimitHandler handler) {
    assertThat(observedHandlers.get())
        .as("the previous test must have run first for this assertion to mean anything")
        .isEqualTo(1);

    // The window is an hour, so nothing refilled naturally: an allow here can only come from the
    // extension's reset.
    RateLimitAssertions.assertAllowed(handler.tryConsume(context(), RULE_SET));
  }

  @Test
  @Order(3)
  @DisplayName("should not inject unrelated parameter types")
  void shouldNotInjectUnrelatedTypes() {
    assertThat(fluxgate.getHandler().getRuleSetIds()).containsExactly(RULE_SET);
  }

  @Test
  @Order(4)
  @DisplayName("withRuleSets should serve every rule set")
  void withRuleSetsShouldServeEveryRuleSet() {
    FluxgateInMemoryExtension extension =
        FluxgateInMemoryExtension.withRuleSets(
            FluxgateTestRules.rule("a").perIp().band(Duration.ofMinutes(1), 1).toRuleSet("first"),
            FluxgateTestRules.rule("b").perIp().band(Duration.ofMinutes(1), 1).toRuleSet("second"));

    assertThat(extension.getHandler().getRuleSetIds()).containsExactly("first", "second");
  }

  @Test
  @Order(5)
  @DisplayName("withHandler should wrap a handler built by the caller")
  void withHandlerShouldWrapCallerHandler() {
    InMemoryRateLimitHandler handler =
        InMemoryRateLimitHandler.builder()
            .ruleSet(
                FluxgateTestRules.rule("a").perIp().band(Duration.ofMinutes(1), 1).toRuleSet("rs"))
            .onMissingRuleSet(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
            .build();

    FluxgateInMemoryExtension extension = FluxgateInMemoryExtension.withHandler(handler);

    assertThat(extension.getHandler()).isSameAs(handler);
    RateLimitAssertions.assertRejected(handler.tryConsume(context(), "unknown"));
  }
}
