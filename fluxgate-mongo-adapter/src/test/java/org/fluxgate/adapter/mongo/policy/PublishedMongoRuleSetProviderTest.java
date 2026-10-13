package org.fluxgate.adapter.mongo.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Optional;
import org.bson.Document;
import org.fluxgate.core.key.LimitScopeKeyResolver;
import org.fluxgate.core.reload.CachingRuleSetProvider;
import org.fluxgate.core.reload.RuleCache;
import org.junit.jupiter.api.Test;

class PublishedMongoRuleSetProviderTest {
  @Test
  void authoritativeEmptyAclDoesNotInheritEmbeddedRuleAcl() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    Document policy = snapshot(1, "legacy");
    policy
        .getList("rules", Document.class)
        .get(0)
        .put("allowedKeys", Collections.singletonList("global"));
    policy.put("accessControl", new Document());
    when(repository.findActive("orders")).thenReturn(Optional.of(policy));
    PublishedMongoRuleSetProvider provider =
        new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
    assertThat(provider.findById("orders").orElseThrow().getAccessControl().isEmpty()).isTrue();
  }

  @Test
  void genericCacheCannotHideAuthoritativeRevision() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    when(repository.findActive("orders"))
        .thenReturn(Optional.of(snapshot(1, "legacy")), Optional.of(snapshot(2, "legacy")));
    PublishedMongoRuleSetProvider provider =
        new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
    RuleCache cache = mock(RuleCache.class);
    CachingRuleSetProvider wrapped = new CachingRuleSetProvider(provider, cache);
    assertThat(wrapped.findById("orders").orElseThrow().getRules().get(0).getAttributes())
        .containsEntry("fluxgate.counterRevision", 1L);
    assertThat(wrapped.findById("orders").orElseThrow().getRules().get(0).getAttributes())
        .containsEntry("fluxgate.counterRevision", 2L);
    verifyNoInteractions(cache);
  }

  @Test
  void readsAuthoritativeSnapshotOnEveryRequestAndAttachesServerCounterMetadata() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    when(repository.findActive("orders"))
        .thenReturn(Optional.of(snapshot(1, "legacy")), Optional.of(snapshot(2, "new-epoch")));
    PublishedMongoRuleSetProvider provider =
        new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
    assertThat(provider.findById("orders").orElseThrow().getRules().get(0).getAttributes())
        .containsEntry("fluxgate.counterRevision", 1L)
        .containsEntry("fluxgate.counterEpoch", "legacy");
    assertThat(provider.findById("orders").orElseThrow().getRules().get(0).getAttributes())
        .containsEntry("fluxgate.counterRevision", 2L)
        .containsEntry("fluxgate.counterEpoch", "new-epoch");
    verify(repository, times(2)).findActive("orders");
  }

  @Test
  void snapshotAclAndMatcherSurviveAndStoreFailureDoesNotReadDrafts() {
    MongoPolicyRepository repository = mock(MongoPolicyRepository.class);
    when(repository.findActive("orders")).thenReturn(Optional.of(snapshot(1, "legacy")));
    PublishedMongoRuleSetProvider provider =
        new PublishedMongoRuleSetProvider(repository, new LimitScopeKeyResolver(), () -> null);
    assertThat(
            provider
                .findById("orders")
                .orElseThrow()
                .getAccessControl()
                .getDeniedIps()
                .contains("10.1.2.3"))
        .isTrue();
    assertThat(
            provider
                .findById("orders")
                .orElseThrow()
                .getRules()
                .get(0)
                .getMatcher()
                .getPathPatterns())
        .contains("/api/**");
    when(repository.findActive("orders")).thenThrow(new IllegalStateException("Snapshot missing"));
    assertThatThrownBy(() -> provider.findById("orders")).hasMessageContaining("Snapshot missing");
  }

  private Document snapshot(long revision, String epoch) {
    Document band =
        new Document("windowSeconds", 60L).append("capacity", 10L).append("label", "stable");
    Document rule =
        new Document("id", "quota")
            .append("name", "Quota")
            .append("enabled", true)
            .append("scope", "GLOBAL")
            .append("keyStrategyId", "global")
            .append("onLimitExceedPolicy", "REJECT_REQUEST")
            .append("ruleSetId", "orders")
            .append("pathPatterns", Collections.singletonList("/api/**"))
            .append("bands", Collections.singletonList(band));
    return new Document("revision", revision)
        .append("counterEpoch", epoch)
        .append("accessControl", new Document("deniedIps", Collections.singletonList("10.0.0.0/8")))
        .append("rules", Collections.singletonList(rule));
  }
}
