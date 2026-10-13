package org.fluxgate.envoy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.fluxgate.adapter.mongo.event.MongoRateLimitMetricsRecorder;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.metrics.CompositeMetricsRecorder;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitResult;
import org.springframework.context.SmartLifecycle;

/**
 * Best-effort Mongo event delivery, never durable publication audit. Event timestamps are writer
 * processing times; concurrent workers do not preserve order. Overload, shutdown, or store failure
 * can lose events. This bean deliberately does not implement the recorder SPI.
 */
public final class MongoTelemetryDispatcher implements SmartLifecycle {
  private final ThreadPoolExecutor executor;
  private final long drainMillis;
  private final Map<RateLimitMetricsRecorder, RateLimitMetricsRecorder> facades =
      new IdentityHashMap<>();
  private final Set<MongoRateLimitMetricsRecorder> writers =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private volatile boolean running = true;
  private long accepted, forwarded, failed, rejected, cancelled, inflight;

  public MongoTelemetryDispatcher() {
    this(4, 1024, Duration.ofSeconds(10));
  }

  MongoTelemetryDispatcher(int workers, int capacity, Duration drain) {
    drainMillis = drain.toMillis();
    executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            task -> {
              Thread t = new Thread(task, "fluxgate-mongo-telemetry");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public synchronized RateLimitMetricsRecorder adapt(RateLimitMetricsRecorder primary) {
    if (primary == null) return null;
    return facades.computeIfAbsent(primary, this::mapRecorder);
  }

  private RateLimitMetricsRecorder mapRecorder(RateLimitMetricsRecorder recorder) {
    if (recorder.getClass() == CompositeMetricsRecorder.class) {
      return new CompositeMetricsRecorder(
          ((CompositeMetricsRecorder) recorder).getRecorders().stream().map(this::adapt).toList());
    }
    if (recorder.getClass() == MongoRateLimitMetricsRecorder.class) {
      writers.add((MongoRateLimitMetricsRecorder) recorder);
      return (context, result) -> submit(recorder, context, result);
    }
    return recorder;
  }

  private void submit(
      RateLimitMetricsRecorder recorder, RequestContext context, RateLimitResult result) {
    final RequestContext copy;
    try {
      copy = snapshot(context);
    } catch (RuntimeException e) {
      synchronized (this) {
        rejected++;
      }
      return;
    }
    synchronized (this) {
      if (!running) {
        rejected++;
        return;
      }
      accepted++;
      try {
        executor.execute(new Event(recorder, copy, result));
      } catch (RejectedExecutionException e) {
        accepted--;
        rejected++;
      }
    }
  }

  private final class Event implements Runnable {
    private final RateLimitMetricsRecorder recorder;
    private final RequestContext context;
    private final RateLimitResult result;

    Event(RateLimitMetricsRecorder recorder, RequestContext context, RateLimitResult result) {
      this.recorder = recorder;
      this.context = context;
      this.result = result;
    }

    public void run() {
      synchronized (MongoTelemetryDispatcher.this) {
        inflight++;
      }
      boolean success = false;
      try {
        recorder.record(context, result);
        success = true;
      } catch (Throwable ignored) {
        /* Count leaf failures without logging credentials or exception text. */
      } finally {
        synchronized (MongoTelemetryDispatcher.this) {
          if (success) forwarded++;
          else failed++;
          inflight--;
        }
      }
    }
  }

  static RequestContext snapshot(RequestContext context) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    CaptureBudget budget = new CaptureBudget();
    budget.text(context.getClientIp());
    budget.text(context.getUserId());
    budget.text(context.getApiKey());
    budget.text(context.getEndpoint());
    budget.text(context.getMethod());
    context
        .getHeaders()
        .forEach(
            (key, value) -> {
              budget.text(key);
              budget.text(value);
            });
    IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
    context
        .getAttributes()
        .forEach((key, value) -> attrs.put(budget.text(key), freeze(value, ancestors, 0, budget)));
    return RequestContext.builder()
        .clientIp(context.getClientIp())
        .userId(context.getUserId())
        .apiKey(context.getApiKey())
        .endpoint(context.getEndpoint())
        .method(context.getMethod())
        .headers(context.getHeaders())
        .attributes(attrs)
        .build();
  }

  private static Object freeze(
      Object value, IdentityHashMap<Object, Boolean> ancestors, int depth, CaptureBudget budget) {
    if (--budget.nodes < 0 || depth > 32)
      throw new IllegalArgumentException("Telemetry capture bound exceeded");
    if (value == null) return null;
    if (value instanceof String string) return budget.text(string);
    if (value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      budget.text(value.toString());
      return value;
    }
    if (value instanceof BigInteger number) {
      if (number.bitLength() > budget.bytes * 4L)
        throw new IllegalArgumentException("Oversize telemetry number");
      budget.text(number.toString());
      return number;
    }
    if (value instanceof BigDecimal number) {
      if (number.unscaledValue().bitLength() > budget.bytes * 4L)
        throw new IllegalArgumentException("Oversize telemetry number");
      budget.text(number.toString());
      return number;
    }
    if (value instanceof Double d && Double.isFinite(d)) {
      budget.text(d.toString());
      return d;
    }
    if (value instanceof Float f && Float.isFinite(f)) {
      budget.text(f.toString());
      return f;
    }
    if (ancestors.put(value, true) != null)
      throw new IllegalArgumentException("Cyclic telemetry attribute");
    try {
      if (value instanceof Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach(
            (key, item) -> {
              if (!(key instanceof String s))
                throw new IllegalArgumentException("Non-string telemetry key");
              copy.put(budget.text(s), freeze(item, ancestors, depth + 1, budget));
            });
        return Collections.unmodifiableMap(copy);
      }
      if (value instanceof List<?> list) {
        List<Object> copy = new ArrayList<>();
        for (Object item : list) copy.add(freeze(item, ancestors, depth + 1, budget));
        return Collections.unmodifiableList(copy);
      }
      throw new IllegalArgumentException("Unsupported telemetry attribute");
    } finally {
      ancestors.remove(value);
    }
  }

  /** At most 512 capture nodes and 64 KiB of scalar UTF-8 data per queued event. */
  private static final class CaptureBudget {
    int nodes = 512;
    int bytes = 64 * 1024;

    String text(String value) {
      if (--nodes < 0) throw new IllegalArgumentException("Telemetry node bound exceeded");
      if (value == null) return null;
      if (value.length() > bytes)
        throw new IllegalArgumentException("Telemetry byte bound exceeded");
      for (int i = 0; i < value.length(); i++) {
        char ch = value.charAt(i);
        int cost;
        if (ch < 0x80) cost = 1;
        else if (ch < 0x800) cost = 2;
        else if (Character.isHighSurrogate(ch)
            && i + 1 < value.length()
            && Character.isLowSurrogate(value.charAt(i + 1))) {
          cost = 4;
          i++;
        } else cost = 3;
        bytes -= cost;
        if (bytes < 0) throw new IllegalArgumentException("Telemetry byte bound exceeded");
      }
      return value;
    }
  }

  /**
   * Forwarded counts leaf record() returns, not persistence. Writer counters aggregate the complete
   * lifetime of each adapted built-in recorder, including calls made outside this dispatcher; the
   * independent atomic counters are an approximate concurrent snapshot.
   */
  public synchronized String diagnosticsJson() {
    long written = 0, dropped = 0, writerFailed = 0, pending = 0;
    for (MongoRateLimitMetricsRecorder writer : writers) {
      written += writer.getWrittenEvents();
      dropped += writer.getDroppedEvents();
      writerFailed += writer.getFailedEvents();
      pending += writer.getPendingEvents();
    }
    return "{\"enabled\":1,\"accepted\":"
        + accepted
        + ",\"forwarded\":"
        + forwarded
        + ",\"failed\":"
        + failed
        + ",\"rejected\":"
        + rejected
        + ",\"cancelled\":"
        + cancelled
        + ",\"inflight\":"
        + inflight
        + ",\"queue\":"
        + executor.getQueue().size()
        + ",\"outstanding\":"
        + (accepted - forwarded - failed - cancelled)
        + ",\"writerWritten\":"
        + written
        + ",\"writerDropped\":"
        + dropped
        + ",\"writerFailed\":"
        + writerFailed
        + ",\"writerPending\":"
        + pending
        + "}";
  }

  public void start() {
    /* Constructed ready for nonblocking submissions; cannot restart after drain. */
  }

  public boolean isRunning() {
    return running;
  }

  public int getPhase() {
    return 0;
  }

  public void stop() {
    stop(() -> {});
  }

  public void stop(Runnable callback) {
    synchronized (this) {
      running = false;
      executor.shutdown();
    }
    try {
      if (!executor.awaitTermination(drainMillis, TimeUnit.MILLISECONDS)) cancelQueued();
    } catch (InterruptedException e) {
      cancelQueued();
      Thread.currentThread().interrupt();
    } finally {
      callback.run();
    }
  }

  private void cancelQueued() {
    List<Runnable> queued = executor.shutdownNow();
    synchronized (this) {
      cancelled += queued.size();
    }
  }
}
