package org.fluxgate.control.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.control.aop.RuleChangeAspect;
import org.fluxgate.control.notify.RedisRuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifierMetrics;
import org.fluxgate.core.constants.FluxgateConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link ControlSupportAutoConfiguration}.
 *
 * <p>Nothing here talks to Redis: the notifier connects lazily on the first publish, so the wiring
 * can be asserted without a broker.
 */
@ExtendWith(OutputCaptureExtension.class)
class ControlSupportAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ControlSupportAutoConfiguration.class));

  @Test
  @DisplayName("An application that configures no Redis URI gets no control-plane beans")
  void shouldBackOffWithoutARedisUri() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RuleChangeNotifier.class);
          assertThat(context).doesNotHaveBean(RuleChangeAspect.class);
        });
  }

  @Test
  @DisplayName("A Redis URI wires the notifier, the counters and the aspect")
  void shouldRegisterTheNotifierMetricsAndAspect() {
    runner
        .withPropertyValues(
            "fluxgate.control.redis.uri=redis://localhost:6379",
            "fluxgate.control.secret=unit-test-only-secret")
        .run(
            context -> {
              assertThat(context).hasSingleBean(RuleChangeNotifier.class);
              assertThat(context.getBean(RuleChangeNotifier.class))
                  .isInstanceOf(RedisRuleChangeNotifier.class);
              assertThat(context).hasSingleBean(RuleChangeNotifierMetrics.class);
              assertThat(context).hasSingleBean(RuleChangeAspect.class);
              // The aspect shares the counters, so a lost notification shows up on the bean an
              // operator gauges.
              assertThat(context.getBean(RuleChangeAspect.class).getMetrics())
                  .isSameAs(context.getBean(RuleChangeNotifierMetrics.class));
            });
  }

  @Test
  @DisplayName("Channel, source and timeout default to the values the data plane expects")
  void shouldBindTheDefaultProperties() {
    runner
        .withPropertyValues("fluxgate.control.redis.uri=redis://localhost:6379")
        // An application notifier keeps the context up without a secret, so defaults are visible.
        .withUserConfiguration(CustomNotifierConfiguration.class)
        .run(
            context -> {
              ControlSupportProperties properties = context.getBean(ControlSupportProperties.class);

              assertThat(properties.getRedis().getChannel())
                  .isEqualTo(FluxgateConstants.Channels.RULE_RELOAD);
              assertThat(properties.getRedis().getTimeout()).isEqualTo(Duration.ofSeconds(5));
              assertThat(properties.getSource()).isEqualTo("fluxgate-control");
              assertThat(properties.getSecret()).isNull();
              assertThat(properties.isAllowUnsigned()).isFalse();
            });
  }

  @Test
  @DisplayName("Every property is bound from the environment")
  void shouldBindOverriddenProperties() {
    runner
        .withPropertyValues(
            "fluxgate.control.redis.uri=redis://redis-a:6379,redis://redis-b:6379",
            "fluxgate.control.redis.channel=custom:rule-reload",
            "fluxgate.control.redis.timeout=2s",
            "fluxgate.control.source=studio",
            "fluxgate.control.secret=unit-test-only-secret")
        .run(
            context -> {
              ControlSupportProperties properties = context.getBean(ControlSupportProperties.class);

              assertThat(properties.getRedis().getUri())
                  .isEqualTo("redis://redis-a:6379,redis://redis-b:6379");
              assertThat(properties.getRedis().getChannel()).isEqualTo("custom:rule-reload");
              assertThat(properties.getRedis().getTimeout()).isEqualTo(Duration.ofSeconds(2));
              assertThat(properties.getSource()).isEqualTo("studio");
              assertThat(properties.getSecret()).isEqualTo("unit-test-only-secret");
              assertThat(context).hasSingleBean(RuleChangeNotifier.class);
            });
  }

  @Test
  @DisplayName("H-3: data-plane Redis settings alone never create a notifier or demand a secret")
  void shouldNotRequireASecretWhenOnlyTheDataPlaneUsesRedis() {
    // Fail-fast applies only to an explicitly configured publisher (fluxgate.control.redis.uri).
    runner
        .withPropertyValues(
            "fluxgate.redis.enabled=true",
            "fluxgate.redis.uri=redis://localhost:6379",
            "spring.data.redis.host=localhost")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(RuleChangeNotifier.class);
            });
  }

  @Test
  @DisplayName("H-3: a Redis notifier without a signing secret fails startup")
  void shouldFailStartupWithoutASecret() {
    runner
        .withPropertyValues("fluxgate.control.redis.uri=redis://localhost:6379")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("fluxgate.control.secret")
                  .hasMessageContaining("fluxgate.control.allow-unsigned");
            });
  }

  @Test
  @DisplayName("H-3: a blank secret counts as no secret")
  void shouldFailStartupWithABlankSecret() {
    runner
        .withPropertyValues(
            "fluxgate.control.redis.uri=redis://localhost:6379", "fluxgate.control.secret=  ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("H-3: allow-unsigned=true publishes unsigned and says so at WARN")
  void shouldStartUnsignedOnlyWhenExplicitlyAllowed(CapturedOutput output) {
    runner
        .withPropertyValues(
            "fluxgate.control.redis.uri=redis://localhost:6379",
            "fluxgate.control.allow-unsigned=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(RuleChangeNotifier.class);
              assertThat(
                      ((RedisRuleChangeNotifier) context.getBean(RuleChangeNotifier.class))
                          .isSigning())
                  .isFalse();
            });
    assertThat(output).contains("WARN").contains("fluxgate.control.allow-unsigned=true");
  }

  @Test
  @DisplayName("H-3: a configured secret signs, whatever allow-unsigned says")
  void shouldSignWhenASecretIsConfigured() {
    runner
        .withPropertyValues(
            "fluxgate.control.redis.uri=redis://localhost:6379",
            "fluxgate.control.secret=unit-test-only-secret",
            "fluxgate.control.allow-unsigned=true")
        .run(
            context ->
                assertThat(
                        ((RedisRuleChangeNotifier) context.getBean(RuleChangeNotifier.class))
                            .isSigning())
                    .isTrue());
  }

  @Test
  @DisplayName("An application-provided notifier replaces the Redis one and still feeds the aspect")
  void shouldBackOffWhenTheApplicationDefinesItsOwnNotifier() {
    runner
        .withPropertyValues("fluxgate.control.redis.uri=redis://localhost:6379")
        .withUserConfiguration(CustomNotifierConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RuleChangeNotifier.class);
              assertThat(context.getBean(RuleChangeNotifier.class))
                  .isInstanceOf(NoOpRuleChangeNotifier.class);
              assertThat(context).hasSingleBean(RuleChangeAspect.class);
            });
  }

  /** A notifier an application might define instead of the Redis one. */
  static class NoOpRuleChangeNotifier implements RuleChangeNotifier {

    @Override
    public void notifyChange(String ruleSetId) {}

    @Override
    public void notifyFullReload() {}

    @Override
    public void close() {}
  }

  @Configuration(proxyBeanMethods = false)
  static class CustomNotifierConfiguration {

    @Bean
    RuleChangeNotifier ruleChangeNotifier() {
      return new NoOpRuleChangeNotifier();
    }
  }
}
