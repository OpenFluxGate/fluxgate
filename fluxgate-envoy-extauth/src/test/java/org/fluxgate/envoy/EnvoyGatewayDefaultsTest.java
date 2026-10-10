package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fluxgate.spring.properties.FluxgateResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class EnvoyGatewayDefaultsTest {
  @Test
  void packagedGatewayPolicyBoundsRecoveryWithoutWeakeningFailureHandling() throws Exception {
    var sources =
        new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yml"));
    Binder binder = new Binder(ConfigurationPropertySources.from(sources));
    FluxgateResilienceProperties resilience =
        binder.bind("fluxgate.resilience", Bindable.of(FluxgateResilienceProperties.class)).get();
    assertThat(resilience.getCircuitBreaker().isEnabled()).isTrue();
    assertThat(resilience.getCircuitBreaker().getWaitDurationInOpenState())
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(resilience.getCircuitBreaker().getPermittedCallsInHalfOpenState()).isEqualTo(3);
    assertThat(resilience.getCircuitBreaker().getMinimumNumberOfCalls()).isEqualTo(10);
    assertThat(resilience.getRetry().isEnabled()).isFalse();
    assertThat(binder.bind("fluxgate.ratelimit.failure-behavior", String.class).get())
        .isEqualTo("DENY");
    assertThat(binder.bind("fluxgate.ratelimit.missing-rule-behavior", String.class).get())
        .isEqualTo("DENY");
    assertThat(binder.bind("fluxgate.ratelimit.missing-key-behavior", String.class).get())
        .isEqualTo("REJECT");
    assertThat(binder.bind("fluxgate.ratelimit.fallback.mode", String.class).get())
        .isEqualTo("NONE");
    assertThat(binder.bind("fluxgate.ratelimit.mode", String.class).get()).isEqualTo("REDIS");
  }
}
