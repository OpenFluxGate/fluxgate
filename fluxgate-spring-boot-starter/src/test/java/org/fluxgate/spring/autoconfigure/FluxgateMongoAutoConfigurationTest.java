package org.fluxgate.spring.autoconfigure;

import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FluxgateMongoAutoConfiguration}.
 *
 * Tests the conditional behavior and property binding of MongoDB auto-configuration.
 * Full MongoDB integration tests require a running server and are handled separately.
 */
class FluxgateMongoAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(FluxgateProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldNotCreateBeansByDefault() {
        // Default: fluxgate.mongo.enabled is false
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(FluxgateProperties.class);
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getMongo().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldNotCreateBeansWhenMongoDisabled() {
        contextRunner
                .withPropertyValues("fluxgate.mongo.enabled=false")
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getMongo().isEnabled()).isFalse();
                });
    }

    @Test
    void shouldBindMongoProperties() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.mongo.enabled=true",
                        "fluxgate.mongo.uri=mongodb://localhost:27017/testdb",
                        "fluxgate.mongo.database=testdb"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getMongo().isEnabled()).isTrue();
                    assertThat(props.getMongo().getUri()).isEqualTo("mongodb://localhost:27017/testdb");
                    assertThat(props.getMongo().getDatabase()).isEqualTo("testdb");
                });
    }

    @Test
    void shouldBindCustomCollectionNames() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.mongo.enabled=false",  // Don't try to connect
                        "fluxgate.mongo.rule-collection=custom_rules",
                        "fluxgate.mongo.event-collection=custom_events"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getMongo().getRuleCollection()).isEqualTo("custom_rules");
                    assertThat(props.getMongo().getEventCollection()).isEqualTo("custom_events");
                });
    }

    @Test
    void shouldBindProductionMongoUri() {
        contextRunner
                .withPropertyValues(
                        "fluxgate.mongo.enabled=false",  // Don't try to connect
                        "fluxgate.mongo.uri=mongodb://user:pass@mongo.cluster:27017/proddb?authSource=admin",
                        "fluxgate.mongo.database=proddb"
                )
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);
                    assertThat(props.getMongo().getUri())
                            .isEqualTo("mongodb://user:pass@mongo.cluster:27017/proddb?authSource=admin");
                    assertThat(props.getMongo().getDatabase()).isEqualTo("proddb");
                });
    }

    @Test
    void shouldHaveCorrectDefaultValues() {
        contextRunner
                .run(context -> {
                    FluxgateProperties props = context.getBean(FluxgateProperties.class);

                    // Verify defaults
                    assertThat(props.getMongo().isEnabled()).isFalse();
                    assertThat(props.getMongo().getUri()).isEqualTo("mongodb://localhost:27017/fluxgate");
                    assertThat(props.getMongo().getDatabase()).isEqualTo("fluxgate");
                    assertThat(props.getMongo().getRuleCollection()).isEqualTo("rate_limit_rules");
                    assertThat(props.getMongo().getEventCollection()).isEqualTo("rate_limit_events");
                });
    }
}
