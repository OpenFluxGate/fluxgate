package org.fluxgate.spring.autoconfigure;

import java.util.List;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.RuleSetProperties;
import org.fluxgate.spring.rule.CompositeRuleSetProvider;
import org.fluxgate.spring.rule.PropertiesRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Composes the YAML rule sets ({@code fluxgate.ratelimit.rule-sets}) with the bean named {@value
 * #DELEGATE_BEAN_NAME}, whoever defined it — the MongoDB auto-configuration or the application.
 *
 * <p>The delegate is replaced by a {@link CompositeRuleSetProvider} that tries the YAML rule sets
 * first and falls back to the original delegate. The composite keeps the delegate's bean name, so
 * it stays the single rule-set provider and hot reload (which wraps {@value #DELEGATE_BEAN_NAME})
 * caches and reloads both sources. Without this, a delegate bean made the standalone {@code
 * propertiesRuleSetProvider} back off and the YAML rule sets were silently ignored.
 *
 * <p><b>Injection type.</b> The bean registered under {@value #DELEGATE_BEAN_NAME} is a {@link
 * CompositeRuleSetProvider} once YAML rule sets are configured, no matter what the application
 * defined. Inject the delegate as {@link RateLimitRuleSetProvider}; injecting it by its concrete
 * class fails with a {@code BeanNotOfRequiredTypeException}.
 *
 * <p>Only registered when YAML rule sets are configured.
 *
 * @since 0.4.0
 */
final class YamlRuleSetComposer implements BeanPostProcessor {

  /**
   * Name of the rule-set provider bean that hot reload and the engine wiring treat as the source.
   */
  static final String DELEGATE_BEAN_NAME = "delegateRuleSetProvider";

  private static final Logger log = LoggerFactory.getLogger(YamlRuleSetComposer.class);

  private final ObjectProvider<FluxgateProperties> properties;
  private final ObjectProvider<KeyResolver> keyResolver;

  YamlRuleSetComposer(
      ObjectProvider<FluxgateProperties> properties, ObjectProvider<KeyResolver> keyResolver) {
    this.properties = properties;
    this.keyResolver = keyResolver;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
    if (!DELEGATE_BEAN_NAME.equals(beanName)) {
      return bean;
    }
    if (!(bean instanceof RateLimitRuleSetProvider)) {
      throw new IllegalStateException(
          "fluxgate.ratelimit.rule-sets is configured but bean '"
              + DELEGATE_BEAN_NAME
              + "' is a "
              + bean.getClass().getName()
              + ", not a RateLimitRuleSetProvider, so the YAML rule sets cannot be composed with"
              + " it. Rename the bean or remove fluxgate.ratelimit.rule-sets.");
    }
    if (bean instanceof PropertiesRuleSetProvider) {
      return bean;
    }

    List<RuleSetProperties> ruleSets = properties.getObject().getRatelimit().getRuleSets();
    if (ruleSets == null || ruleSets.isEmpty()) {
      return bean;
    }
    PropertiesRuleSetProvider yaml =
        new PropertiesRuleSetProvider(ruleSets, keyResolver.getObject());
    log.info(
        "Composing {} YAML rule set(s) {} with '{}' ({}) as fallback. Bean '{}' is now a {}; inject"
            + " it as RateLimitRuleSetProvider, not as {}",
        yaml.size(),
        yaml.ruleSetIds(),
        DELEGATE_BEAN_NAME,
        bean.getClass().getName(),
        DELEGATE_BEAN_NAME,
        CompositeRuleSetProvider.class.getSimpleName(),
        bean.getClass().getName());
    return new CompositeRuleSetProvider(yaml, (RateLimitRuleSetProvider) bean);
  }
}
