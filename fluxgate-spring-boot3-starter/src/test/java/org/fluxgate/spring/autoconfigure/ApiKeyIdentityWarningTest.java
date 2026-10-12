package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Item 12: PER_API_KEY rules with an identity source that never reads the API key header quietly
 * limit by IP instead; say so at startup.
 */
class ApiKeyIdentityWarningTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateRateLimiterAutoConfiguration.class))
          .withPropertyValues(
              "fluxgate.ratelimit.mode=IN_MEMORY",
              "fluxgate.ratelimit.rule-sets[0].id=api",
              "fluxgate.ratelimit.rule-sets[0].rules[0].id=per-key",
              "fluxgate.ratelimit.rule-sets[0].rules[0].scope=PER_API_KEY",
              "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].capacity=10",
              "fluxgate.ratelimit.rule-sets[0].rules[0].bands[0].window=1m");

  private Logger logger;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attach() {
    logger = (Logger) LoggerFactory.getLogger(FluxgateRateLimiterAutoConfiguration.class);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  private List<String> warnings() {
    return appender.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains("PER_API_KEY"))
        .collect(Collectors.toList());
  }

  @Test
  void warnsWithTheDefaultPrincipalIdentity() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(warnings()).hasSize(1);
          assertThat(warnings().get(0)).contains("per-key").contains("PRINCIPAL");
        });
  }

  @Test
  void staysQuietWhenTheApiKeyHeaderIsRead() {
    runner
        .withPropertyValues("fluxgate.ratelimit.identity.source=HEADERS")
        .run(context -> assertThat(warnings()).isEmpty());
  }
}
