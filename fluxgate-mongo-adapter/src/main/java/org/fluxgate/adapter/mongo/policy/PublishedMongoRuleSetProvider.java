package org.fluxgate.adapter.mongo.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.fluxgate.adapter.mongo.converter.RateLimitRuleMongoConverter;
import org.fluxgate.core.config.AccessControl;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.match.CidrSet;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;

/** Authoritative published-policy reader. A notification is never required for freshness. */
public final class PublishedMongoRuleSetProvider implements RateLimitRuleSetProvider {
  private final MongoPolicyRepository repository;
  private final KeyResolver keyResolver;
  private final Supplier<RateLimitMetricsRecorder> recorder;

  public PublishedMongoRuleSetProvider(
      MongoPolicyRepository repository,
      KeyResolver keyResolver,
      Supplier<RateLimitMetricsRecorder> recorder) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
  }

  @Override
  public boolean requiresFreshRead() {
    return true;
  }

  @Override
  public Optional<RateLimitRuleSet> findById(String id) {
    return repository
        .findActive(id)
        .map(
            snapshot -> {
              List<RateLimitRule> rules = new ArrayList<>();
              for (Document raw : snapshot.getList("rules", Document.class)) {
                Document rule = new Document(raw);
                Document attributes = raw.get("attributes", Document.class);
                attributes = attributes == null ? new Document() : new Document(attributes);
                attributes.put(
                    "fluxgate.counterRevision", ((Number) snapshot.get("revision")).longValue());
                attributes.put("fluxgate.counterEpoch", snapshot.getString("counterEpoch"));
                rule.put("attributes", attributes);
                rules.add(
                    RateLimitRuleMongoConverter.toDomain(
                        RateLimitRuleMongoConverter.fromBson(rule)));
              }
              if (rules.isEmpty()) throw new IllegalStateException("Published policy has no rules");
              Document acl = snapshot.get("accessControl", Document.class);
              return RateLimitRuleSet.builder(id)
                  .keyResolver(keyResolver)
                  .rules(rules)
                  .accessControl(
                      AccessControl.builder()
                          .allowedIps(
                              CidrSet.of(acl.getList("allowedIps", String.class, List.of())))
                          .deniedIps(CidrSet.of(acl.getList("deniedIps", String.class, List.of())))
                          .allowedKeys(
                              new HashSet<>(acl.getList("allowedKeys", String.class, List.of())))
                          .deniedKeys(
                              new HashSet<>(acl.getList("deniedKeys", String.class, List.of())))
                          .build())
                  .metricsRecorder(recorder.get())
                  .build();
            });
  }
}
