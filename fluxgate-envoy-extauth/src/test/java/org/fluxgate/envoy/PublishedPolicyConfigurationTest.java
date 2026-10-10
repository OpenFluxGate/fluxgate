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
            "fluxgateMongoClientHolder",
            org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder.class,
            () ->
                new org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder(
                    mock(com.mongodb.client.MongoClient.class), false))
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
              assertThat(context.getBeansOfType(com.mongodb.client.MongoClient.class)).isEmpty();
              assertThat(context.getBean("delegateRuleSetProvider"))
                  .isInstanceOf(PublishedMongoRuleSetProvider.class);
              assertThat(context.getBeansOfType(PublishedMongoRuleSetProvider.class)).hasSize(1);
              var writer = context.getBean(MongoTelemetryDispatcher.class);
              assertThat(
                      context
                          .getSourceApplicationContext()
                          .getBeanFactory()
                          .getDependentBeans("fluxgateMongoClientHolder"))
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
              // LTS aliases a sole recorder as primary rather than wrapping it in a composite.
              assertThat(primary)
                  .isInstanceOf(
                      org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder.class)
                  .isSameAs(context.getBean("mongoMetricsRecorder"))
                  .isSameAs(context.getBean("compositeMetricsRecorder"));
              assertThat(
                      context.getBeansOfType(
                          org.fluxgate.core.metrics.RateLimitMetricsRecorder.class))
                  .containsOnlyKeys("mongoMetricsRecorder", "compositeMetricsRecorder");
              assertThat(writer.adapt(primary)).isSameAs(first).isNotSameAs(primary);
            });
  }

  @Test
  void publishedDispatcherUsesHolderWithoutTakingApplicationMongoClient() {
    var applicationClient = mock(com.mongodb.client.MongoClient.class);
    var fluxgateClient = mock(com.mongodb.client.MongoClient.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PublishedPolicyConfiguration.class)
        .withPropertyValues("fluxgate.envoy.published-policies=true")
        .withBean(
            "applicationMongoClient",
            com.mongodb.client.MongoClient.class,
            () -> applicationClient,
            definition -> definition.setDestroyMethodName(""))
        .withBean(
            "fluxgateMongoClientHolder",
            org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder.class,
            () ->
                new org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder(
                    fluxgateClient, true))
        .withBean(MongoPolicyRepository.class, () -> mock(MongoPolicyRepository.class))
        .withBean(org.fluxgate.core.key.KeyResolver.class, LimitScopeKeyResolver::new)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(com.mongodb.client.MongoClient.class))
                  .containsOnlyKeys("applicationMongoClient");
              assertThat(context.getBean(com.mongodb.client.MongoClient.class))
                  .isSameAs(applicationClient);
              assertThat(context.getBean(MongoTelemetryDispatcher.class)).isNotNull();
              assertThat(
                      context
                          .getSourceApplicationContext()
                          .getBeanFactory()
                          .getDependentBeans("fluxgateMongoClientHolder"))
                  .contains("mongoTelemetryDispatcher");
            });
    verify(fluxgateClient).close();
    verify(applicationClient, never()).close();
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
