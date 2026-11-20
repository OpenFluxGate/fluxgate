package org.fluxgate.testkit.junit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.testkit.support.InMemoryRateLimitHandler;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension that hands each test an {@link InMemoryRateLimitHandler} with full buckets.
 *
 * <p>Rate limit tests share a failure mode: the second test method inherits the tokens the first
 * one spent, so the suite passes in isolation and fails in order. This extension removes that class
 * of bug by resetting every bucket before each test, and it removes the boilerplate by injecting
 * the handler as a test parameter.
 *
 * <pre>{@code
 * class RateLimitTest {
 *
 *   @RegisterExtension
 *   static final FluxgateInMemoryExtension fluxgate =
 *       FluxgateInMemoryExtension.withRuleSet(
 *           FluxgateTestRules.rule("per-ip")
 *               .perIp()
 *               .band(Duration.ofMinutes(1), 2)
 *               .toRuleSet("api-limits"));
 *
 *   @Test
 *   void allowsUpToCapacity(InMemoryRateLimitHandler handler) {
 *     RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();
 *     RateLimitAssertions.assertAllowed(handler.tryConsume(ctx, "api-limits"));
 *     RateLimitAssertions.assertAllowed(handler.tryConsume(ctx, "api-limits"));
 *     RateLimitAssertions.assertRejected(handler.tryConsume(ctx, "api-limits"));
 *   }
 *
 *   @Test
 *   void startsFreshInTheNextTest(InMemoryRateLimitHandler handler) {
 *     RequestContext ctx = RequestContext.builder().clientIp("10.0.0.1").build();
 *     RateLimitAssertions.assertAllowed(handler.tryConsume(ctx, "api-limits"));
 *   }
 * }
 * }</pre>
 *
 * <p>Register it with {@code @RegisterExtension} rather than {@code @ExtendWith}, because the rule
 * sets are constructor arguments. A {@code static} field shares one handler (and so one bucket
 * cache) across the class, which is what you want: the reset between tests is what provides the
 * isolation. An instance field creates a handler per test, which also works and costs a little
 * more.
 *
 * <p>The handler is also reachable outside a parameter through {@link #getHandler()}, for a
 * {@code @BeforeEach} that needs to warm buckets up.
 *
 * @see InMemoryRateLimitHandler
 * @see org.fluxgate.testkit.support.FluxgateTestRules
 * @see org.fluxgate.testkit.support.RateLimitAssertions
 */
public final class FluxgateInMemoryExtension implements BeforeEachCallback, ParameterResolver {

  private final InMemoryRateLimitHandler handler;

  private FluxgateInMemoryExtension(InMemoryRateLimitHandler handler) {
    this.handler = handler;
  }

  /**
   * Creates an extension serving one rule set.
   *
   * @param ruleSet the rule set (must not be null)
   * @return the extension
   */
  public static FluxgateInMemoryExtension withRuleSet(RateLimitRuleSet ruleSet) {
    return new FluxgateInMemoryExtension(InMemoryRateLimitHandler.withRuleSet(ruleSet));
  }

  /**
   * Creates an extension serving several rule sets.
   *
   * @param ruleSets the rule sets (must not be null and must not be empty)
   * @return the extension
   */
  public static FluxgateInMemoryExtension withRuleSets(RateLimitRuleSet... ruleSets) {
    Objects.requireNonNull(ruleSets, "ruleSets must not be null");
    Collection<RateLimitRuleSet> list = new ArrayList<>(List.of(ruleSets));
    return new FluxgateInMemoryExtension(InMemoryRateLimitHandler.withRuleSets(list));
  }

  /**
   * Creates an extension around a handler you built yourself, for example with a non-default {@link
   * RateLimitEngine.OnMissingRuleSetStrategy}.
   *
   * @param handler the handler the extension resets and injects (must not be null)
   * @return the extension
   */
  public static FluxgateInMemoryExtension withHandler(InMemoryRateLimitHandler handler) {
    return new FluxgateInMemoryExtension(
        Objects.requireNonNull(handler, "handler must not be null"));
  }

  /**
   * Returns the handler this extension injects.
   *
   * @return the handler, never null
   */
  public InMemoryRateLimitHandler getHandler() {
    return handler;
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    handler.reset();
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType() == InMemoryRateLimitHandler.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    return handler;
  }
}
