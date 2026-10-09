package org.fluxgate.envoy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("acl-demo")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"fluxgate.redis.uri=redis://127.0.0.1:1", "fluxgate.redis.timeout-ms=100"})
class EnvoyAuthzAclApplicationTest {
  @Autowired private TestRestTemplate http;

  @Test
  void optionsRespectsAclDeny() {
    ResponseEntity<Void> response =
        http.exchange("/authz/api/items", HttpMethod.OPTIONS, null, Void.class);
    assertThat(response.getStatusCode().value()).isEqualTo(403);
  }
}
