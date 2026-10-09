package org.fluxgate.envoy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Optional plaintext health listener exposing no authorization or identity endpoints. */
@Component
public final class GatewayHealthServer implements InitializingBean, DisposableBean {
  private final EnvoyProperties properties;
  private final AuthzDecisionService service;
  private HttpServer server;
  private ThreadPoolExecutor executor;

  public GatewayHealthServer(EnvoyProperties properties, AuthzDecisionService service) {
    this.properties = properties;
    this.service = service;
  }

  @Override
  public void afterPropertiesSet() throws IOException {
    if (properties.healthPort() != 0) {
      startServer(properties.healthPort());
    }
  }

  synchronized int startServer(int port) throws IOException {
    if (server != null) {
      throw new IllegalStateException("Health listener already running");
    }
    server = HttpServer.create(new InetSocketAddress(port), 16);
    executor =
        new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            task -> {
              Thread thread = new Thread(task, "fluxgate-health");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      int status;
      if (!"GET".equals(exchange.getRequestMethod())) {
        status = 405;
      } else if ("/healthz".equals(exchange.getRequestURI().getPath())) {
        status = 200;
      } else if ("/readyz".equals(exchange.getRequestURI().getPath())) {
        try {
          status =
              properties.routes().stream().allMatch(route -> service.isReady(route.ruleSetId()))
                  ? 200
                  : 503;
        } catch (RuntimeException e) {
          status = 503;
        }
      } else {
        status = 404;
      }
      exchange.getResponseHeaders().add("Cache-Control", "no-store");
      exchange.sendResponseHeaders(status, -1);
    }
  }

  @Override
  public synchronized void destroy() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
