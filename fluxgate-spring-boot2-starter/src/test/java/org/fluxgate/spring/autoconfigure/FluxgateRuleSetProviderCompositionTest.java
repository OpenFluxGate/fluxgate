package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.rule.CompositeRuleSetProvider;
import org.fluxgate.spring.rule.PropertiesRuleSetProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  private static final String USER_RULE_SET = "user-rs";

  private static final String[] YAML_RULE_SET_PROPERTIES = {
    "fluxgate.ratelimit.rule-sets[0].id=" + YAML_RULE_SET,
    "fluxgate.ratelimit.rule-sets[0].rules[0].id=yaml-rule",
    "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].capacity=10",
    "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].window=1m"
  };

  /** Auto-configurations only; each test adds Mongo, YAML and user beans as needed. */
  private final ApplicationContextRunner baseRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  FluxgateMongoAutoConfiguration.class,
                  FluxgateResilienceAutoConfiguration.class,
                  FluxgateRedisAutoConfiguration.class,
                  FluxgateRateLimiterAutoConfiguration.class,
                  FluxgateReloadAutoConfiguration.class));

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

  private static String[] reloadProperties(boolean reload) {
    return reload
        ? new String[] {"fluxgate.reload.enabled=true", "fluxgate.reload.strategy=POLLING"}
        : new String[] {"fluxgate.reload.enabled=false"};
  }

  /** Asserts that exactly one non-caching provider exists and the primary resolves the ids. */
  private static void assertSingleProviderResolves(
      AssertableApplicationContext context, boolean reload, String... presentIds) {
    assertThat(context).hasNotFailed();
    RateLimitRuleSetProvider primary = context.getBean(RateLimitRuleSetProvider.class);
    if (reload) {
      assertThat(primary).isInstanceOf(CachingRuleSetProvider.class);
    }
    for (String id : presentIds) {
      assertThat(primary.findById(id)).as("rule set %s", id).isPresent();
    }
    assertThat(primary.findById("unknown")).isEmpty();
    assertThat(context.getBeansOfType(RateLimitRuleSetProvider.class).values())
        .filteredOn(p -> !(p instanceof CachingRuleSetProvider))
        .hasSize(1);
    assertThat(context).hasSingleBean(RateLimitEngine.class);
  }

  @Nested
  @DisplayName("user-defined delegateRuleSetProvider + YAML rule sets")
  class UserDelegateWithYaml {

    @ParameterizedTest(name = "reload={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("with Mongo enabled: YAML is composed with the user delegate, not dropped")
    void mongoEnabled(boolean reload) {
      baseRunner
          .withUserConfiguration(StubMongoConfig.class, UserDelegateConfig.class)
          .withPropertyValues("fluxgate.mongo.enabled=true")
          .withPropertyValues(YAML_RULE_SET_PROPERTIES)
          .withPropertyValues(reloadProperties(reload))
          .run(
              context -> {
                assertSingleProviderResolves(context, reload, YAML_RULE_SET, USER_RULE_SET);
                assertThat(context.getBean("delegateRuleSetProvider"))
                    .isInstanceOf(CompositeRuleSetProvider.class);
                // the user's delegate replaces the Mongo provider
                assertThat(context.getBean(RateLimitRuleSetProvider.class).findById(MONGO_RULE_SET))
                    .isEmpty();
              });
    }

    @ParameterizedTest(name = "reload={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("with Mongo disabled: YAML is composed with the user delegate, not dropped")
    void mongoDisabled(boolean reload) {
      baseRunner
          .withUserConfiguration(UserDelegateConfig.class)
          .withPropertyValues(YAML_RULE_SET_PROPERTIES)
          .withPropertyValues(reloadProperties(reload))
          .run(
              context -> {
                assertSingleProviderResolves(context, reload, YAML_RULE_SET, USER_RULE_SET);
                assertThat(context.getBean("delegateRuleSetProvider"))
                    .isInstanceOf(CompositeRuleSetProvider.class);
              });
    }
  }

  @Nested
  @DisplayName("single rule source")
  class SingleSource {

    @ParameterizedTest(name = "reload={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("pure Mongo: the Mongo provider is used as is")
    void pureMongo(boolean reload) {
      baseRunner
          .withUserConfiguration(StubMongoConfig.class)
          .withPropertyValues("fluxgate.mongo.enabled=true")
          .withPropertyValues(reloadProperties(reload))
          .run(
              context -> {
                assertSingleProviderResolves(context, reload, MONGO_RULE_SET);
                assertThat(context.getBean("delegateRuleSetProvider"))
                    .isNotInstanceOf(CompositeRuleSetProvider.class);
              });
    }

    @ParameterizedTest(name = "reload={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("pure YAML: the properties provider is used")
    void pureYaml(boolean reload) {
      baseRunner
          .withPropertyValues(YAML_RULE_SET_PROPERTIES)
          .withPropertyValues(reloadProperties(reload))
          .run(
              context -> {
                assertSingleProviderResolves(context, reload, YAML_RULE_SET);
                assertThat(context).hasSingleBean(PropertiesRuleSetProvider.class);
              });
    }

    @Test
    @DisplayName("pure YAML with default reload settings: the caching provider wraps the YAML one")
    void pureYamlWithDefaultReload() {
      baseRunner
          .withPropertyValues(YAML_RULE_SET_PROPERTIES)
          .run(
              context -> {
                assertSingleProviderResolves(context, true, YAML_RULE_SET);
                CachingRuleSetProvider caching = context.getBean(CachingRuleSetProvider.class);
                assertThat(caching.getDelegate()).isInstanceOf(PropertiesRuleSetProvider.class);
                assertThat(caching.getDelegate())
                    .isSameAs(context.getBean(PropertiesRuleSetProvider.class));
              });
    }

    /**
     * Mirrors the filter sample: a single application provider under a name other than {@code
     * delegateRuleSetProvider}, no MongoDB, no YAML, hot reload left at its defaults.
     */
    @ParameterizedTest(name = "defaultReload={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("sample-like: a single application provider bean is wrapped by hot reload")
    void singleApplicationProvider(boolean defaultReload) {
      ApplicationContextRunner sampleRunner =
          baseRunner.withUserConfiguration(ApplicationProviderConfig.class);
      if (!defaultReload) {
        sampleRunner = sampleRunner.withPropertyValues(reloadProperties(false));
      }
      sampleRunner.run(
          context -> {
            assertSingleProviderResolves(context, defaultReload, USER_RULE_SET);
            if (defaultReload) {
              assertThat(context.getBean(CachingRuleSetProvider.class).getDelegate())
                  .isSameAs(context.getBean("ruleSetProvider"));
            }
          });
    }
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

  @Configuration(proxyBeanMethods = false)
  static class UserDelegateConfig {

    @Bean(name = "delegateRuleSetProvider")
    RateLimitRuleSetProvider delegateRuleSetProvider() {
      return userProvider();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ApplicationProviderConfig {

    @Bean
    RateLimitRuleSetProvider ruleSetProvider() {
      return userProvider();
    }
  }

  private static RateLimitRuleSetProvider userProvider() {
    RateLimitRuleSet ruleSet =
        RateLimitRuleSet.builder(USER_RULE_SET)
            .rules(
                List.of(
                    RateLimitRule.builder("user-rule")
                        .ruleSetId(USER_RULE_SET)
                        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 7).build())
                        .build()))
            .keyResolver(new LimitScopeKeyResolver())
            .build();
    return id -> USER_RULE_SET.equals(id) ? Optional.of(ruleSet) : Optional.empty();
  }
}
