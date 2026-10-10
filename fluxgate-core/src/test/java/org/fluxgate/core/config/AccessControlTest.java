package org.fluxgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import org.fluxgate.core.config.AccessControl.Decision;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.LimitScopeKeyResolver;
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

  // ===== configured keys are normalised like resolved keys =====

  @Nested
  @DisplayName("Key normalisation")
  class KeyNormalisationTests {

    private final LimitScopeKeyResolver resolver = new LimitScopeKeyResolver();

    private RateLimitKey resolveUser(String userId) {
      return resolver.resolve(
          RequestContext.builder().userId(userId).build(),
          RateLimitRule.builder("r")
              .scope(LimitScope.PER_USER)
              .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
              .build());
    }

    @Test
    @DisplayName("a denied key with characters the resolver encodes still denies that identity")
    void deniedKeyMatchesTheResolvedForm() {
      AccessControl ac = AccessControl.builder().addDeniedKey("user:a+1").build();
      RateLimitKey denied = resolveUser("a+1");
      RateLimitKey lookAlike = resolveUser("a_1");

      assertThat(ac.evaluate("192.0.2.1", denied, Collections.singletonList(denied)))
          .isEqualTo(Decision.DENY);
      assertThat(ac.evaluate("192.0.2.1", lookAlike, Collections.singletonList(lookAlike)))
          .isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("allowed keys are normalised through the bulk setter too")
    void allowedKeysAreNormalised() {
      AccessControl ac =
          AccessControl.builder().allowedKeys(Collections.singleton("user:a b")).build();
      RateLimitKey allowed = resolveUser("a b");

      assertThat(ac.getAllowedKeys()).containsExactly(allowed.value());
      assertThat(ac.evaluate("192.0.2.1", allowed, Collections.singletonList(allowed)))
          .isEqualTo(Decision.ALLOW_BYPASS);
    }

    @Test
    @DisplayName("entries already in encoded form pass through unchanged")
    void encodedEntriesPassThrough() {
      RateLimitKey rewritten = resolveUser("a+1");
      RateLimitKey hashed = resolveUser("x".repeat(300));
      String bareShort = RateLimitKey.of("a b").value();
      String bareLong = RateLimitKey.of("y".repeat(300)).value();

      AccessControl ac =
          AccessControl.builder()
              .addDeniedKey(rewritten.value())
              .addDeniedKey(hashed.value())
              .addAllowedKey(bareShort)
              .addAllowedKey(bareLong)
              .build();

      assertThat(ac.getDeniedKeys()).containsExactlyInAnyOrder(rewritten.value(), hashed.value());
      assertThat(ac.getAllowedKeys()).containsExactlyInAnyOrder(bareShort, bareLong);
      assertThat(ac.evaluate("192.0.2.1", rewritten, Collections.singletonList(rewritten)))
          .isEqualTo(Decision.DENY);
      assertThat(ac.evaluate("192.0.2.1", hashed, Collections.singletonList(hashed)))
          .isEqualTo(Decision.DENY);
    }

    @Test
    @DisplayName("normalising a configured key twice gives the same result")
    void normalisationIsIdempotent() {
      for (String raw :
          new String[] {
            "user:alice",
            "user:a+1",
            "user:" + "z".repeat(300),
            "a b",
            "w".repeat(300),
            "custom:h:x",
            "key:h:" + "0".repeat(64),
            "ip:",
            "h:"
          }) {
        String once = LimitScopeKeyResolver.normalizeResolvedKey(raw);
        assertThat(LimitScopeKeyResolver.normalizeResolvedKey(once)).as(raw).isEqualTo(once);
      }
    }

    @Test
    @DisplayName("a raw value that only looks encoded is still told apart from the real encoding")
    void lookAlikeOfEncodingIsNotForged() {
      // a user whose raw id is literally h:a_1:<16 hex> resolves to a re-encoded key, so a
      // pass-through entry never matches it by accident
      String encodedValue = RateLimitKey.of("user:", "a+1").value().substring("user:".length());
      RateLimitKey forger = resolveUser(encodedValue);

      AccessControl ac = AccessControl.builder().addDeniedKey("user:" + encodedValue).build();

      assertThat(forger.value()).isNotEqualTo("user:" + encodedValue);
      assertThat(ac.evaluate("192.0.2.1", forger, Collections.singletonList(forger)))
          .isEqualTo(Decision.NO_OPINION);
    }

    @Test
    @DisplayName("clean keys are kept as configured")
    void cleanKeysAreUnchanged() {
      AccessControl ac =
          AccessControl.builder().addDeniedKey("user:alice").addAllowedKey("global").build();

      assertThat(ac.getDeniedKeys()).containsExactly("user:alice");
      assertThat(ac.getAllowedKeys()).containsExactly("global");
    }

    @Test
    @DisplayName("a custom prefix matches raw when the value is clean, encoded otherwise")
    void customPrefixEntries() {
      // custom prefixes are unknown to the normaliser: tenant:a+1 could be of("tenant:", "a+1")
      // or of("tenant:a+1"), so such an entry is sanitised as a whole
      RateLimitKey clean = RateLimitKey.of("tenant:", "acme");
      RateLimitKey rewritten = RateLimitKey.of("tenant:", "a+1");
      RateLimitKey whole = RateLimitKey.of("tenant:a+1");

      AccessControl raw =
          AccessControl.builder().addDeniedKey("tenant:acme").addDeniedKey("tenant:a+1").build();
      AccessControl encoded = AccessControl.builder().addDeniedKey(rewritten.value()).build();

      assertThat(raw.evaluate("192.0.2.1", clean, Collections.singletonList(clean)))
          .isEqualTo(Decision.DENY);
      assertThat(raw.evaluate("192.0.2.1", rewritten, Collections.singletonList(rewritten)))
          .isEqualTo(Decision.NO_OPINION);
      assertThat(raw.evaluate("192.0.2.1", whole, Collections.singletonList(whole)))
          .isEqualTo(Decision.DENY);
      assertThat(encoded.getDeniedKeys()).containsExactly(rewritten.value());
      assertThat(encoded.evaluate("192.0.2.1", rewritten, Collections.singletonList(rewritten)))
          .isEqualTo(Decision.DENY);
    }
  }

  @Test
  @DisplayName("equals and hashCode take the IP lists into account")
  void equalsIncludesIpLists() {
    AccessControl tenSlash8 =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();
    AccessControl sameDenied =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();
    AccessControl otherDenied =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("192.168.0.0/16")))
            .build();
    AccessControl allowedInstead =
        AccessControl.builder()
            .allowedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();

    assertThat(tenSlash8).isEqualTo(sameDenied).hasSameHashCodeAs(sameDenied);
    assertThat(tenSlash8).isNotEqualTo(otherDenied);
    assertThat(tenSlash8).isNotEqualTo(allowedInstead);
    assertThat(tenSlash8).isNotEqualTo(AccessControl.EMPTY);
  }
}
