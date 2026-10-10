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
    @DisplayName("colon is percent-escaped")
    void colon() {
      assertThat(RedisRateLimiter.sanitizeSegment("a:b")).isEqualTo("a%3Ab");
    }

    @Test
    @DisplayName("braces are percent-escaped")
    void braces() {
      assertThat(RedisRateLimiter.sanitizeSegment("{foo}")).isEqualTo("%7Bfoo%7D");
    }

    @Test
    @DisplayName("glob metacharacters are percent-escaped")
    void globChars() {
      assertThat(RedisRateLimiter.sanitizeSegment("*?[\\]")).isEqualTo("%2A%3F%5B%5C%5D");
    }

    @Test
    @DisplayName("whitespace is percent-escaped")
    void whitespace() {
      assertThat(RedisRateLimiter.sanitizeSegment("a b\tc")).isEqualTo("a%20b%09c");
    }

    @Test
    @DisplayName("the escape character itself is escaped, so escaping stays reversible")
    void percent() {
      assertThat(RedisRateLimiter.sanitizeSegment("100%")).isEqualTo("100%25");
    }

    @Test
    @DisplayName("ids that used to collide after sanitising now map to distinct segments")
    void escapingIsInjective() {
      assertThat(RedisRateLimiter.sanitizeSegment("a:b"))
          .isNotEqualTo(RedisRateLimiter.sanitizeSegment("a_b"))
          .isNotEqualTo(RedisRateLimiter.sanitizeSegment("a%3Ab"));
      assertThat(RedisRateLimiter.sanitizeSegment("a b"))
          .isNotEqualTo(RedisRateLimiter.sanitizeSegment("a_b"));
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
      // The * should have been escaped; the resulting pattern must not match the entire keyspace
      assertThat(pattern).doesNotContain("evil*set");
      assertThat(pattern).contains("evil%2Aset");
    }

    @Test
    @DisplayName("a band label's ':' and '%' are escaped, other characters are kept")
    void bandLabel() {
      assertThat(RedisRateLimiter.escapeBandLabel("x:fw")).isEqualTo("x%3Afw");
      assertThat(RedisRateLimiter.escapeBandLabel("50%")).isEqualTo("50%25");
      assertThat(RedisRateLimiter.escapeBandLabel("per minute")).isEqualTo("per minute");
      assertThat(RedisRateLimiter.escapeBandLabel("100-per-60s-sw")).isEqualTo("100-per-60s-sw");
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
