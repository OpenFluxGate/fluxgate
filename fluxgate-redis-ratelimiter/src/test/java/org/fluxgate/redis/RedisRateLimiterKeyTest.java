package org.fluxgate.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.key.MissingKeyBehavior;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.redis.store.RedisTokenBucketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for N17 (key-segment sanitization) and N13 (missingKeyResult). */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterKeyTest {

  @Mock private RedisTokenBucketStore store;

  private RedisRateLimiter limiter;

  @BeforeEach
  void setup() {
    limiter = new RedisRateLimiter(store);
  }

  @Nested
  @DisplayName("N17 sanitizeSegment")
  class SanitizeSegment {

    @Test
    @DisplayName("clean string is unchanged")
    void cleanString() {
      assertThat(RedisRateLimiter.sanitizeSegment("api-limits")).isEqualTo("api-limits");
    }

    @Test
    @DisplayName("colon replaced with underscore")
    void colon() {
      assertThat(RedisRateLimiter.sanitizeSegment("a:b")).isEqualTo("a_b");
    }

    @Test
    @DisplayName("braces replaced with underscores")
    void braces() {
      assertThat(RedisRateLimiter.sanitizeSegment("{foo}")).isEqualTo("_foo_");
    }

    @Test
    @DisplayName("glob metacharacters replaced")
    void globChars() {
      assertThat(RedisRateLimiter.sanitizeSegment("*?[\\]")).isEqualTo("_____");
    }

    @Test
    @DisplayName("whitespace replaced")
    void whitespace() {
      assertThat(RedisRateLimiter.sanitizeSegment("a b\tc")).isEqualTo("a_b_c");
    }

    @Test
    @DisplayName("null returns empty string")
    void nullInput() {
      assertThat(RedisRateLimiter.sanitizeSegment(null)).isEmpty();
    }

    @Test
    @DisplayName("bucketKeyPattern sanitizes ruleSetId so embedded * is not a SCAN wildcard")
    void bucketKeyPatternSanitizes() {
      String pattern = RedisRateLimiter.bucketKeyPattern("evil*set");
      // The * should have been replaced; the resulting pattern must not match the entire keyspace
      assertThat(pattern).doesNotContain("evil*set");
      assertThat(pattern).contains("evil_set");
    }
  }

  @Nested
  @DisplayName("N13 missingKeyResult")
  class MissingKeyResult {

    @Test
    @DisplayName("rejected result carries matchedRule and policy when key is missing")
    void missingKeyCarriesRuleAndPolicy() {
      RateLimitRule rule =
          RateLimitRule.builder("test-rule")
              .scope(LimitScope.PER_USER)
              .onLimitExceedPolicy(OnLimitExceedPolicy.REJECT_REQUEST)
              .addBand(RateLimitBand.builder(Duration.ofSeconds(1), 10).build())
              .build();

      RateLimitRuleSet ruleSet =
          RateLimitRuleSet.builder("rs1")
              .keyResolver(new LimitScopeKeyResolver(MissingKeyBehavior.REJECT))
              .rules(List.of(rule))
              .build();

      // No userId in the context — PER_USER scope with REJECT behavior raises MissingKey
      RequestContext ctx = RequestContext.builder().endpoint("/test").method("GET").build();

      RateLimitResult result = limiter.tryConsume(ctx, ruleSet, 1);

      assertThat(result.isAllowed()).isFalse();
      assertThat(result.getMatchedRule()).isNotNull();
      assertThat(result.getMatchedRule().getId()).isEqualTo("test-rule");
      assertThat(result.getPolicy()).isEqualTo(OnLimitExceedPolicy.REJECT_REQUEST);
    }
  }
}
