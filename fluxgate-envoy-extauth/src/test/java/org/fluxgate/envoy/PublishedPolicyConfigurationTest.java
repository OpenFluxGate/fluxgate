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
        .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
        .withPropertyValues("fluxgate.envoy.published-policies=true", "fluxgate.mongo.enabled=true")
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
            });
  }

  @Test
  void disabledConfigurationAddsNoProvider() {
    new ApplicationContextRunner()
        .withUserConfiguration(PublishedPolicyConfiguration.class)
        .withPropertyValues("fluxgate.envoy.published-policies=false")
        .run(context -> assertThat(context).doesNotHaveBean("delegateRuleSetProvider"));
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
