package org.fluxgate.spring.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.fluxgate.core.handler.FluxgateRateLimitHandler;
import org.fluxgate.spring.annotation.RateLimit;
import org.fluxgate.spring.aop.RateLimitAspect;
import org.fluxgate.spring.aop.RateLimitExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Item 9: a filter or aspect without a default rule set id under {@code missing-rule-behavior=DENY}
 * rejects everything. That is reported once, at ERROR, instead of a WARN line per request.
 */
class MissingRuleSetIdLoggingTest {

  private ListAppender<ILoggingEvent> appender;
  private Logger logger;

  private ListAppender<ILoggingEvent> capture(Class<?> type) {
    logger = (Logger) LoggerFactory.getLogger(type);
    logger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  @AfterEach
  void detach() {
    if (logger != null) {
      logger.detachAppender(appender);
      logger.setLevel(null);
    }
  }

  private List<ILoggingEvent> at(Level level) {
    return appender.list.stream().filter(e -> e.getLevel() == level).collect(Collectors.toList());
  }

  private static FluxgateRateLimitFilter filter(boolean deny) {
    return new FluxgateRateLimitFilter(
        mock(FluxgateRateLimitHandler.class),
        "",
        new String[] {"/**"},
        new String[0],
        false,
        0,
        1,
        false,
        deny,
        true,
        false,
        null,
        0L,
        new RequestContextFactory("X-Forwarded-For", false, null, false, null, null),
        new RateLimitHeaderWriter(true, true),
        new ProblemDetailRateLimitResponseWriter(),
        null);
  }

  private static void request(FluxgateRateLimitFilter filter) throws Exception {
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api"),
        new MockHttpServletResponse(),
        new MockFilterChain());
  }

  @Test
  void filterReportsAMissingRuleSetIdOnceAtError() throws Exception {
    capture(FluxgateRateLimitFilter.class);
    FluxgateRateLimitFilter filter = filter(true);

    for (int i = 0; i < 5; i++) {
      request(filter);
    }

    assertThat(at(Level.ERROR)).hasSize(1);
    assertThat(at(Level.ERROR).get(0).getFormattedMessage())
        .contains("default-rule-set-id")
        .contains("503");
    assertThat(at(Level.WARN)).isEmpty();
  }

  @Test
  void filterReportsASkippedFilterOnceAtWarn() throws Exception {
    capture(FluxgateRateLimitFilter.class);
    FluxgateRateLimitFilter filter = filter(false);

    for (int i = 0; i < 5; i++) {
      request(filter);
    }

    assertThat(at(Level.WARN)).hasSize(1);
    assertThat(at(Level.ERROR)).isEmpty();
  }

  @Test
  void aspectReportsAMissingRuleSetIdOnceAtError() throws Throwable {
    capture(RateLimitAspect.class);
    RateLimitAspect aspect =
        new RateLimitAspect(
            mock(FluxgateRateLimitHandler.class), null, "X-Forwarded-For", false, false, "", true);
    RateLimit rateLimit = mock(RateLimit.class);
    when(rateLimit.ruleSetId()).thenReturn("");
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    Signature signature = mock(Signature.class);
    when(signature.getDeclaringType()).thenReturn(MissingRuleSetIdLoggingTest.class);
    when(signature.getName()).thenReturn("call");
    when(joinPoint.getSignature()).thenReturn(signature);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> aspect.aroundMethod(joinPoint, rateLimit))
          .isInstanceOf(RateLimitExceededException.class);
    }

    assertThat(at(Level.ERROR)).hasSize(1);
    assertThat(at(Level.WARN)).isEmpty();
  }
}
