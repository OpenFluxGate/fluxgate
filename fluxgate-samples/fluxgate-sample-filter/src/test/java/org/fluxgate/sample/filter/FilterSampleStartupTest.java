package org.fluxgate.sample.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.fluxgate.spring.filter.FluxgateRateLimitFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The sample starts with its shipped {@code application.yml} and the filter enabled, as {@code
 * ./mvnw spring-boot:run} does: only {@code fluxgate.api.url} is pointed at a local stand-in for
 * the rate limit check API of {@code fluxgate-sample-redis}, which answers the {@code POST
 * /api/ratelimit/check} that {@code HttpRateLimitHandler} sends.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilterSampleStartupTest {

  private static final String ALLOWED = "{\"allowed\":true,\"remaining\":9,\"retryAfterMs\":0}";

  private static final String REJECTED =
      "{\"allowed\":false,\"remaining\":0,\"retryAfterMs\":30000}";

  private static final AtomicReference<String> CHECK_RESPONSE = new AtomicReference<>(ALLOWED);

  private static final HttpServer CHECK_API = startCheckApi();

  @DynamicPropertySource
  static void checkApi(DynamicPropertyRegistry registry) {
    registry.add("fluxgate.api.url", () -> "http://localhost:" + CHECK_API.getAddress().getPort());
  }

  @AfterAll
  static void stopCheckApi() {
    CHECK_API.stop(0);
  }

  @Autowired private ApplicationContext context;

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void allowByDefault() {
    CHECK_RESPONSE.set(ALLOWED);
  }

  @Test
  void startsWithTheShippedConfigurationAndTheFilterEnabled() throws Exception {
    assertThat(context.getBeansOfType(FluxgateRateLimitFilter.class)).hasSize(1);

    mockMvc
        .perform(get("/api/hello"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RateLimit-Remaining", "9"));
  }

  @Test
  void rejectionFromTheCheckApiIsAnswered429() throws Exception {
    CHECK_RESPONSE.set(REJECTED);

    mockMvc
        .perform(get("/api/hello"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "30"));
  }

  @Test
  void excludedPathIsNotLimited() throws Exception {
    CHECK_RESPONSE.set(REJECTED);

    mockMvc.perform(get("/health")).andExpect(status().isOk());
  }

  private static HttpServer startCheckApi() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/ratelimit/check",
          exchange -> {
            byte[] body = CHECK_RESPONSE.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot start the stand-in rate limit check API", e);
    }
  }
}
