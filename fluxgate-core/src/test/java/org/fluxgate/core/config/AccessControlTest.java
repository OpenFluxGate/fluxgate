package org.fluxgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.fluxgate.core.config.AccessControl.Decision;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.match.CidrSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AccessControl Tests")
@SuppressWarnings("deprecation") // also covers the legacy single-key evaluate(RateLimitKey)
class AccessControlTest {

  // ===== EMPTY constant =====

  @Test
  @DisplayName("EMPTY always returns NO_OPINION")
  void empty_alwaysNoOpinion() {
    assertThat(AccessControl.EMPTY.evaluate(RateLimitKey.of("ip:1.2.3.4")))
        .isEqualTo(Decision.NO_OPINION);
    assertThat(AccessControl.EMPTY.evaluate(RateLimitKey.of("user:alice")))
        .isEqualTo(Decision.NO_OPINION);
  }

  // ===== IP CIDR =====

  @Nested
  @DisplayName("IP CIDR matching")
  class IpCidrTests {

    @Test
    @DisplayName("IP in denied CIDR returns DENY")
    void deniedIpReturnsDeny() {
      AccessControl ac =
          AccessControl.builder()
              .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
              .build();
      assertThat(ac.evaluate(RateLimitKey.of("ip:10.0.0.5"))).isEqualTo(Decision.DENY);
    }

    @Test
    @DisplayName("IP in allowed CIDR returns ALLOW_BYPASS")
    void allowedIpReturnsAllowBypass() {
      AccessControl ac =
          AccessControl.builder()
              .allowedIps(CidrSet.of(Collections.singletonList("192.168.0.0/16")))
              .build();
      assertThat(ac.evaluate(RateLimitKey.of("ip:192.168.1.1"))).isEqualTo(Decision.ALLOW_BYPASS);
    }

    @Test
    @DisplayName("IP outside both lists returns NO_OPINION")
    void ipOutsideBothListsNoOpinion() {
      AccessControl ac =
          AccessControl.builder()
              .allowedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
              .deniedIps(CidrSet.of(Collections.singletonList("172.16.0.0/12")))
              .build();
      assertThat(ac.evaluate(RateLimitKey.of("ip:192.168.1.1"))).isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("DENY wins when IP is in both allow and deny lists")
    void denyWinsOverAllow_ip() {
      AccessControl ac =
          AccessControl.builder()
              .allowedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
              .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
              .build();
      assertThat(ac.evaluate(RateLimitKey.of("ip:10.0.0.1"))).isEqualTo(Decision.DENY);
    }
  }

  // ===== key matching =====

  @Nested
  @DisplayName("Key matching")
  class KeyTests {

    @Test
    @DisplayName("exact key in denied set returns DENY")
    void deniedKeyReturnsDeny() {
      AccessControl ac = AccessControl.builder().addDeniedKey("key:bad-actor").build();
      assertThat(ac.evaluate(RateLimitKey.of("key:bad-actor"))).isEqualTo(Decision.DENY);
    }

    @Test
    @DisplayName("exact key in allowed set returns ALLOW_BYPASS")
    void allowedKeyReturnsAllowBypass() {
      AccessControl ac = AccessControl.builder().addAllowedKey("user:alice").build();
      assertThat(ac.evaluate(RateLimitKey.of("user:alice"))).isEqualTo(Decision.ALLOW_BYPASS);
    }

    @Test
    @DisplayName("key not in any list returns NO_OPINION")
    void unknownKeyNoOpinion() {
      AccessControl ac =
          AccessControl.builder().addAllowedKey("user:alice").addDeniedKey("user:bob").build();
      assertThat(ac.evaluate(RateLimitKey.of("user:charlie"))).isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("DENY wins when key is in both allow and deny")
    void denyWinsOverAllow_key() {
      AccessControl ac =
          AccessControl.builder().addAllowedKey("user:alice").addDeniedKey("user:alice").build();
      assertThat(ac.evaluate(RateLimitKey.of("user:alice"))).isEqualTo(Decision.DENY);
    }
  }

  // ===== allowedKeys / deniedKeys bulk setters =====

  @Test
  @DisplayName("allowedKeys(Set) and deniedKeys(Set) bulk setters work")
  void bulkSetters() {
    AccessControl ac =
        AccessControl.builder()
            .allowedKeys(Collections.singleton("user:alice"))
            .deniedKeys(Collections.singleton("key:bad"))
            .build();
    assertThat(ac.evaluate(RateLimitKey.of("user:alice"))).isEqualTo(Decision.ALLOW_BYPASS);
    assertThat(ac.evaluate(RateLimitKey.of("key:bad"))).isEqualTo(Decision.DENY);
  }

  // ===== request-level evaluation (client IP + keys of all matching rules) =====

  @Nested
  @DisplayName("evaluate(clientIp, primaryKey, keys)")
  class RequestEvaluationTests {

    private final RateLimitKey ipKey = RateLimitKey.of("ip:1.2.3.4");
    private final RateLimitKey adminKey = RateLimitKey.of("user:admin");

    @Test
    @DisplayName("allowed key that is not the primary key does not bypass")
    void allowedNonPrimaryKey_noOpinion() {
      AccessControl ac = AccessControl.builder().addAllowedKey("user:admin").build();
      assertThat(ac.evaluate("1.2.3.4", ipKey, Arrays.asList(ipKey, adminKey)))
          .isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("allowed primary key bypasses")
    void allowedPrimaryKey_bypasses() {
      AccessControl ac = AccessControl.builder().addAllowedKey("user:admin").build();
      assertThat(ac.evaluate("1.2.3.4", adminKey, Arrays.asList(adminKey, ipKey)))
          .isEqualTo(Decision.ALLOW_BYPASS);
    }

    @Test
    @DisplayName("null primary key (unresolvable first rule) never bypasses by key")
    void nullPrimaryKey_noKeyBypass() {
      AccessControl ac = AccessControl.builder().addAllowedKey("user:admin").build();
      assertThat(ac.evaluate("1.2.3.4", null, Collections.singletonList(adminKey)))
          .isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("denied non-primary key beats allowed primary key")
    void deniedNonPrimaryKey_beatsAllowedPrimary() {
      AccessControl ac =
          AccessControl.builder().addAllowedKey("ip:1.2.3.4").addDeniedKey("user:admin").build();
      assertThat(ac.evaluate("1.2.3.4", ipKey, Arrays.asList(ipKey, adminKey)))
          .isEqualTo(Decision.DENY);
    }

    @Test
    @DisplayName("client IP lists apply regardless of key shape")
    void clientIpLists() {
      AccessControl allow =
          AccessControl.builder()
              .allowedIps(CidrSet.of(Collections.singletonList("1.2.3.0/24")))
              .build();
      AccessControl deny =
          AccessControl.builder()
              .deniedIps(CidrSet.of(Collections.singletonList("1.2.3.0/24")))
              .addAllowedKey("user:admin")
              .build();
      assertThat(allow.evaluate("1.2.3.4", adminKey, Collections.singletonList(adminKey)))
          .isEqualTo(Decision.ALLOW_BYPASS);
      assertThat(deny.evaluate("1.2.3.4", adminKey, Collections.singletonList(adminKey)))
          .isEqualTo(Decision.DENY);
    }
  }
}
