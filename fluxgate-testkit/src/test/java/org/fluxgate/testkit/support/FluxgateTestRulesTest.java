package org.fluxgate.testkit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.exception.InvalidRuleConfigException;
import org.fluxgate.core.exception.MissingRateLimitKeyException;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FluxgateTestRules")
class FluxgateTestRulesTest {

  @Nested
  @DisplayName("rule()")
  class RuleTests {

    @Test
    @DisplayName("should default to an enabled PER_IP rule that rejects")
    void shouldDefaultToEnabledPerIpRejectingRule() {
      RateLimitRule rule = FluxgateTestRules.rule("r1").band(Duration.ofMinutes(1), 10).build();

      assertThat(rule.getId()).isEqualTo("r1");
      assertThat(rule.getName()).isEqualTo("r1");
      assertThat(rule.isEnabled()).isTrue();
      assertThat(rule.getScope()).isEqualTo(LimitScope.PER_IP);
      assertThat(rule.getOnLimitExceedPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
      assertThat(rule.getBands()).hasSize(1);
    }

    @Test
    @DisplayName("should derive the band key label when no label is given")
    void shouldDeriveBandKeyLabel() {
      RateLimitRule rule = FluxgateTestRules.rule("r1").band(Duration.ofMinutes(1), 100).build();

      assertThat(rule.getBands().get(0).getKeyLabel()).isEqualTo("100-per-60s");
    }

    @Test
    @DisplayName("should keep an explicit band label")
    void shouldKeepExplicitBandLabel() {
      RateLimitRule rule =
          FluxgateTestRules.rule("r1").band(Duration.ofMinutes(1), 100, "sustained").build();

      assertThat(rule.getBands().get(0).getKeyLabel()).isEqualTo("sustained");
    }

    @Test
    @DisplayName("should apply each scope shortcut")
    void shouldApplyEachScopeShortcut() {
      Duration window = Duration.ofSeconds(1);

      assertThat(FluxgateTestRules.rule("a").perUser().band(window, 1).build().getScope())
          .isEqualTo(LimitScope.PER_USER);
      assertThat(FluxgateTestRules.rule("b").perApiKey().band(window, 1).build().getScope())
          .isEqualTo(LimitScope.PER_API_KEY);
      assertThat(FluxgateTestRules.rule("c").global().band(window, 1).build().getScope())
          .isEqualTo(LimitScope.GLOBAL);

      RateLimitRule custom =
          FluxgateTestRules.rule("d").perAttribute("tenantId").band(window, 1).build();
      assertThat(custom.getScope()).isEqualTo(LimitScope.CUSTOM);
      assertThat(custom.getKeyStrategyId()).isEqualTo("tenantId");
    }

    @Test
    @DisplayName("should support disabled() and waitForRefill()")
    void shouldSupportDisabledAndWaitForRefill() {
      RateLimitRule rule =
          FluxgateTestRules.rule("r1")
              .name("named")
              .disabled()
              .waitForRefill()
              .band(Duration.ofSeconds(1), 1)
              .build();

      assertThat(rule.getName()).isEqualTo("named");
      assertThat(rule.isEnabled()).isFalse();
      assertThat(rule.getOnLimitExceedPolicy()).isEqualTo(OnLimitExceedPolicy.WAIT_FOR_REFILL);
    }

    @Test
    @DisplayName("should fail with an actionable message when no band was added")
    void shouldFailWithoutBands() {
      assertThatThrownBy(() -> FluxgateTestRules.rule("r1").build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("r1")
          .hasMessageContaining("band(window, capacity)");
    }

    @Test
    @DisplayName("should delegate band validation to the production builder")
    void shouldRejectDuplicateDerivedBandLabels() {
      assertThatThrownBy(
              () ->
                  FluxgateTestRules.rule("r1")
                      .band(Duration.ofMinutes(1), 10)
                      .band(Duration.ofMinutes(1), 10)
                      .build())
          .isInstanceOf(InvalidRuleConfigException.class);
    }
  }

  @Nested
  @DisplayName("ruleSet()")
  class RuleSetTests {

    @Test
    @DisplayName("toRuleSet should stamp the rule set id onto the rule")
    void toRuleSetShouldStampRuleSetId() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("per-ip")
              .perIp()
              .band(Duration.ofMinutes(1), 10)
              .toRuleSet("api-limits");

      assertThat(ruleSet.getId()).isEqualTo("api-limits");
      assertThat(ruleSet.getRules()).hasSize(1);
      assertThat(ruleSet.getRules().get(0).getRuleSetIdOrNull()).isEqualTo("api-limits");
    }

    @Test
    @DisplayName("should provide a key resolver, which the rule set builder requires")
    void shouldProvideKeyResolver() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.rule("per-ip").band(Duration.ofSeconds(1), 1).toRuleSet("rs");

      assertThat(ruleSet.getKeyResolver()).isNotNull();
    }

    @Test
    @DisplayName("should combine several rules into one rule set")
    void shouldCombineSeveralRules() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.ruleSet(
              "api-limits",
              FluxgateTestRules.rule("burst").perIp().band(Duration.ofSeconds(1), 5),
              FluxgateTestRules.rule("sustained").perIp().band(Duration.ofMinutes(1), 100));

      assertThat(ruleSet.getRules())
          .extracting(RateLimitRule::getId)
          .containsExactly("burst", "sustained");
    }

    @Test
    @DisplayName("should honour a MissingKeyBehavior of REJECT")
    void shouldHonourMissingKeyBehavior() {
      RateLimitRuleSet ruleSet =
          FluxgateTestRules.ruleSet(
              "api-limits",
              MissingKeyBehavior.REJECT,
              FluxgateTestRules.rule("per-user").perUser().band(Duration.ofMinutes(1), 10));

      RequestContext noUser = RequestContext.builder().clientIp("10.0.0.1").build();

      assertThatThrownBy(() -> ruleSet.getKeyResolver().resolve(noUser, ruleSet.getRules().get(0)))
          .isInstanceOf(MissingRateLimitKeyException.class);
    }

    @Test
    @DisplayName("should reject an empty rule list")
    void shouldRejectEmptyRuleList() {
      assertThatThrownBy(() -> FluxgateTestRules.ruleSet("api-limits"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one rule");
    }
  }
}
