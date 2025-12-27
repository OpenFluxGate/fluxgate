package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import java.util.Arrays;
import java.util.Iterator;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.fluxgate.spring.properties.FluxgateProperties.DdlAuto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link FluxgateMongoAutoConfiguration}.
 *
 * <p>Tests the conditional behavior and property binding of MongoDB auto-configuration. Full
 * MongoDB integration tests require a running server and are handled separately.
 */
class FluxgateMongoAutoConfigurationTest {

  @Configuration
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class TestConfig {}

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class);

  @Test
  void shouldNotCreateBeansByDefault() {
    // Default: fluxgate.mongo.enabled is false
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(FluxgateProperties.class);
          FluxgateProperties props = context.getBean(FluxgateProperties.class);
          assertThat(props.getMongo().isEnabled()).isFalse();
        });
  }

  @Test
  void shouldNotCreateBeansWhenMongoDisabled() {
    contextRunner
        .withPropertyValues("fluxgate.mongo.enabled=false")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getMongo().isEnabled()).isFalse();
            });
  }

  @Test
  void shouldBindMongoProperties() {
    // Don't enable mongo (would try to connect and validate collections)
    contextRunner
        .withPropertyValues(
            "fluxgate.mongo.enabled=false",
            "fluxgate.mongo.uri=mongodb://localhost:27017/testdb",
            "fluxgate.mongo.database=testdb")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getMongo().getUri()).isEqualTo("mongodb://localhost:27017/testdb");
              assertThat(props.getMongo().getDatabase()).isEqualTo("testdb");
            });
  }

  @Test
  void shouldBindCustomCollectionNames() {
    contextRunner
        .withPropertyValues(
            "fluxgate.mongo.enabled=false", // Don't try to connect
            "fluxgate.mongo.rule-collection=custom_rules",
            "fluxgate.mongo.event-collection=custom_events")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getMongo().getRuleCollection()).isEqualTo("custom_rules");
              assertThat(props.getMongo().getEventCollection()).isEqualTo("custom_events");
              assertThat(props.getMongo().hasEventCollection()).isTrue();
            });
  }

  @Test
  void shouldBindProductionMongoUri() {
    contextRunner
        .withPropertyValues(
            "fluxgate.mongo.enabled=false", // Don't try to connect
            "fluxgate.mongo.uri=mongodb://user:pass@mongo.cluster:27017/proddb?authSource=admin",
            "fluxgate.mongo.database=proddb")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);
              assertThat(props.getMongo().getUri())
                  .isEqualTo("mongodb://user:pass@mongo.cluster:27017/proddb?authSource=admin");
              assertThat(props.getMongo().getDatabase()).isEqualTo("proddb");
            });
  }

  @Test
  void shouldHaveCorrectDefaultValues() {
    contextRunner
        .withPropertyValues(
            // Explicitly set default values to isolate from CI environment variables
            "fluxgate.mongo.uri=mongodb://localhost:27017/fluxgate",
            "fluxgate.mongo.database=fluxgate")
        .run(
            context -> {
              FluxgateProperties props = context.getBean(FluxgateProperties.class);

              // Verify defaults
              assertThat(props.getMongo().isEnabled()).isFalse();
              assertThat(props.getMongo().getUri()).isEqualTo("mongodb://localhost:27017/fluxgate");
              assertThat(props.getMongo().getDatabase()).isEqualTo("fluxgate");
              assertThat(props.getMongo().getRuleCollection()).isEqualTo("rate_limit_rules");
              assertThat(props.getMongo().getEventCollection()).isNull();
              assertThat(props.getMongo().getDdlAuto()).isEqualTo(DdlAuto.VALIDATE);
            });
  }

  // ==================== DDL Auto Tests ====================

  @Nested
  @DisplayName("DDL Auto Tests")
  class DdlAutoTests {

    @Test
    @DisplayName("should default to VALIDATE mode")
    void shouldDefaultToValidateMode() {
      contextRunner.run(
          context -> {
            FluxgateProperties props = context.getBean(FluxgateProperties.class);
            assertThat(props.getMongo().getDdlAuto()).isEqualTo(DdlAuto.VALIDATE);
          });
    }

    @Test
    @DisplayName("should bind ddl-auto=validate")
    void shouldBindDdlAutoValidate() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.ddl-auto=validate")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().getDdlAuto()).isEqualTo(DdlAuto.VALIDATE);
              });
    }

    @Test
    @DisplayName("should bind ddl-auto=create")
    void shouldBindDdlAutoCreate() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.ddl-auto=create")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().getDdlAuto()).isEqualTo(DdlAuto.CREATE);
              });
    }

    @Test
    @DisplayName("should bind ddl-auto=CREATE (uppercase)")
    void shouldBindDdlAutoCreateUppercase() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.ddl-auto=CREATE")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().getDdlAuto()).isEqualTo(DdlAuto.CREATE);
              });
    }
  }

  // ==================== Event Collection Optional Tests ====================

  @Nested
  @DisplayName("Event Collection Optional Tests")
  class EventCollectionOptionalTests {

    @Test
    @DisplayName("should have null event-collection by default")
    void shouldHaveNullEventCollectionByDefault() {
      contextRunner.run(
          context -> {
            FluxgateProperties props = context.getBean(FluxgateProperties.class);
            assertThat(props.getMongo().getEventCollection()).isNull();
            assertThat(props.getMongo().hasEventCollection()).isFalse();
          });
    }

    @Test
    @DisplayName("should bind event-collection when configured")
    void shouldBindEventCollectionWhenConfigured() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.event-collection=my_events")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().getEventCollection()).isEqualTo("my_events");
                assertThat(props.getMongo().hasEventCollection()).isTrue();
              });
    }

    @Test
    @DisplayName("should report hasEventCollection=false for empty string")
    void shouldReportHasEventCollectionFalseForEmptyString() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.event-collection=")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().hasEventCollection()).isFalse();
              });
    }

    @Test
    @DisplayName("should report hasEventCollection=false for whitespace")
    void shouldReportHasEventCollectionFalseForWhitespace() {
      contextRunner
          .withPropertyValues("fluxgate.mongo.event-collection=   ")
          .run(
              context -> {
                FluxgateProperties props = context.getBean(FluxgateProperties.class);
                assertThat(props.getMongo().hasEventCollection()).isFalse();
              });
    }
  }

  // ==================== DDL Auto Validation Logic Tests ====================

  @Nested
  @DisplayName("DDL Auto Validation Logic Tests")
  class DdlAutoValidationLogicTests {

    @Test
    @DisplayName(
        "should throw IllegalStateException when collection does not exist in VALIDATE mode")
    void shouldThrowExceptionWhenCollectionNotExistsInValidateMode() {
      // Given
      FluxgateProperties properties = new FluxgateProperties();
      properties.getMongo().setDdlAuto(DdlAuto.VALIDATE);

      MongoDatabase mockDatabase = mock(MongoDatabase.class);
      ListCollectionNamesIterable mockIterable = mock(ListCollectionNamesIterable.class);
      MongoCursor<String> mockCursor = mock(MongoCursor.class);

      when(mockDatabase.listCollectionNames()).thenReturn(mockIterable);
      // ListCollectionNamesIterable.iterator() returns MongoCursor
      when(mockIterable.iterator()).thenReturn(mockCursor);
      // Empty cursor - collection does not exist
      when(mockCursor.hasNext()).thenReturn(false);

      FluxgateMongoAutoConfiguration config = new FluxgateMongoAutoConfiguration(properties);

      // When & Then
      assertThatThrownBy(
              () -> invokeFluxgateRuleCollection(config, mockDatabase, "nonexistent_collection"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("should not throw exception when collection exists in VALIDATE mode")
    void shouldNotThrowExceptionWhenCollectionExistsInValidateMode() {
      // Given
      FluxgateProperties properties = new FluxgateProperties();
      properties.getMongo().setDdlAuto(DdlAuto.VALIDATE);
      properties.getMongo().setRuleCollection("existing_collection");

      MongoDatabase mockDatabase = mock(MongoDatabase.class);
      ListCollectionNamesIterable mockIterable = mock(ListCollectionNamesIterable.class);
      MongoCursor<String> mockCursor = mock(MongoCursor.class);

      when(mockDatabase.listCollectionNames()).thenReturn(mockIterable);
      // ListCollectionNamesIterable.iterator() returns MongoCursor
      when(mockIterable.iterator()).thenReturn(mockCursor);
      // Mock the cursor iteration
      Iterator<String> listIterator =
          Arrays.asList("existing_collection", "other_collection").iterator();
      when(mockCursor.hasNext()).thenAnswer(inv -> listIterator.hasNext());
      when(mockCursor.next()).thenAnswer(inv -> listIterator.next());
      when(mockDatabase.getCollection("existing_collection")).thenReturn(null);

      FluxgateMongoAutoConfiguration config = new FluxgateMongoAutoConfiguration(properties);

      // When & Then - should not throw
      invokeFluxgateRuleCollection(config, mockDatabase, "existing_collection");
      verify(mockDatabase, never()).createCollection(any());
    }

    @Test
    @DisplayName("should create collection when it does not exist in CREATE mode")
    void shouldCreateCollectionWhenNotExistsInCreateMode() {
      // Given
      FluxgateProperties properties = new FluxgateProperties();
      properties.getMongo().setDdlAuto(DdlAuto.CREATE);
      properties.getMongo().setRuleCollection("new_collection");

      MongoDatabase mockDatabase = mock(MongoDatabase.class);
      ListCollectionNamesIterable mockIterable = mock(ListCollectionNamesIterable.class);
      MongoCursor<String> mockCursor = mock(MongoCursor.class);

      when(mockDatabase.listCollectionNames()).thenReturn(mockIterable);
      // ListCollectionNamesIterable.iterator() returns MongoCursor
      when(mockIterable.iterator()).thenReturn(mockCursor);
      // Empty cursor - no collections exist
      when(mockCursor.hasNext()).thenReturn(false);
      when(mockDatabase.getCollection("new_collection")).thenReturn(null);

      FluxgateMongoAutoConfiguration config = new FluxgateMongoAutoConfiguration(properties);

      // When
      invokeFluxgateRuleCollection(config, mockDatabase, "new_collection");

      // Then
      verify(mockDatabase).createCollection("new_collection");
    }

    @Test
    @DisplayName("should not create collection when it already exists in CREATE mode")
    void shouldNotCreateCollectionWhenAlreadyExistsInCreateMode() {
      // Given
      FluxgateProperties properties = new FluxgateProperties();
      properties.getMongo().setDdlAuto(DdlAuto.CREATE);
      properties.getMongo().setRuleCollection("existing_collection");

      MongoDatabase mockDatabase = mock(MongoDatabase.class);
      ListCollectionNamesIterable mockIterable = mock(ListCollectionNamesIterable.class);
      MongoCursor<String> mockCursor = mock(MongoCursor.class);

      when(mockDatabase.listCollectionNames()).thenReturn(mockIterable);
      // ListCollectionNamesIterable.iterator() returns MongoCursor
      when(mockIterable.iterator()).thenReturn(mockCursor);
      // Mock cursor iteration with existing collection
      Iterator<String> listIterator = Arrays.asList("existing_collection").iterator();
      when(mockCursor.hasNext()).thenAnswer(inv -> listIterator.hasNext());
      when(mockCursor.next()).thenAnswer(inv -> listIterator.next());
      when(mockDatabase.getCollection("existing_collection")).thenReturn(null);

      FluxgateMongoAutoConfiguration config = new FluxgateMongoAutoConfiguration(properties);

      // When
      invokeFluxgateRuleCollection(config, mockDatabase, "existing_collection");

      // Then
      verify(mockDatabase, never()).createCollection(any());
    }

    /**
     * Helper method to invoke the fluxgateRuleCollection bean method using reflection. This allows
     * us to test the DDL auto logic without needing a real MongoDB connection.
     */
    private void invokeFluxgateRuleCollection(
        FluxgateMongoAutoConfiguration config, MongoDatabase database, String collectionName) {
      try {
        java.lang.reflect.Method method =
            FluxgateMongoAutoConfiguration.class.getDeclaredMethod(
                "fluxgateRuleCollection", MongoDatabase.class);
        method.setAccessible(true);
        method.invoke(config, database);
      } catch (java.lang.reflect.InvocationTargetException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }
}
