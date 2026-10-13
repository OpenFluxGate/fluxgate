package org.fluxgate.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fluxgate.core.util.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Lifecycle tests for {@link RedisRuleChangeNotifier}.
 *
 * <p>Covers the leaks and races the review found: a reconnect that abandoned the previous Lettuce
 * client (and with it a Netty event loop group), and a publish racing {@code close()} that could
 * create a brand new client after shutdown.
 */
class RedisRuleChangeNotifierLifecycleTest {

  private static final String SECRET = "unit-test-only-secret";

  /** One generation of mocks: client, connection and sync commands. */
  private static final class Generation {
    final RedisClient client = mock(RedisClient.class);
    final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);

    @SuppressWarnings("unchecked")
    final RedisCommands<String, String> commands = mock(RedisCommands.class);

    Generation(boolean open, long receivers) {
      when(client.connect()).thenReturn(connection);
      when(connection.isOpen()).thenReturn(open);
      when(connection.sync()).thenReturn(commands);
      when(commands.publish(anyString(), anyString())).thenReturn(receivers);
    }
  }

  /** Stubs the static factory so each {@code RedisClient.create} hands out the next generation. */
  private static MockedStatic<RedisClient> stubClientFactory(List<Generation> generations) {
    MockedStatic<RedisClient> statik = mockStatic(RedisClient.class);
    List<RedisClient> clients = new ArrayList<>();
    generations.forEach(generation -> clients.add(generation.client));
    statik
        .when(() -> RedisClient.create(any(RedisURI.class)))
        .thenReturn(clients.get(0), clients.subList(1, clients.size()).toArray(new RedisClient[0]));
    return statik;
  }

  @Test
  void shouldReportTheNumberOfReceivers() {
    Generation first = new Generation(true, 3L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      notifier.notifyChange("orders");

      verify(first.commands).publish(eq("fluxgate:rule-reload"), anyString());
      notifier.close();
    }
  }

  @Test
  void shouldNotWarnItselfIntoFailureWhenNobodyIsListening() {
    Generation first = new Generation(true, 0L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      notifier.notifyFullReload();
      notifier.notifyFullReload();

      verify(first.commands, times(2)).publish(anyString(), anyString());
      notifier.close();
    }
  }

  @Test
  void shouldReuseAnOpenConnection() {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      notifier.notifyChange("orders");
      notifier.notifyChange("payments");

      verify(first.client, times(1)).connect();
      notifier.close();
    }
  }

  @Test
  void shouldReleaseThePreviousClientWhenReconnecting() {
    Generation first = new Generation(false, 1L);
    Generation second = new Generation(true, 1L);

    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first, second))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      notifier.notifyChange("orders"); // creates generation 1, which reports itself closed
      notifier.notifyChange("orders"); // must drop generation 1 and create generation 2

      verify(first.connection).close();
      verify(first.client).shutdown();
      verify(second.client, never()).shutdown();

      notifier.close();
      verify(second.client).shutdown();
    }
  }

  @Test
  void shouldShutDownTheClientOnClose() {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");
      notifier.notifyChange("orders");

      notifier.close();
      notifier.close();

      verify(first.connection, times(1)).close();
      verify(first.client, times(1)).shutdown();
    }
  }

  @Test
  void shouldNotCreateANewClientAfterClose() {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");
      notifier.notifyChange("orders");
      notifier.close();

      assertThatThrownBy(() -> notifier.notifyChange("orders"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");

      // Exactly one client was ever created; the post-close publish did not resurrect one.
      statik.verify(() -> RedisClient.create(any(RedisURI.class)), times(1));
    }
  }

  @Test
  void shouldWrapPublishFailuresInANotificationException() {
    Generation first = new Generation(true, 1L);
    when(first.commands.publish(anyString(), anyString()))
        .thenThrow(new IllegalStateException("connection reset"));

    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      assertThatThrownBy(() -> notifier.notifyChange("orders"))
          .isInstanceOf(RuleChangeNotificationException.class);

      assertThat(notifier).isNotNull();
      notifier.close();
    }
  }

  @Test
  @DisplayName("A configured secret signs every published payload")
  void shouldPublishASignedPayloadWhenASecretIsConfigured() throws Exception {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier(
              "redis://localhost:6379",
              "fluxgate:rule-reload",
              Duration.ofSeconds(5),
              "studio",
              SECRET);

      notifier.notifyChange("orders");

      ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
      verify(first.commands).publish(eq("fluxgate:rule-reload"), payload.capture());
      RuleChangeMessage published =
          new ObjectMapper().readValue(payload.getValue(), RuleChangeMessage.class);

      assertThat(published.getRuleSetId()).isEqualTo("orders");
      assertThat(published.getSource()).isEqualTo("studio");
      assertThat(published.getSignature()).isNotNull();
      assertThat(
              HmacSigner.verify(
                  SECRET,
                  published.canonicalForm("fluxgate:rule-reload"),
                  published.getSignature()))
          .isTrue();

      notifier.close();
    }
  }

  @Test
  @DisplayName("Without a secret the payload keeps the shape earlier versions published")
  void shouldPublishAnUnsignedPayloadWithoutASecret() {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      notifier.notifyFullReload();

      ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
      verify(first.commands).publish(eq("fluxgate:rule-reload"), payload.capture());

      assertThat(payload.getValue()).contains("\"fullReload\":true").doesNotContain("signature");

      notifier.close();
    }
  }

  @Test
  @DisplayName("publishChange reports the receiver count Redis returned")
  void shouldReturnTheReceiverCount() {
    Generation first = new Generation(true, 3L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      assertThat(notifier.publishChange("orders")).isEqualTo(3L);
      assertThat(notifier.publishFullReload()).isEqualTo(3L);
      notifier.close();
    }
  }

  @Test
  @DisplayName("A connection failure surfaces as RuleChangeNotificationException")
  void shouldWrapConnectionFailures() {
    Generation first = new Generation(true, 1L);
    when(first.client.connect()).thenThrow(new RedisConnectionException("Unable to connect"));
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");

      assertThatThrownBy(() -> notifier.notifyChange("orders"))
          .isInstanceOf(RuleChangeNotificationException.class)
          .hasCauseInstanceOf(RedisConnectionException.class);
      notifier.close();
    }
  }

  @Test
  @DisplayName("A slow PUBLISH does not hold the connection lock against other publishers")
  void shouldPublishOutsideTheConnectionLock() throws Exception {
    Generation first = new Generation(true, 1L);
    CountDownLatch firstPublishEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstPublish = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean blockNext =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(first.commands.publish(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              if (blockNext.compareAndSet(true, false)) {
                firstPublishEntered.countDown();
                releaseFirstPublish.await(10, TimeUnit.SECONDS);
              }
              return 1L;
            });

    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier("redis://localhost:6379", "fluxgate:rule-reload");
      // Static mocks are thread-local: create the connection on this thread first.
      notifier.notifyChange("warm-up");
      blockNext.set(true);

      CompletableFuture<Void> slow = CompletableFuture.runAsync(() -> notifier.notifyChange("a"));
      assertThat(firstPublishEntered.await(5, TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> fast = CompletableFuture.runAsync(() -> notifier.notifyChange("b"));
      fast.get(2, TimeUnit.SECONDS); // would time out while the first publish held the lock

      releaseFirstPublish.countDown();
      slow.get(5, TimeUnit.SECONDS);
      notifier.close();
    }
  }

  @Test
  @DisplayName("Surrounding whitespace in the secret is trimmed, like on the data plane")
  void shouldTrimTheSecret() throws Exception {
    Generation first = new Generation(true, 1L);
    try (MockedStatic<RedisClient> statik = stubClientFactory(List.of(first))) {
      RedisRuleChangeNotifier notifier =
          new RedisRuleChangeNotifier(
              "redis://localhost:6379",
              "fluxgate:rule-reload",
              Duration.ofSeconds(5),
              "studio",
              "  " + SECRET + "\n");

      notifier.notifyChange("orders");

      ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
      verify(first.commands).publish(eq("fluxgate:rule-reload"), payload.capture());
      RuleChangeMessage published =
          new ObjectMapper().readValue(payload.getValue(), RuleChangeMessage.class);
      assertThat(published.getNonce()).isNotBlank();
      assertThat(
              HmacSigner.verify(
                  SECRET,
                  published.canonicalForm("fluxgate:rule-reload"),
                  published.getSignature()))
          .isTrue();
      notifier.close();
    }
  }

  @Test
  @DisplayName("An unparseable URI fails without quoting its password anywhere in the exception")
  void shouldNotLeakThePasswordOfAnInvalidUri() {
    RedisRuleChangeNotifier notifier =
        new RedisRuleChangeNotifier("redis://:top secret@localhost:6379", "fluxgate:rule-reload");

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(() -> notifier.notifyChange("orders"));

    assertThat(thrown).isInstanceOf(RuleChangeNotificationException.class);
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      assertThat(String.valueOf(t.getMessage())).doesNotContain("top secret");
    }
    notifier.close();
  }
}
