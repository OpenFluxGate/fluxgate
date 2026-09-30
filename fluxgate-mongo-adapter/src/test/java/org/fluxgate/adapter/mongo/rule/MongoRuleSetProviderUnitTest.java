package org.fluxgate.adapter.mongo.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mongodb.MongoQueryException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.exception.FluxgateOperationException;
import org.fluxgate.core.exception.MongoConnectionException;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MongoRuleSetProvider}, without a MongoDB instance.
 *
 * <p>The behaviour under test is the fix for "a store failure looks like an empty rule set": a
 * driver error must surface as a FluxGate exception so the configured failure behaviour applies,
 * instead of an empty {@link Optional} that silently lifts rate limiting.
 */
@ExtendWith(MockitoExtension.class)
class MongoRuleSetProviderUnitTest {

  @Mock private RateLimitRuleRepository ruleRepository;

  private KeyResolver keyResolver;
  private MongoRuleSetProvider provider;

  @BeforeEach
  void setUp() {
    keyResolver = (context, rule) -> RateLimitKey.of("ip:10.0.0.1");
    provider = new MongoRuleSetProvider(ruleRepository, keyResolver);
  }

  private static RateLimitRule rule() {
    return RateLimitRule.builder("orders-rule")
        .name("orders")
        .enabled(true)
        .ruleSetId("orders")
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).label("per-minute").build())
        .build();
  }

  @Test
  void shouldReturnTheRuleSetWhenRulesExist() {
    when(ruleRepository.findByRuleSetId("orders")).thenReturn(List.of(rule()));

    Optional<RateLimitRuleSet> result = provider.findById("orders");

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("orders");
    assertThat(result.get().getRules()).hasSize(1);
  }

  @Test
  void shouldReturnEmptyWhenTheQuerySucceedsButFindsNothing() {
    when(ruleRepository.findByRuleSetId("orders")).thenReturn(Collections.emptyList());

    // Called twice: the first empty result warns, the second only logs at DEBUG.
    assertThat(provider.findById("orders")).isEmpty();
    assertThat(provider.findById("orders")).isEmpty();
  }

  @Test
  void shouldWrapAConnectionFailureInMongoConnectionException() {
    when(ruleRepository.findByRuleSetId("orders"))
        .thenThrow(new MongoTimeoutException("no primary available"));

    assertThatThrownBy(() -> provider.findById("orders"))
        .isInstanceOf(MongoConnectionException.class)
        .hasMessageContaining("orders");
  }

  @Test
  void shouldWrapAnyOtherMongoFailureInAnOperationException() {
    when(ruleRepository.findByRuleSetId("orders"))
        .thenThrow(
            new MongoQueryException(new BsonDocument(), new ServerAddress("localhost", 27017)));

    assertThatThrownBy(() -> provider.findById("orders"))
        .isInstanceOf(FluxgateOperationException.class)
        .hasMessageContaining("orders");
  }

  @Test
  void shouldMarkStoreFailuresAsRetryable() {
    when(ruleRepository.findByRuleSetId("orders"))
        .thenThrow(
            new MongoQueryException(new BsonDocument(), new ServerAddress("localhost", 27017)));

    assertThatThrownBy(() -> provider.findById("orders"))
        .isInstanceOf(FluxgateOperationException.class)
        .matches(e -> ((FluxgateOperationException) e).isRetryable());
  }
}
