package org.fluxgate.spring.autoconfigure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.metrics.RateLimitMetricsRecorder;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.fluxgate.core.spi.RateLimitRuleSetProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A {@link RateLimitRuleSetProvider} wrapper that lazily resolves the
 * {@link RateLimitMetricsRecorder} at runtime.
 *
 * <p>This solves the bean initialization order problem where the
 * CompositeMetricsRecorder may not be available when MongoRuleSetProvider
 * is created.
 *
 * <p>The metricsRecorder is resolved on the first call to {@link #findById(String)},
 * ensuring all Spring beans are fully initialized.
 */
class LazyMetricsMongoRuleSetProvider implements RateLimitRuleSetProvider {

    private static final Logger log = LoggerFactory.getLogger(LazyMetricsMongoRuleSetProvider.class);

    private final RateLimitRuleRepository ruleRepository;
    private final KeyResolver keyResolver;
    private final ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider;

    private volatile RateLimitMetricsRecorder resolvedRecorder;
    private volatile boolean recorderResolved = false;

    LazyMetricsMongoRuleSetProvider(
            RateLimitRuleRepository ruleRepository,
            KeyResolver keyResolver,
            ObjectProvider<RateLimitMetricsRecorder> metricsRecorderProvider) {
        this.ruleRepository = Objects.requireNonNull(ruleRepository, "ruleRepository must not be null");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver must not be null");
        this.metricsRecorderProvider = metricsRecorderProvider;
    }

    @Override
    public Optional<RateLimitRuleSet> findById(String ruleSetId) {
        List<RateLimitRule> rules = ruleRepository.findByRuleSetId(ruleSetId);

        if (rules.isEmpty()) {
            return Optional.empty();
        }

        RateLimitRuleSet.Builder builder = RateLimitRuleSet.builder(ruleSetId)
                .keyResolver(keyResolver)
                .rules(rules);

        // Lazily resolve the metrics recorder
        RateLimitMetricsRecorder recorder = getMetricsRecorder();
        if (recorder != null) {
            builder.metricsRecorder(recorder);
        }

        return Optional.of(builder.build());
    }

    /**
     * Lazily resolves the metrics recorder from the ObjectProvider.
     * Uses double-checked locking for thread safety.
     */
    private RateLimitMetricsRecorder getMetricsRecorder() {
        if (!recorderResolved) {
            synchronized (this) {
                if (!recorderResolved) {
                    resolvedRecorder = metricsRecorderProvider.getIfAvailable();
                    recorderResolved = true;
                    if (resolvedRecorder != null) {
                        log.info("Resolved metrics recorder: {}", resolvedRecorder.getClass().getSimpleName());
                    } else {
                        log.info("No metrics recorder available");
                    }
                }
            }
        }
        return resolvedRecorder;
    }
}
