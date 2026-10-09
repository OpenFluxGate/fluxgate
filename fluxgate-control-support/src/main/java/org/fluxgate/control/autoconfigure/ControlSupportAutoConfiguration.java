package org.fluxgate.control.autoconfigure;

import org.fluxgate.control.aop.RuleChangeAspect;
import org.fluxgate.control.notify.RedisRuleChangeNotifier;
import org.fluxgate.control.notify.RuleChangeNotifier;
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

    log.info(
        "Creating RedisRuleChangeNotifier: uri={}, channel={}, source={}",
        redis.getUri(),
        redis.getChannel(),
        properties.getSource());

    return new RedisRuleChangeNotifier(
        redis.getUri(), redis.getChannel(), redis.getTimeout(), properties.getSource());
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
   * @return the aspect instance
   */
  @Bean
  @ConditionalOnClass(name = "org.aspectj.lang.annotation.Aspect")
  @ConditionalOnBean(RuleChangeNotifier.class)
  @ConditionalOnMissingBean(RuleChangeAspect.class)
  public RuleChangeAspect ruleChangeAspect(RuleChangeNotifier notifier) {
    log.info("Creating RuleChangeAspect for @NotifyRuleChange and @NotifyFullReload support");
    return new RuleChangeAspect(notifier);
  }
}
