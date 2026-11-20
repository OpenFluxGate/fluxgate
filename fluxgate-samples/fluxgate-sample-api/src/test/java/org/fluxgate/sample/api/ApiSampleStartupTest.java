package org.fluxgate.sample.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.fluxgate.sample.api.config.ServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The sample starts with its shipped {@code application.yml}, as {@code ./mvnw spring-boot:run}
 * does. It only proxies to the other samples, so no backing store is needed.
 */
@SpringBootTest
class ApiSampleStartupTest {

  @Autowired private ServiceProperties serviceProperties;

  @Test
  void startsWithTheShippedConfiguration() {
    assertThat(serviceProperties).isNotNull();
  }
}
