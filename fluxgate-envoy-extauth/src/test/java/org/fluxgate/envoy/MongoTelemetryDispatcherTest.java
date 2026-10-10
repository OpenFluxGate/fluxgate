package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoCollection;
import java.util.List;
import java.util.concurrent.*;
import org.bson.Document;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.CompositeMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.junit.jupiter.api.Test;

class MongoTelemetryDispatcherTest {
  @Test
  void blockedMongoDoesNotHoldCompletedDecision() throws Exception {
    MongoCollection<Document> collection = mock(MongoCollection.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(collection)
        .insertMany(anyList(), any(com.mongodb.client.model.InsertManyOptions.class));
    var recorder = new MongoRateLimitMetricsRecorder(collection);
    var primary = new CompositeMetricsRecorder(List.of(recorder));
    MongoTelemetryDispatcher dispatcher = new MongoTelemetryDispatcher();
    var facade = dispatcher.adapt(primary);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    // Both capture and the LTS writer remain independent of the completed decision.
    try {
      Future<?> decision =
          caller.submit(
              () ->
                  facade.record(
                      RequestContext.builder().build(), RateLimitResult.allowedWithoutRule()));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      decision.get(200, TimeUnit.MILLISECONDS);
      awaitContains(dispatcher, "\"forwarded\":1");
      assertThat(dispatcher.diagnosticsJson())
          .contains("\"writerWritten\":0", "\"writerPending\":1", "\"outstanding\":0");
    } finally {
      release.countDown();
      caller.shutdownNow();
      dispatcher.stop();
      recorder.close();
    }
    assertThat(dispatcher.diagnosticsJson()).contains("\"writerWritten\":1", "\"writerPending\":0");
  }

  @Test
  void queueOverflowNeverRunsOnCallerAndShutdownAccountsForCancelledEvents() throws Exception {
    MongoRateLimitMetricsRecorder leaf = mock(MongoRateLimitMetricsRecorder.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                }
              }
              return null;
            })
        .when(leaf)
        .record(any(), any());
    var writer = new MongoTelemetryDispatcher(1, 1, java.time.Duration.ofMillis(40));
    var facade = writer.adapt(leaf);
    var context = RequestContext.builder().build();
    var result = RateLimitResult.allowedWithoutRule();
    try {
      facade.record(context, result);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      facade.record(context, result);
      facade.record(context, result);
      assertThat(writer.diagnosticsJson())
          .contains("\"accepted\":2", "\"rejected\":1", "\"queue\":1");
      long started = System.nanoTime();
      writer.stop();
      assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(1));
      assertThat(writer.diagnosticsJson())
          .contains("\"cancelled\":1", "\"inflight\":1", "\"outstanding\":1");
      facade.record(context, result);
      assertThat(writer.diagnosticsJson()).contains("\"rejected\":2");
      verify(leaf, times(1)).record(any(), any());
    } finally {
      release.countDown();
      writer.stop();
    }
    awaitContains(writer, "\"outstanding\":0");
  }

  @Test
  void leafFailureAndErrorAreCountedInsteadOfCompositeHidingThem() throws Exception {
    var leaf = mock(MongoRateLimitMetricsRecorder.class);
    doThrow(new IllegalStateException("private"), new AssertionError("private"))
        .when(leaf)
        .record(any(), any());
    var writer = new MongoTelemetryDispatcher();
    try {
      var facade = writer.adapt(new CompositeMetricsRecorder(List.of(leaf)));
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      awaitContains(writer, "\"failed\":2");
      assertThat(writer.diagnosticsJson())
          .contains("\"inflight\":0", "\"outstanding\":0")
          .doesNotContain("private");
    } finally {
      writer.stop();
    }
  }

  @Test
  void nestedMutableAttributesAreFrozenAndUnsupportedCaptureIsRejected() throws Exception {
    var gate = new CountDownLatch(1);
    var first = new CountDownLatch(1);
    var leaf = mock(MongoRateLimitMetricsRecorder.class);
    var captured = new java.util.concurrent.atomic.AtomicReference<RequestContext>();
    doAnswer(
            call -> {
              first.countDown();
              gate.await();
              captured.set(call.getArgument(0));
              return null;
            })
        .when(leaf)
        .record(any(), any());
    var writer = new MongoTelemetryDispatcher();
    var nested =
        new java.util.ArrayList<Object>(
            List.of(new java.util.HashMap<>(java.util.Map.of("tenant", "original"))));
    var request =
        RequestContext.builder().apiKey("private-key").attribute("nested", nested).build();
    try {
      var facade = writer.adapt(leaf);
      facade.record(request, RateLimitResult.allowedWithoutRule());
      assertThat(first.await(1, TimeUnit.SECONDS)).isTrue();
      ((java.util.Map<String, Object>) nested.get(0)).put("tenant", "changed");
      nested.clear();
      gate.countDown();
      awaitContains(writer, "\"forwarded\":1");
      assertThat(captured.get().getAttribute("nested").toString())
          .contains("original")
          .doesNotContain("changed");
      facade.record(
          RequestContext.builder().attribute("bad", new Object()).build(),
          RateLimitResult.allowedWithoutRule());
      var cycle = new java.util.ArrayList<Object>();
      cycle.add(cycle);
      facade.record(
          RequestContext.builder().attribute("bad", cycle).build(),
          RateLimitResult.allowedWithoutRule());
      assertThat(writer.diagnosticsJson()).contains("\"rejected\":2").doesNotContain("private-key");
      verify(leaf, times(1)).record(any(), any());
    } finally {
      gate.countDown();
      writer.stop();
    }
  }

  @Test
  void knownCompositeMappingIsStableAndOtherLeavesStaySynchronous() throws Exception {
    var mongo = mock(MongoRateLimitMetricsRecorder.class);
    var sync = mock(org.fluxgate.core.metrics.RateLimitMetricsRecorder.class);
    var primary =
        new CompositeMetricsRecorder(List.of(new CompositeMetricsRecorder(List.of(mongo)), sync));
    var writer = new MongoTelemetryDispatcher();
    try {
      var facade = writer.adapt(primary);
      assertThat(writer.adapt(primary)).isSameAs(facade);
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      verify(sync).record(any(), any());
      awaitContains(writer, "\"forwarded\":1");
    } finally {
      writer.stop();
    }
  }

  private static void awaitContains(MongoTelemetryDispatcher writer, String token)
      throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!writer.diagnosticsJson().contains(token) && System.nanoTime() < end) Thread.sleep(5);
    assertThat(writer.diagnosticsJson()).contains(token);
  }

  @Test
  void actualDecisionAndQuotaFinishWhileRealMongoInsertIsBlocked() throws Exception {
    MongoCollection<Document> collection = mock(MongoCollection.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(collection)
        .insertMany(anyList(), any(com.mongodb.client.model.InsertManyOptions.class));
    var writer = new MongoTelemetryDispatcher();
    var recorder = new MongoRateLimitMetricsRecorder(collection);
    var rule =
        org.fluxgate.core.config.RateLimitRule.builder("quota")
            .ruleSetId("test")
            .addBand(
                org.fluxgate.core.config.RateLimitBand.builder(java.time.Duration.ofHours(1), 1)
                    .build())
            .build();
    var policy =
        org.fluxgate.core.ratelimiter.RateLimitRuleSet.builder("test")
            .rules(List.of(rule))
            .keyResolver(new org.fluxgate.core.key.LimitScopeKeyResolver())
            .metricsRecorder(writer.adapt(new CompositeMetricsRecorder(List.of(recorder))))
            .build();
    org.fluxgate.core.spi.RateLimitRuleSetProvider provider = id -> java.util.Optional.of(policy);
    var remaining = new java.util.concurrent.atomic.AtomicInteger(1);
    var engine =
        org.fluxgate.core.engine.RateLimitEngine.builder()
            .ruleSetProvider(provider)
            .rateLimiter(
                (context, snapshot, permits) -> {
                  var key = org.fluxgate.core.key.RateLimitKey.of("global");
                  var result =
                      remaining.getAndDecrement() > 0
                          ? RateLimitResult.allowed(key, rule, 0, 0)
                          : RateLimitResult.rejected(key, rule, 1_000_000_000L);
                  snapshot.getMetricsRecorder().record(context, result);
                  return result;
                })
            .build();
    var service = new AuthzDecisionService(engine, provider, "test");
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      assertThat(
              caller
                  .submit(() -> service.decide(RequestContext.builder().build()).status())
                  .get(1, TimeUnit.SECONDS))
          .isEqualTo(200);
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(
              caller
                  .submit(() -> service.decide(RequestContext.builder().build()).status())
                  .get(1, TimeUnit.SECONDS))
          .isEqualTo(429);
      assertThat(release.getCount()).isEqualTo(1);
    } finally {
      release.countDown();
      writer.stop();
      caller.shutdownNow();
      recorder.close();
    }
  }

  @Test
  void oversizedScalarsHeadersAndNumericValuesRejectOnlyTelemetry() {
    var leaf = mock(MongoRateLimitMetricsRecorder.class);
    var writer = new MongoTelemetryDispatcher();
    try {
      var facade = writer.adapt(leaf);
      var result = RateLimitResult.allowedWithoutRule();
      facade.record(RequestContext.builder().attribute("huge", "x".repeat(65537)).build(), result);
      facade.record(RequestContext.builder().header("huge", "x".repeat(65537)).build(), result);
      facade.record(
          RequestContext.builder()
              .attribute("huge", java.math.BigInteger.ONE.shiftLeft(300000))
              .build(),
          result);
      facade.record(
          RequestContext.builder()
              .attribute(
                  "huge", new java.math.BigDecimal(java.math.BigInteger.ONE.shiftLeft(300000)))
              .build(),
          result);
      facade.record(
          RequestContext.builder().attribute("unicode", "가".repeat(22000)).build(), result);
      assertThat(writer.diagnosticsJson()).contains("\"rejected\":5", "\"accepted\":0");
      verify(leaf, never()).record(any(), any());
    } finally {
      writer.stop();
    }
  }

  @Test
  void bothQueuesDrainBeforeOwnedMongoClientCloses() throws Exception {
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    var captureEntered = new CountDownLatch(1);
    var releaseCapture = new CountDownLatch(1);
    var insertEntered = new CountDownLatch(1);
    var releaseInsert = new CountDownLatch(1);
    var observed = new java.util.concurrent.atomic.AtomicBoolean(false);
    var client = mock(com.mongodb.client.MongoClient.class);
    doAnswer(
            call -> {
              alive.set(false);
              return null;
            })
        .when(client)
        .close();
    MongoCollection<Document> collection = mock(MongoCollection.class);
    doAnswer(
            call -> {
              insertEntered.countDown();
              releaseInsert.await();
              observed.set(alive.get());
              return null;
            })
        .when(collection)
        .insertMany(anyList(), any(com.mongodb.client.model.InsertManyOptions.class));
    var recorder = new MongoRateLimitMetricsRecorder(collection);
    var captureGate = mock(MongoRateLimitMetricsRecorder.class);
    doAnswer(
            call -> {
              captureEntered.countDown();
              releaseCapture.await();
              return null;
            })
        .when(captureGate)
        .record(any(), any());
    var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
    context.registerBean(
        "fluxgateMongoClientHolder",
        org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder.class,
        () -> new org.fluxgate.spring.autoconfigure.FluxgateMongoClientHolder(client, true),
        definition -> definition.setDestroyMethodName("close"));
    context.registerBean(
        "recorder",
        MongoRateLimitMetricsRecorder.class,
        () -> recorder,
        definition -> {
          definition.setDependsOn("fluxgateMongoClientHolder");
          definition.setDestroyMethodName("close");
        });
    context.registerBean(
        MongoTelemetryDispatcher.class,
        () -> new MongoTelemetryDispatcher(1, 2, java.time.Duration.ofSeconds(2)),
        definition -> definition.setDependsOn("fluxgateMongoClientHolder"));
    context.refresh();
    var dispatcher = context.getBean(MongoTelemetryDispatcher.class);
    var facade = dispatcher.adapt(recorder);
    ExecutorService closer = Executors.newSingleThreadExecutor();
    try {
      // Hold the only dispatcher worker so both real events wait in its first queue.
      dispatcher
          .adapt(captureGate)
          .record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      assertThat(captureEntered.await(1, TimeUnit.SECONDS)).isTrue();
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      assertThat(dispatcher.diagnosticsJson()).contains("\"queue\":2", "\"inflight\":1");
      Future<?> closing = closer.submit(context::close);
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (dispatcher.isRunning() && System.nanoTime() < end) Thread.sleep(2);
      assertThat(dispatcher.isRunning()).isFalse();
      assertThat(alive.get()).isTrue();
      assertThat(closing.isDone()).isFalse();
      releaseCapture.countDown();
      assertThat(insertEntered.await(1, TimeUnit.SECONDS)).isTrue();
      awaitContains(dispatcher, "\"forwarded\":3");
      assertThat(dispatcher.diagnosticsJson())
          .contains("\"outstanding\":0", "\"writerWritten\":0", "\"writerPending\":2");
      assertThat(alive.get()).isTrue();
      assertThat(closing.isDone()).isFalse();
      releaseInsert.countDown();
      closing.get(2, TimeUnit.SECONDS);
      assertThat(observed.get()).isTrue();
      assertThat(alive.get()).isFalse();
      assertThat(dispatcher.diagnosticsJson())
          .contains("\"writerWritten\":2", "\"writerPending\":0", "\"outstanding\":0");
      verify(client).close();
    } finally {
      releaseCapture.countDown();
      releaseInsert.countDown();
      context.close();
      closer.shutdownNow();
      dispatcher.stop();
      recorder.close();
    }
  }

  @Test
  void actualLtsWriterFailureDoesNotBecomeForwardingFailure() throws Exception {
    MongoCollection<Document> collection = mock(MongoCollection.class);
    doThrow(new IllegalStateException("test-store-failure"))
        .when(collection)
        .insertMany(anyList(), any(com.mongodb.client.model.InsertManyOptions.class));
    var recorder = new MongoRateLimitMetricsRecorder(collection);
    var dispatcher = new MongoTelemetryDispatcher();
    try {
      dispatcher
          .adapt(recorder)
          .record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      awaitContains(dispatcher, "\"forwarded\":1");
      assertThat(recorder.flush(java.time.Duration.ofSeconds(2))).isTrue();
      assertThat(dispatcher.diagnosticsJson())
          .contains(
              "\"failed\":0", "\"writerFailed\":1", "\"writerWritten\":0", "\"writerPending\":0")
          .doesNotContain("test-store-failure");
    } finally {
      dispatcher.stop();
      recorder.close();
    }
  }

  @Test
  void actualLtsQueueOverflowIsSeparateFromDispatcherRejectionAndCountedOnce() throws Exception {
    MongoCollection<Document> collection = mock(MongoCollection.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              release.await();
              return null;
            })
        .when(collection)
        .insertMany(anyList(), any(com.mongodb.client.model.InsertManyOptions.class));
    var recorder = new MongoRateLimitMetricsRecorder(collection, false, 1);
    var dispatcher = new MongoTelemetryDispatcher(1, 4, java.time.Duration.ofSeconds(2));
    try {
      var facade = dispatcher.adapt(recorder);
      // The same leaf also occurs in another composite; diagnostics must not double count it.
      dispatcher.adapt(new CompositeMetricsRecorder(List.of(recorder)));
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      awaitContains(dispatcher, "\"forwarded\":2");
      facade.record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      awaitContains(dispatcher, "\"forwarded\":3");
      assertThat(dispatcher.diagnosticsJson())
          .contains(
              "\"rejected\":0",
              "\"writerDropped\":1",
              "\"writerPending\":2",
              "\"writerWritten\":0",
              "\"outstanding\":0");
    } finally {
      release.countDown();
      dispatcher.stop();
      recorder.close();
    }
    assertThat(dispatcher.diagnosticsJson()).contains("\"writerWritten\":2", "\"writerPending\":0");
  }

  @Test
  void customRecorderOverridesKeepTheirOriginalSynchronousContract() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var mongoPlugin =
        new MongoRateLimitMetricsRecorder(mock(MongoCollection.class)) {
          @Override
          public void record(RequestContext context, RateLimitResult result) {
            calls.incrementAndGet();
          }
        };
    var compositePlugin =
        new CompositeMetricsRecorder(
            List.of(mock(org.fluxgate.core.metrics.RateLimitMetricsRecorder.class))) {
          @Override
          public void record(RequestContext context, RateLimitResult result) {
            calls.incrementAndGet();
          }
        };
    var writer = new MongoTelemetryDispatcher();
    try {
      assertThat(writer.adapt(mongoPlugin)).isSameAs(mongoPlugin);
      assertThat(writer.adapt(compositePlugin)).isSameAs(compositePlugin);
      writer
          .adapt(mongoPlugin)
          .record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      writer
          .adapt(compositePlugin)
          .record(RequestContext.builder().build(), RateLimitResult.allowedWithoutRule());
      assertThat(calls.get()).isEqualTo(2);
      assertThat(writer.diagnosticsJson()).contains("\"accepted\":0");
    } finally {
      writer.stop();
      mongoPlugin.close();
    }
  }
}
