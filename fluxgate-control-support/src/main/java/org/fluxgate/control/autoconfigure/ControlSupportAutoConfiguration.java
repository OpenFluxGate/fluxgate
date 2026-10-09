package org.fluxgate.control.autoconfigure;

import org.fluxgate.control.aop.RuleChangeAspect;
import org.fluxgate.control.notify.RedisRuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifierMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Auto-configuration for FluxGate Control Support.
 *
 * <p>Automatically configures:
 *
 * <ul>
 *   <li>{@link RuleChangeNotifier} - Redis-based notifier for broadcasting rule changes
 *   <li>{@link RuleChangeAspect} - AOP aspect for {@code @NotifyRuleChange} and
 *       {@code @NotifyFullReload} annotations
 * </ul>
 *
 * <p>Example usage in application.yml:
 *
 * <pre>
 * fluxgate:
 *   control:
 *     redis:
 *       uri: redis://localhost:6379
 *       channel: fluxgate:rule-reload
 * </pre>
 *
 * <p>Example usage with annotations:
 *
 * <pre>{@code
 * @Service
 * public class RuleManagementService {
 *
 *     @NotifyRuleChange(ruleSetId = "#ruleSetId")
 *     public void updateRule(String ruleSetId, RuleDto dto) {
 *         mongoRepository.save(dto);
 *     }
 *
 *     @NotifyFullReload
 *     public void deleteAllRules() {
 *         mongoRepository.deleteAll();
 *     }
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.lettuce.core.RedisClient")
@ConditionalOnProperty(prefix = "fluxgate.control.redis", name = "uri")
@EnableConfigurationProperties(ControlSupportProperties.class)
@EnableAspectJAutoProxy
public class ControlSupportAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ControlSupportAutoConfiguration.class);

  /**
   * Creates the Redis-based rule change notifier.
   *
   * @param properties the configuration properties
   * @return the notifier instance
   */
  @Bean
  @ConditionalOnMissingBean(RuleChangeNotifier.class)
  public RuleChangeNotifier ruleChangeNotifier(ControlSupportProperties properties) {
    ControlSupportProperties.RedisProperties redis = properties.getRedis();
    requireSigningSecret(properties);

    log.info(
        "Creating RedisRuleChangeNotifier: uri={}, channel={}, source={}",
        redis.getUri(),
        redis.getChannel(),
        properties.getSource());

    return new RedisRuleChangeNotifier(
        redis.getUri(),
        redis.getChannel(),
        redis.getTimeout(),
        properties.getSource(),
        properties.getSecret());
  }

  /**
   * H-3: refuses to publish unsigned notifications unless the operator opted in.
   *
   * <p>A data plane that accepts unsigned messages obeys anyone who can {@code PUBLISH} to the
   * channel, so publishing unsigned is only ever right in development. Since 0.4 a missing secret
   * is a startup error; {@code fluxgate.control.allow-unsigned=true} is the explicit escape hatch.
   *
   * <p>Failing fast is right here, unlike the data plane's {@code AUTO} fallback to polling: this
   * notifier only exists when {@code fluxgate.control.redis.uri} is set explicitly, which is a
   * deliberate request to publish. Redis being enabled for the data plane never creates it.
   */
  private static void requireSigningSecret(ControlSupportProperties properties) {
    String secret = properties.getSecret();
    if (secret != null && !secret.trim().isEmpty()) {
      return;
    }
    if (!properties.isAllowUnsigned()) {
      throw new IllegalStateException(
          "fluxgate.control.secret is not set. Rule change notifications must be signed so the"
              + " data plane can tell them from a forged PUBLISH that resets every token bucket."
              + " Set fluxgate.control.secret to the same value as fluxgate.reload.pubsub.secret"
              + " on the data plane, or - for development only - set"
              + " fluxgate.control.allow-unsigned=true.");
    }
    log.warn(
        "fluxgate.control.allow-unsigned=true and no fluxgate.control.secret: rule change"
            + " notifications on channel '{}' are published UNSIGNED. Only a data plane with"
            + " fluxgate.reload.pubsub.allow-unsigned=true accepts them. Do not run this in"
            + " production.",
        properties.getRedis().getChannel());
  }

  /**
   * Creates the notification counters.
   *
   * <p>{@code getFailedNotifications()} is the number worth alerting on: every increment is a rule
   * change that no data plane instance was told about.
   *
   * @return the metrics instance
   */
  @Bean
  @ConditionalOnMissingBean(RuleChangeNotifierMetrics.class)
  public RuleChangeNotifierMetrics ruleChangeNotifierMetrics() {
    return new RuleChangeNotifierMetrics();
  }

  /**
   * Creates the AOP aspect for rule change annotations.
   *
   * <p>Only created when:
   *
   * <ul>
   *   <li>AspectJ is on the classpath
   *   <li>A {@link RuleChangeNotifier} bean exists
   * </ul>
   *
   * @param notifier the rule change notifier
   * @param metrics counters for notification outcomes
   * @return the aspect instance
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
  @ConditionalOnBean(RuleChangeNotifier.class)
  @ConditionalOnMissingBean(RuleChangeAspect.class)
  public RuleChangeAspect ruleChangeAspect(
      RuleChangeNotifier notifier, RuleChangeNotifierMetrics metrics) {
    log.info("Creating RuleChangeAspect for @NotifyRuleChange and @NotifyFullReload support");
    return new RuleChangeAspect(notifier, metrics);
  }
}
