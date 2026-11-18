package org.fluxgate.core.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.junit.jupiter.api.Test;

class CompositeKeyEncoderTest {
  private static String encode(String ip, String user) {
    Map<String, String> values = new HashMap<>();
    values.put("ip", ip);
    values.put("user", user);
    return CompositeKeyEncoder.encode("ip_user_v1", List.of("ip", "user"), values);
  }

  @Test
  void matchesIndependentGoldenVectorAndPreservesSanitizerBoundary() {
    String value = encode("2001:db8::1", "user-42");
    assertThat(value).isEqualTo("cs1_h6GTG0-3fofa5DvMSIHhulszFIhldYEJi4wZlQydCis");
    assertThat(value).hasSize(47).matches("cs1_[A-Za-z0-9_-]{43}");
    assertThat(KeyValueSanitizer.sanitize(value)).isEqualTo(value);
    assertThat(RateLimitKey.of("custom:", value).value()).isEqualTo("custom:" + value);
  }

  @Test
  void distinguishesDelimiterUnicodeAndSchemaBoundaries() {
    assertThat(encode("a:b", "c")).isNotEqualTo(encode("a", "b:c"));
    assertThat(encode("a_b", "c")).isNotEqualTo(encode("a", "b_c"));
    assertThat(encode("é", "u")).isNotEqualTo(encode("e\u0301", "u"));
    assertThat(encode(" a", "u")).isNotEqualTo(encode("a", "u"));
    assertThat(encode("가나다😀", "u")).hasSize(47);
    assertThat(encode("::ffff:192.0.2.1", "u")).isNotEqualTo(encode("192.0.2.1", "u"));
    Map<String, String> reversed = new LinkedHashMap<>();
    reversed.put("user", "u");
    reversed.put("ip", "a");
    assertThat(CompositeKeyEncoder.encode("ip_user_v1", List.of("ip", "user"), reversed))
        .isEqualTo(encode("a", "u"));
    assertThat(CompositeKeyEncoder.encode("ip_user_v2", List.of("ip", "user"), reversed))
        .isNotEqualTo(encode("a", "u"));
    assertThat(CompositeKeyEncoder.encode("ip_user_v1", List.of("user", "ip"), reversed))
        .isNotEqualTo(encode("a", "u"));
  }

  @Test
  void rejectsMissingBlankMalformedAndOversizedValuesWithoutLeakingThem() {
    for (String invalid : Arrays.asList(null, "", " \t", "\ud800", "\udc00", "a".repeat(1025), "가".repeat(342))) {
      assertThatIllegalArgumentException().isThrownBy(() -> encode("ip", invalid));
    }
    for (int size : new int[] {255, 256, 257, 1024}) {
      assertThat(encode("ip", "a".repeat(size))).hasSize(47);
    }
    assertThat(encode("ip", "😀".repeat(256))).hasSize(47);
    assertThatIllegalArgumentException().isThrownBy(() -> encode("ip", "😀".repeat(257)));
    assertThatIllegalArgumentException().isThrownBy(() -> encode("ip", "sensitive".repeat(200)))
        .withMessageNotContaining("sensitive");
  }

  @Test
  void validatesSchemaAndExactFieldSet() {
    for (String name : Arrays.asList(null, "", "UPPER", "a:b", "a".repeat(33))) {
      assertThatIllegalArgumentException().isThrownBy(() -> CompositeKeyEncoder.encode(name, List.of("a"), Map.of("a", "v")));
    }
    for (List<String> fields : Arrays.asList(null, List.<String>of(), List.of("a", "a"), Arrays.asList("a", null), List.of("A"), List.of("a","b","c","d","e","f","g","h","i"))) {
      assertThatIllegalArgumentException().isThrownBy(() -> CompositeKeyEncoder.encode("test", fields, Map.of("a", "v")));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> CompositeKeyEncoder.encode("test", List.of("a"), null));
    assertThatIllegalArgumentException().isThrownBy(() -> CompositeKeyEncoder.encode("test", List.of("a"), Map.of()));
    assertThatIllegalArgumentException().isThrownBy(() -> CompositeKeyEncoder.encode("test", List.of("a"), Map.of("a", "v", "b", "x")));
    assertThat(CompositeKeyEncoder.encode("a".repeat(32), List.of("b".repeat(32)), Map.of("b".repeat(32), "v"))).hasSize(47);
  }

  @Test
  void usesExistingCustomResolverWithoutChangingLegacyValuesOrDefaults() {
    RateLimitRule rule = RateLimitRule.builder("rule").ruleSetId("set").scope(LimitScope.CUSTOM)
        .keyStrategyId("ipUser").addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build()).build();
    LimitScopeKeyResolver resolver = new LimitScopeKeyResolver();
    String value = encode("2001:db8::1", "u");
    assertThat(resolver.resolve(RequestContext.builder().attribute("ipUser", value).build(), rule).value())
        .isEqualTo("custom:" + value);
    assertThat(resolver.resolve(RequestContext.builder().attribute("ipUser", "ip:2001:db8::1:user:u").build(), rule).value())
        .isEqualTo("custom:ip:2001:db8::1:user:u");
    assertThat(resolver.resolve(RequestContext.builder().clientIp("10.0.0.1").build(), rule).value())
        .isEqualTo("ip:10.0.0.1");
    assertThat(LimitScopeKeyResolver.normalizeResolvedKey("custom:" + value)).isEqualTo("custom:" + value);
  }
}
