package org.fluxgate.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import java.util.List;
import java.util.stream.Collectors;
import org.bson.Document;
import org.fluxgate.spring.properties.FluxgateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * R3: FluxGate's MongoDB client must never become the application's {@link MongoClient}.
 *
 * <p>The rule collection is replaced by a mock so no test needs a running server; the clients are
 * only created, never used.
 */
class FluxgateMongoClientIsolationTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(FluxgateProperties.class)
  static class Base {

    @Bean
    @SuppressWarnings("unchecked")
    MongoCollection<Document> fluxgateRuleCollection() {
      return mock(MongoCollection.class);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class AppClient {

    @Bean(destroyMethod = "close")
    MongoClient appMongoClient() {
      return MongoClients.create("mongodb://127.0.0.1:27011");
    }

    @Bean
    MongoDatabase appDatabase(MongoClient appMongoClient) {
      return appMongoClient.getDatabase("app");
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Base.class)
          .withPropertyValues(
              "fluxgate.mongo.enabled=true",
              "fluxgate.mongo.uri=mongodb://127.0.0.1:27012/fluxgate",
              "fluxgate.mongo.database=fluxgate");

  private static List<String> hosts(MongoClient client) {
    return client.getClusterDescription().getClusterSettings().getHosts().stream()
        .map(ServerAddress::toString)
        .collect(Collectors.toList());
  }

  @Test
  void coexistsWithBootMongoAutoConfiguration() {
    runner
        .withConfiguration(
            AutoConfigurations.of(
                FluxgateMongoAutoConfiguration.class, MongoAutoConfiguration.class))
        .withPropertyValues("spring.data.mongodb.uri=mongodb://127.0.0.1:27011/app")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              // Boot's client is the application's only MongoClient...
              assertThat(context.getBeansOfType(MongoClient.class)).containsOnlyKeys("mongo");
              assertThat(hosts(context.getBean(MongoClient.class)))
                  .containsExactly("127.0.0.1:27011");
              // ...and FluxGate keeps talking to its own cluster.
              FluxgateMongoClientHolder holder = context.getBean(FluxgateMongoClientHolder.class);
              assertThat(hosts(holder.getClient())).containsExactly("127.0.0.1:27012");
              assertThat(context.getBean("fluxgateMongoDatabase", MongoDatabase.class).getName())
                  .isEqualTo("fluxgate");
            });
  }

  @Test
  void leavesAUserDefinedMongoClientAndDatabaseAlone() {
    runner
        .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
        .withUserConfiguration(AppClient.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(MongoClient.class))
                  .containsOnlyKeys("appMongoClient");
              assertThat(hosts(context.getBean(FluxgateMongoClientHolder.class).getClient()))
                  .containsExactly("127.0.0.1:27012");
              assertThat(context.getBean("fluxgateMongoDatabase", MongoDatabase.class).getName())
                  .isEqualTo("fluxgate");
              assertThat(context.getBean("appDatabase", MongoDatabase.class).getName())
                  .isEqualTo("app");
            });
  }

  @Test
  void fluxgateOnlyExposesNoMongoClient() {
    runner
        .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MongoClient.class);
              assertThat(context).hasSingleBean(FluxgateMongoClientHolder.class);
              assertThat(hosts(context.getBean(FluxgateMongoClientHolder.class).getClient()))
                  .containsExactly("127.0.0.1:27012");
            });
  }

  @Test
  void usesALegacyFluxgateMongoClientBeanWithoutClosingIt() {
    MongoClient legacy = mock(MongoClient.class);
    when(legacy.getDatabase("fluxgate")).thenReturn(mock(MongoDatabase.class));
    runner
        .withConfiguration(AutoConfigurations.of(FluxgateMongoAutoConfiguration.class))
        .withBean(
            "fluxgateMongoClient",
            MongoClient.class,
            () -> legacy,
            definition -> definition.setDestroyMethodName(""))
        .run(
            context -> {
              FluxgateMongoClientHolder holder = context.getBean(FluxgateMongoClientHolder.class);
              assertThat(holder.getClient()).isSameAs(legacy);
              assertThat(holder.isOwned()).isFalse();
            });
    verify(legacy, never()).close();
  }
}
