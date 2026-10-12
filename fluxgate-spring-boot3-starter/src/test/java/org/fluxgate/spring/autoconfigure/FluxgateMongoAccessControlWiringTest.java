package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.Document;
import org.fluxgate.adapter.mongo.spi.RuleSetAccessControlSource;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.actuator.FluxgateHealthIndicator.MongoHealthChecker;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Mongo rule set provider must load rule-set access control from a {@link
 * RuleSetAccessControlSource} bean when the repository bean is a decorator that does not implement
 * the SPI, and must say so at WARN when no source is available at all.
 */
class FluxgateMongoAccessControlWiringTest {

  private static final AccessControl ACL =
      AccessControl.builder().addAllowedKey("partner-key").build();

  /** A repository decorator (caching, metrics, ...) that does not implement the SPI. */
  static final class DecoratedRepository implements RateLimitRuleRepository {
    @Override
    public List<RateLimitRule> findByRuleSetId(String ruleSetId) {
      return List.of(
          RateLimitRule.builder("r1")
              .name("rule")
              .ruleSetId(ruleSetId)
              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("m").build())
              .build());
    }

    @Override
    public Optional<RateLimitRule> findById(String id) {
      return Optional.empty();
    }

    @Override
    public List<RateLimitRule> findAll() {
      return List.of();
    }

    @Override
    public void save(RateLimitRule rule) {}

    @Override
    public boolean deleteById(String id) {
      return false;
    }

    @Override
    public int deleteByRuleSetId(String ruleSetId) {
      return 0;
    }
  }

  /** Replaces the Mongo infrastructure beans, so no server is needed. */
  @Configuration
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class MongoStubs {
    @Bean
    FluxgateMongoClientHolder fluxgateMongoClientHolder() {
      return new FluxgateMongoClientHolder(mock(MongoClient.class), false);
    }

    @Bean(name = "fluxgateMongoDatabase")
    MongoDatabase fluxgateMongoDatabase() {
      return mock(MongoDatabase.class);
    }

    @Bean(name = "fluxgateRuleCollection")
    @SuppressWarnings("unchecked")
    MongoCollection<Document> fluxgateRuleCollection() {
      return mock(MongoCollection.class);
    }

    @Bean
    MongoHealthChecker mongoHealthChecker() {
      return mock(MongoHealthChecker.class);
    }

    @Bean
    RateLimitRuleRepository decoratedRepository() {
      return new DecoratedRepository();
    }
  }

  @Configuration
  static class AccessControlSourceConfig {
    @Bean
    RuleSetAccessControlSource ruleSetAccessControlSource() {
      return ruleSetId -> ACL;
    }
  }

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
          .withUserConfiguration(MongoStubs.class)
          .withPropertyValues("fluxgate.mongo.enabled=true");

  private final Logger logger =
      (Logger) LoggerFactory.getLogger(LazyMetricsMongoRuleSetProvider.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attachAppender() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
  }

  private List<ILoggingEvent> accessControlWarnings() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .filter(e -> e.getFormattedMessage().contains("access control"))
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("decorated repository + RuleSetAccessControlSource bean: the ACL is applied")
  void decoratedRepositoryWithSpiBean_appliesAccessControl() {
    contextRunner
        .withUserConfiguration(AccessControlSourceConfig.class)
        .run(
            context -> {
              RateLimitRuleSetProvider provider =
                  context.getBean("delegateRuleSetProvider", RateLimitRuleSetProvider.class);

              RateLimitRuleSet ruleSet = provider.findById("api").orElseThrow();

              assertThat(ruleSet.getAccessControl()).isEqualTo(ACL);
              assertThat(accessControlWarnings()).isEmpty();
            });
  }

  @Test
  @DisplayName("decorated repository without a source: access control is not loaded, WARN once")
  void decoratedRepositoryWithoutSpi_warnsOnce() {
    contextRunner.run(
        context -> {
          RateLimitRuleSetProvider provider =
              context.getBean("delegateRuleSetProvider", RateLimitRuleSetProvider.class);

          provider.findById("api").orElseThrow();
          provider.findById("other").orElseThrow();

          assertThat(accessControlWarnings()).hasSize(1);
        });
  }
}
