package org.fluxgate.spring.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

/** Item 12: values that cannot work fail the startup instead of misbehaving at runtime. */
class FluxgatePropertiesValidationTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void doesNotRequireABeanValidationProvider() {
    // @Validated would make Spring Boot bootstrap Bean Validation as soon as the API is on the
    // classpath (springdoc brings it), failing startup when no provider is present. The class
    // validates itself, which Spring Boot applies without the annotation.
    assertThat(FluxgateProperties.class.isAnnotationPresent(Validated.class)).isFalse();
    assertThat(new FluxgateProperties()).isInstanceOf(Validator.class);
  }

  @Test
  void defaultsAreValid() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fluxgate.ratelimit.wait-for-refill.max-wait-time-ms=-1",
        "fluxgate.ratelimit.wait-for-refill.max-concurrent-waits=0",
        "fluxgate.redis.timeout-ms=0",
        "fluxgate.redis.max-bucket-ttl=0s",
        "fluxgate.ratelimit.fallback.max-buckets=0",
        "fluxgate.ratelimit.fallback.expire-after-access=-1s",
        "fluxgate.reload.cache.ttl=0s",
        "fluxgate.reload.cache.max-size=0",
        "fluxgate.reload.cache.negative-ttl=-1s",
        "fluxgate.reload.polling.interval=0s",
        "fluxgate.reload.polling.initial-delay=-1s",
        "fluxgate.reload.pubsub.retry-interval=0s",
        "fluxgate.reload.pubsub.backstop-polling-interval=-1s",
        "fluxgate.reload.pubsub.max-message-age=0s"
      })
  void rejectsAnUnusableValue(String property) {
    String name = property.substring(0, property.indexOf('='));
    runner
        .withPropertyValues(property)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .rootCause()
                  .hasMessageContaining(name.substring(name.lastIndexOf('.') + 1));
            });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fluxgate.ratelimit.wait-for-refill.max-wait-time-ms=0",
        "fluxgate.reload.cache.negative-ttl=0s",
        "fluxgate.reload.pubsub.backstop-polling-interval=0s",
        "fluxgate.reload.polling.initial-delay=0s",
        "fluxgate.mongo.event-retention=0s"
      })
  void acceptsDocumentedZeroValues(String property) {
    runner.withPropertyValues(property).run(context -> assertThat(context).hasNotFailed());
  }
}
