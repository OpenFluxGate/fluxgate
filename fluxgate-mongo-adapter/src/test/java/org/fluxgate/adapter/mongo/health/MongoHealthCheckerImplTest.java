package org.fluxgate.adapter.mongo.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.fluxgate.adapter.mongo.health.MongoHealthCheckerImpl.HealthCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link MongoHealthCheckerImpl}. */
@ExtendWith(MockitoExtension.class)
class MongoHealthCheckerImplTest {

  @Mock private MongoDatabase database;

  @Test
  void shouldReturnUpWhenConnectionIsHealthy() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class)))
        .thenAnswer(
            invocation -> {
              Document cmd = invocation.getArgument(0);
              if (cmd.containsKey("ping")) {
                return new Document("ok", 1.0);
              } else if (cmd.containsKey("buildInfo")) {
                return new Document("version", "7.0.0");
              } else if (cmd.containsKey("serverStatus")) {
                Document connections = new Document();
                connections.put("current", 10);
                connections.put("available", 100);
                connections.put("totalCreated", 50);
                return new Document("connections", connections);
              }
              return new Document();
            });

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.message()).isEqualTo("MongoDB is healthy");
    assertThat(result.details()).containsEntry("database", "fluxgate");
    assertThat(result.details()).containsKey("latency_ms");
    assertThat(result.details()).containsEntry("version", "7.0.0");
    assertThat(result.details()).containsEntry("connections.current", 10);
    assertThat(result.details()).containsEntry("connections.available", 100);
  }

  @Test
  void shouldReturnDownWhenPingFails() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class)))
        .thenThrow(new RuntimeException("Connection refused"));

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("failed");
    assertThat(result.details()).containsKey("error");
  }

  @Test
  void shouldReturnDownWhenPingReturnsNotOk() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class))).thenReturn(new Document("ok", 0.0));

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isFalse();
    assertThat(result.status()).isEqualTo("DOWN");
    assertThat(result.message()).contains("Ping command failed");
  }

  @Test
  void shouldHandleServerStatusError() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class)))
        .thenAnswer(
            invocation -> {
              Document cmd = invocation.getArgument(0);
              if (cmd.containsKey("ping")) {
                return new Document("ok", 1.0);
              } else if (cmd.containsKey("buildInfo")) {
                return new Document("version", "7.0.0");
              } else if (cmd.containsKey("serverStatus")) {
                throw new RuntimeException("Not authorized");
              }
              return new Document();
            });

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    // Should still be healthy, just with limited info
    assertThat(result.isHealthy()).isTrue();
    assertThat(result.status()).isEqualTo("UP");
    assertThat(result.details()).containsEntry("version", "7.0.0");
    assertThat(result.details()).doesNotContainKey("connections.current");
  }

  @Test
  void shouldIncludeReplicaSetInfo() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class)))
        .thenAnswer(
            invocation -> {
              Document cmd = invocation.getArgument(0);
              if (cmd.containsKey("ping")) {
                return new Document("ok", 1.0);
              } else if (cmd.containsKey("buildInfo")) {
                return new Document("version", "7.0.0");
              } else if (cmd.containsKey("serverStatus")) {
                Document repl = new Document();
                repl.put("setName", "rs0");
                repl.put("isWritablePrimary", true);
                return new Document("repl", repl);
              }
              return new Document();
            });

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    assertThat(result.isHealthy()).isTrue();
    assertThat(result.details()).containsEntry("replicaSet.name", "rs0");
    assertThat(result.details()).containsEntry("replicaSet.role", "PRIMARY");
  }

  @Test
  void shouldMeasureLatency() {
    when(database.getName()).thenReturn("fluxgate");
    when(database.runCommand(any(Document.class))).thenReturn(new Document("ok", 1.0));

    MongoHealthCheckerImpl checker = new MongoHealthCheckerImpl(database);
    HealthCheckResult result = checker.check();

    assertThat(result.details()).containsKey("latency_ms");
    Object latency = result.details().get("latency_ms");
    assertThat(latency).isInstanceOf(Long.class);
    assertThat((Long) latency).isGreaterThanOrEqualTo(0);
  }
}
