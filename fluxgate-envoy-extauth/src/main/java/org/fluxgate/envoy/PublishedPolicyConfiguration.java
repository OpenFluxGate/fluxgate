package org.fluxgate.envoy;

import com.mongodb.client.MongoDatabase;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.fluxgate.adapter.mongo.policy.PublishedMongoRuleSetProvider;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects authoritative published snapshots instead of mutable draft rules. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "fluxgate.envoy.published-policies", havingValue = "true")
public class PublishedPolicyConfiguration {
  @Bean
  public MongoTelemetryDispatcher mongoTelemetryDispatcher(FluxgateMongoClientHolder clientHolder) {
    // Dependency keeps the isolated FluxGate client alive until dispatcher lifecycle drain
    // completes.
    return new MongoTelemetryDispatcher();
  }

  @Bean
  @ConditionalOnMissingBean(MongoPolicyRepository.class)
  public MongoPolicyRepository mongoPolicyRepository(
      @Qualifier("fluxgateMongoDatabase") MongoDatabase database,
      @Value("${fluxgate.mongo.rule-collection:rate_limit_rules}") String rulesCollection) {
    return new MongoPolicyRepository(database, rulesCollection);
  }

  @Bean(name = "delegateRuleSetProvider")
  @ConditionalOnMissingBean(name = "delegateRuleSetProvider")
  public PublishedMongoRuleSetProvider publishedRuleSetProvider(
      MongoPolicyRepository repository,
      KeyResolver keyResolver,
      ObjectProvider<RateLimitMetricsRecorder> metrics,
      MongoTelemetryDispatcher dispatcher) {
    return new PublishedMongoRuleSetProvider(
        repository, keyResolver, () -> dispatcher.adapt(metrics.getIfAvailable()));
  }
}
