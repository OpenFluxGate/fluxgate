package org.fluxgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import org.fluxgate.core.match.CidrSet;
import org.junit.jupiter.api.Test;

class AccessControlReloadEqualityTest {
  @Test
  void equivalentCidrPoliciesHaveValueEquality() {
    AccessControl first =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();
    AccessControl same =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();
    assertThat(first).isEqualTo(same);
    assertThat(first.hashCode()).isEqualTo(same.hashCode());
  }

  @Test
  void cidrOnlyChangeChangesPolicyEquality() {
    AccessControl first =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("10.0.0.0/8")))
            .build();
    AccessControl changed =
        AccessControl.builder()
            .deniedIps(CidrSet.of(Collections.singletonList("192.168.0.0/16")))
            .build();
    assertThat(first).isNotEqualTo(changed);
    assertThat(first.hashCode()).isNotEqualTo(changed.hashCode());
  }
}
