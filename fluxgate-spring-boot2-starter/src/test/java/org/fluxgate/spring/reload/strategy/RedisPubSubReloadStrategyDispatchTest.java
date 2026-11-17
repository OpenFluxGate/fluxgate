package org.fluxgate.spring.reload.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.core.reload.RuleReloadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pub/Sub messages are handled off the Lettuce event loop: a slow reload (a full bucket reset
 * scanning a large keyspace) must not block the connection that delivers the next message.
 */
class RedisPubSubReloadStrategyDispatchTest {

  private static final String CHANNEL = "fluxgate:rule-reload";

  private final AtomicReference<RedisPubSubListener<String, String>> lettuceListener =
      new AtomicReference<>();

  private RedisPubSubReloadStrategy strategy;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void startWithAMockedConnection() {
    RedisClient client = mock(RedisClient.class);
    StatefulRedisPubSubConnection<String, String> connection =
        mock(StatefulRedisPubSubConnection.class);
    when(client.connectPubSub()).thenReturn(connection);
    when(connection.sync()).thenReturn(mock(RedisPubSubCommands.class));
    doAnswer(
            invocation -> {
              lettuceListener.set(invocation.getArgument(0));
              return null;
            })
        .when(connection)
        .addListener(any(RedisPubSubListener.class));

    strategy =
        new RedisPubSubReloadStrategy(client, CHANNEL, false, Duration.ofSeconds(1), null, null);
    strategy.start();
    assertThat(lettuceListener.get()).isNotNull();
  }

  @AfterEach
  void stop() {
    strategy.stop();
  }

  /** Delivers a message the way Lettuce does, on the calling ("event loop") thread. */
  private void deliver(String message) {
    lettuceListener.get().message(CHANNEL, message);
  }

  @Test
  void aSlowReloadDoesNotBlockTheDeliveringThread() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<String> handlerThread = new AtomicReference<>();
    strategy.addListener(
        event -> {
          handlerThread.set(Thread.currentThread().getName());
          started.countDown();
          await(release);
        });

    long begin = System.nanoTime();
    deliver("orders");
    long deliveryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(deliveryMillis).isLessThan(1_000);
    assertThat(handlerThread.get())
        .isNotEqualTo(Thread.currentThread().getName())
        .startsWith("fluxgate-pubsub-listener");
    release.countDown();
  }

  @Test
  void messagesAreHandledInTheOrderTheyArrive() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(3);
    List<String> handled = new CopyOnWriteArrayList<>();
    strategy.addListener(
        event -> {
          await(release);
          handled.add(event.getRuleSetId());
          done.countDown();
        });

    deliver("a");
    deliver("b");
    deliver("c");
    release.countDown();

    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(handled).containsExactly("a", "b", "c");
  }

  @Test
  void stopShutsTheListenerThreadDownAndDropsLaterMessages() throws Exception {
    List<RuleReloadEvent> events = new CopyOnWriteArrayList<>();
    strategy.addListener(events::add);

    strategy.stop();
    deliver("orders");

    Thread.sleep(100);
    assertThat(events).isEmpty();
    assertThat(
            Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("fluxgate-pubsub-listener")))
        .isEmpty();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
