package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AuthzGracefulShutdownTest {
  @Test
  void packagedDefaultsDrainAnInflightTomcatRequestBeforeContextCloses() throws Exception {
    SpringApplication application = new SpringApplication(TestApplication.class);
    ServletWebServerApplicationContext context =
        (ServletWebServerApplicationContext)
            application.run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off");
    Gate gate = context.getBean(Gate.class);
    CountDownLatch closing = new CountDownLatch(1);
    context.addApplicationListener(
        event -> {
          if (event instanceof ContextClosedEvent) closing.countDown();
        });
    var pool = Executors.newFixedThreadPool(2);
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://127.0.0.1:" + context.getWebServer().getPort() + "/inflight"))
              .timeout(Duration.ofSeconds(15))
              .build();
      var response =
          pool.submit(
              () -> HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()));
      assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
      var shutdown = pool.submit(context::close);
      assertThat(closing.await(5, TimeUnit.SECONDS)).isTrue();
      // Exceeds Tomcat's immediate servlet unload wait: the request must remain
      // alive and shutdown must keep waiting for its real response.
      assertThatThrownBy(() -> shutdown.get(3, TimeUnit.SECONDS))
          .isInstanceOf(TimeoutException.class);
      gate.release.countDown();
      var completed = response.get(5, TimeUnit.SECONDS);
      assertThat(completed.statusCode()).isEqualTo(200);
      assertThat(completed.body()).isEqualTo("completed-before-stop");
      shutdown.get(5, TimeUnit.SECONDS);
    } finally {
      gate.release.countDown();
      context.close();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    ServletWebServerFactoryAutoConfiguration.class,
    DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class,
    LifecycleAutoConfiguration.class
  })
  static class TestApplication {
    @Bean
    Gate gate() {
      return new Gate();
    }
  }

  @RestController
  static class Gate {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @GetMapping("/inflight")
    String inflight() throws InterruptedException {
      entered.countDown();
      if (!release.await(15, TimeUnit.SECONDS))
        throw new IllegalStateException("request not released");
      return "completed-before-stop";
    }
  }
}
