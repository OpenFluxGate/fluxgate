package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoDatabase;
import java.util.Optional;
import org.fluxgate.adapter.mongo.policy.MongoPolicyRepository;
import org.fluxgate.adapter.mongo.policy.PublishedMongoRuleSetProvider;
import org.fluxgate.core.engine.RateLimitEngine;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.ratelimiter.RateLimiter;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.fluxgate.spring.autoconfigure.FluxgateMongoAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublishedPolicyConfigurationTest {
  @Test
  void namedPublishedProviderReplacesMongoStarterDelegate() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PublishedPolicyConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                FluxgateMongoAutoConfiguration.class,
                org.fluxgate.spring.autoconfigure.FluxgateMetricsCompositeAutoConfiguration.class))
        .withPropertyValues(
            "fluxgate.envoy.published-policies=true",
            "fluxgate.mongo.enabled=true",
            "fluxgate.mongo.event-collection=events")
        .withBean("fluxgateMongoDatabase", MongoDatabase.class, () -> mock(MongoDatabase.class))
        .withBean(
            "fluxgateMongoClient",
            com.mongodb.client.MongoClient.class,
            () -> mock(com.mongodb.client.MongoClient.class))
        .withBean(
            "fluxgateRuleCollection",
            com.mongodb.client.MongoCollection.class,
            () -> mock(com.mongodb.client.MongoCollection.class))
        .withBean(
            "fluxgateEventCollection",
            com.mongodb.client.MongoCollection.class,
            () -> mock(com.mongodb.client.MongoCollection.class))
        .withBean(MongoPolicyRepository.class, () -> repository)
        .withBean(org.fluxgate.core.key.KeyResolver.class, LimitScopeKeyResolver::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean("delegateRuleSetProvider"))
                  .isInstanceOf(PublishedMongoRuleSetProvider.class);
              assertThat(context.getBeansOfType(PublishedMongoRuleSetProvider.class)).hasSize(1);
              var writer = context.getBean(MongoTelemetryDispatcher.class);
              assertThat(
                      context
                          .getSourceApplicationContext()
                          .getBeanFactory()
                          .getDependentBeans("fluxgateMongoClient"))
                  .contains("mongoTelemetryDispatcher");
              var rule =
                  new org.bson.Document("id", "test")
                      .append("name", "test")
                      .append("scope", "GLOBAL")
                      .append("keyStrategyId", "global")
                      .append("onLimitExceedPolicy", "REJECT_REQUEST")
                      .append("ruleSetId", "test")
                      .append(
                          "bands",
                          java.util.List.of(
                              new org.bson.Document("windowSeconds", 60L)
                                  .append("capacity", 5L)
                                  .append("algorithm", "TOKEN_BUCKET")));
              when(repository.findActive("test"))
                  .thenReturn(
                      java.util.Optional.of(
                          new org.bson.Document("revision", 1L)
                              .append("counterEpoch", "epoch")
                              .append("rules", java.util.List.of(rule))
                              .append("accessControl", new org.bson.Document())));
              var published = context.getBean(PublishedMongoRuleSetProvider.class);
              var first = published.findById("test").orElseThrow().getMetricsRecorder();
              var second = published.findById("test").orElseThrow().getMetricsRecorder();
              assertThat(first).isSameAs(second);
              var primary =
                  context.getBean(org.fluxgate.core.metrics.RateLimitMetricsRecorder.class);
              assertThat(primary)
                  .isInstanceOf(org.fluxgate.core.metrics.CompositeMetricsRecorder.class);
              assertThat(
                      context.getBeansOfType(
                          org.fluxgate.core.metrics.RateLimitMetricsRecorder.class))
                  .hasSize(2);
              assertThat(writer.adapt(primary)).isSameAs(writer.adapt(primary));
            });
  }

  @Test
  void disabledConfigurationAddsNoProvider() {
    new ApplicationContextRunner()
        .withUserConfiguration(PublishedPolicyConfiguration.class)
        .withPropertyValues("fluxgate.envoy.published-policies=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean("delegateRuleSetProvider");
              assertThat(context).doesNotHaveBean(MongoTelemetryDispatcher.class);
            });
  }

  @Test
  void missingActivePolicyFailsClosedWithoutCallingLimiter() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    when(repository.findActive("missing")).thenReturn(Optional.empty());
    RateLimitRuleSetProvider provider =
        new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
    RateLimiter limiter = mock(RateLimiter.class);
    RateLimitEngine engine =
        RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(limiter)
            .onMissingRuleSetStrategy(RateLimitEngine.OnMissingRuleSetStrategy.DENY)
            .build();
    assertThat(
            engine
                .check(
                    "missing",
                    org.fluxgate.core.context.RequestContext.builder()
                        .clientIp("127.0.0.1")
                        .build(),
                    1)
                .isAllowed())
        .isFalse();
    verifyNoInteractions(limiter);
  }
}
