package org.fluxgate.spring.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.spring.properties.FluxgateProperties.BandProperties;
import org.fluxgate.spring.properties.FluxgateProperties.MatcherProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleSetProperties;
import org.junit.jupiter.api.Test;

/** Item 12: YAML rule sets that cannot mean what they say fail the startup. */
class PropertiesRuleSetProviderValidationTest {

  private static final KeyResolver KEYS = (ctx, rule) -> RateLimitKey.of("k");

  private static BandProperties band(Duration window) {
    BandProperties band = new BandProperties();
    band.setCapacity(10);
    band.setWindow(window);
    return band;
  }

  private static RuleProperties rule(String id, BandProperties band) {
    RuleProperties rule = new RuleProperties();
    rule.setId(id);
    rule.setBands(List.of(band));
    return rule;
  }

  private static RuleSetProperties ruleSet(RuleProperties... rules) {
    RuleSetProperties ruleSet = new RuleSetProperties();
    ruleSet.setId("api");
    ruleSet.setRules(List.of(rules));
    return ruleSet;
  }

  @Test
  void rejectsAnUnknownZoneId() {
    BandProperties band = band(Duration.ofDays(1));
    band.setZoneId("Mars/Olympus_Mons");

    assertThatThrownBy(
            () -> new PropertiesRuleSetProvider(List.of(ruleSet(rule("r1", band))), KEYS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mars/Olympus_Mons")
        .hasMessageContaining("r1");
  }

  @Test
  void rejectsDuplicateRuleIdsInOneRuleSet() {
    assertThatThrownBy(
            () ->
                new PropertiesRuleSetProvider(
                    List.of(
                        ruleSet(
                            rule("same", band(Duration.ofMinutes(1))),
                            rule("same", band(Duration.ofHours(1))))),
                    KEYS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same")
        .hasMessageContaining("api");
  }

  @Test
  void rejectsANonPositiveWindow() {
    assertThatThrownBy(
            () ->
                new PropertiesRuleSetProvider(
                    List.of(ruleSet(rule("r1", band(Duration.ZERO)))), KEYS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
  }

  @Test
  void upperCasesMethodsIndependentlyOfTheDefaultLocale() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(new Locale("tr", "TR"));
    try {
      RuleProperties rule = rule("r1", band(Duration.ofMinutes(1)));
      MatcherProperties matcher = new MatcherProperties();
      matcher.setMethods(List.of("link"));
      rule.setMatcher(matcher);

      PropertiesRuleSetProvider provider =
          new PropertiesRuleSetProvider(List.of(ruleSet(rule)), KEYS);

      assertThat(provider.findById("api").orElseThrow().getRules().get(0).getMatcher().getMethods())
          .containsExactly("LINK");
    } finally {
      Locale.setDefault(previous);
    }
  }
}
