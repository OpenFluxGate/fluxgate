package org.fluxgate.adapter.mongo.model;

import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;

import java.util.List;
import java.util.Objects;

public class RateLimitRuleDocument {

    private String id;
    private String name;
    private boolean enabled;
    private LimitScope scope;
    private String keyStrategyId;
    private OnLimitExceedPolicy onLimitExceedPolicy;
    private List<RateLimitBandDocument> bands;
    private String ruleSetId;

    protected RateLimitRuleDocument() {
    }

    public RateLimitRuleDocument(
            String id,
            String name,
            boolean enabled,
            LimitScope scope,
            String keyStrategyId,
            OnLimitExceedPolicy onLimitExceedPolicy,
            List<RateLimitBandDocument> bands,
            String ruleSetId
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.enabled = enabled;
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.keyStrategyId = Objects.requireNonNull(keyStrategyId, "keyStrategyId must not be null");
        this.onLimitExceedPolicy = Objects.requireNonNull(onLimitExceedPolicy, "onLimitExceedPolicy must not be null");
        this.bands = List.copyOf(Objects.requireNonNull(bands, "bands must not be null"));
        this.ruleSetId = Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LimitScope getScope() {
        return scope;
    }

    public String getKeyStrategyId() {
        return keyStrategyId;
    }

    public OnLimitExceedPolicy getOnLimitExceedPolicy() {
        return onLimitExceedPolicy;
    }

    public List<RateLimitBandDocument> getBands() {
        return bands;
    }

    public String getRuleSetId() {
        return ruleSetId;
    }
}
