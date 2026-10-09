package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.bson.Document;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.rule.CompositeRuleSetProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring test for rule sets defined in YAML ({@code fluxgate.ratelimit.rule-sets}) together with
 * the MongoDB rule source: the primary {@link RateLimitRuleSetProvider} must resolve both.
 *
 * <p>MongoDB itself is replaced by a stub {@link RateLimitRuleRepository} and a mock rule
 * collection, so no server is needed.
 */
class FluxgateRuleSetProviderCompositionTest {

  private static final String YAML_RULE_SET = "yaml-rs";
  private static final String MONGO_RULE_SET = "mongo-rs";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FluxgateMongoAutoConfiguration.class,
                  FluxgateResilienceAutoConfiguration.class,
                  FluxgateRedisAutoConfiguration.class,
                  FluxgateRateLimiterAutoConfiguration.class,
                  FluxgateReloadAutoConfiguration.class))
          .withUserConfiguration(StubMongoConfig.class)
          .withPropertyValues(
              "fluxgate.mongo.enabled=true",
              "fluxgate.ratelimit.rule-sets[0].id=" + YAML_RULE_SET,
              "fluxgate.ratelimit.rule-sets[0].rules[0].id=yaml-rule",
              "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].capacity=10",
              "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].window=1m");

  @Test
  @DisplayName("without hot reload: the composite is the primary provider and serves both sources")
  void compositeIsPrimaryWithoutReload() {
    runner
        .withPropertyValues("fluxgate.reload.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RateLimitRuleSetProvider primary = context.getBean(RateLimitRuleSetProvider.class);
              assertThat(primary).isInstanceOf(CompositeRuleSetProvider.class);
              assertResolvesBothSources(context, primary);
            });
  }

  @Test
  @DisplayName("with hot reload: the caching provider wraps the composite and serves both sources")
  void cachingProviderWrapsCompositeWithReload() {
    runner
        .withPropertyValues("fluxgate.reload.enabled=true", "fluxgate.reload.strategy=POLLING")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              RateLimitRuleSetProvider primary = context.getBean(RateLimitRuleSetProvider.class);
              assertThat(primary).isInstanceOf(CachingRuleSetProvider.class);
              assertThat(context.getBean("delegateRuleSetProvider"))
                  .isInstanceOf(CompositeRuleSetProvider.class);
              assertResolvesBothSources(context, primary);
            });
  }

  private static void assertResolvesBothSources(
      AssertableApplicationContext context, RateLimitRuleSetProvider primary) {
    assertThat(primary.findById(YAML_RULE_SET)).isPresent();
    assertThat(primary.findById(MONGO_RULE_SET)).isPresent();
    assertThat(primary.findById("unknown")).isEmpty();

    assertThat(context).hasSingleBean(RateLimitEngine.class);
  }

  @Configuration(proxyBeanMethods = false)
  static class StubMongoConfig {

    @Bean(name = "fluxgateRuleCollection")
    @SuppressWarnings("unchecked")
    MongoCollection<Document> fluxgateRuleCollection() {
      return mock(MongoCollection.class);
    }

    @Bean
    RateLimitRuleRepository rateLimitRuleRepository() {
      RateLimitRuleRepository repository = mock(RateLimitRuleRepository.class);
      RateLimitRule mongoRule =
          RateLimitRule.builder("mongo-rule")
              .ruleSetId(MONGO_RULE_SET)
              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 5).build())
              .build();
      when(repository.findByRuleSetId(anyString())).thenReturn(Collections.emptyList());
      when(repository.findByRuleSetId(MONGO_RULE_SET)).thenReturn(List.of(mongoRule));
      return repository;
    }
  }
}
