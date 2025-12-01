package org.fluxgate.adapter.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.fluxgate.adapter.mongo.model.RateLimitBandDocument;
import org.fluxgate.adapter.mongo.model.RateLimitRuleDocument;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.adapter.mongo.rule.MongoRuleSetProvider;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.OnLimitExceedPolicy;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.key.KeyResolver;
import org.fluxgate.core.context.RequestContext;
import org.fluxgate.core.key.RateLimitKey;
import org.fluxgate.core.ratelimiter.RateLimitRuleSet;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MongoRuleSetProviderIntegrationTest {

    private static final String MONGO_URI =
            System.getProperty(
                    "fluxgate.mongo.uri",
                    System.getenv().getOrDefault("FLUXGATE_MONGO_URI",
                            "mongodb://fluxgate:fluxgate123@localhost:27017/fluxgate?authSource=admin")
            );

    private static final String DB_NAME =
            System.getProperty(
                    "fluxgate.mongo.db",
                    System.getenv().getOrDefault("FLUXGATE_MONGO_DB",
                            "fluxgate")
            );

    private static final String RULE_COLLECTION = "rate_limit_rules";

    private MongoClient client;
    private MongoDatabase database;
    private MongoCollection<Document> ruleCollection;
    private MongoRateLimitRuleRepository repository;
    private MongoRuleSetProvider provider;

    @BeforeEach
    void setUp() {
        // Connect to local MongoDB (docker or local)
        client = MongoClients.create(MONGO_URI);
        database = client.getDatabase(DB_NAME);
        ruleCollection = database.getCollection(RULE_COLLECTION);

        // Start with a clean state
        ruleCollection.drop();

        repository = new MongoRateLimitRuleRepository(ruleCollection);
        provider = new MongoRuleSetProvider(repository, new TestKeyResolver());
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Return optional.empty() when do not exist ruleSetId")
    void findById_shouldReturnEmptyIfNotExists() {
        String ruleSetId = "not-exist";
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);
        assertTrue(maybeRuleSet.isEmpty());
    }

    @Test
    @DisplayName("Can load ruleset from Mongo by ruleSetId")
    void findById_shouldLoadRuleSetFromMongo() {
        // given
        String ruleSetId = "test-rule-set";

        // Create a band
        RateLimitBandDocument bandDoc =
                new RateLimitBandDocument(1L, 100L, "per-second");

        // Create a rule document
        RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                "rule-1",
                "Test Rule",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(bandDoc),
                ruleSetId
        );

        // Upsert to Mongo (repository converts to BSON internally)
        repository.upsert(ruleDoc);

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent(), "RuleSet must exist");

        RateLimitRuleSet ruleSet = maybeRuleSet.get();

        // Verify RuleSet properties
        // Assumes "id + rules" structure
        // (Adjust method names if your API differs)
        assertEquals(ruleSetId, ruleSet.getId(), "RuleSet id must match");
        assertEquals(1, ruleSet.getRules().size(), "Should have 1 rule");

        RateLimitRule rule = ruleSet.getRules().get(0);
        assertEquals("rule-1", rule.getId());
        assertEquals("Test Rule", rule.getName());
        assertTrue(rule.isEnabled());
        assertEquals(LimitScope.PER_API_KEY, rule.getScope());
        assertEquals("apiKey", rule.getKeyStrategyId());
        assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, rule.getOnLimitExceedPolicy());
        assertEquals(ruleSetId, rule.getRuleSetIdOrNull());

        assertEquals(1, rule.getBands().size(), "Should have 1 band");
        RateLimitBand band = rule.getBands().get(0);

        // Verify band properties (adjust method names to match your RateLimitBand API)
        assertEquals(100L, band.getCapacity());
        assertEquals(Duration.ofSeconds(1), band.getWindow());
        assertEquals("per-second", band.getLabel());
    }

    @Test
    @DisplayName("Should return empty Optional when ruleSetId does not exist")
    void findById_shouldReturnEmptyWhenRuleSetNotFound() {
        // given
        String nonExistentRuleSetId = "non-existent-ruleset";

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(nonExistentRuleSetId);

        // then
        assertFalse(maybeRuleSet.isPresent(), "RuleSet should not exist");
    }

    @Test
    @DisplayName("Can load ruleset with multiple rules")
    void findById_shouldLoadRuleSetWithMultipleRules() {
        // given
        String ruleSetId = "multi-rule-set";

        RateLimitBandDocument band1 = new RateLimitBandDocument(1L, 100L, "per-second");
        RateLimitBandDocument band2 = new RateLimitBandDocument(60L, 1000L, "per-minute");

        RateLimitRuleDocument rule1 = new RateLimitRuleDocument(
                "rule-1",
                "IP Rate Limit",
                true,
                LimitScope.PER_IP,
                "ip",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band1),
                ruleSetId
        );

        RateLimitRuleDocument rule2 = new RateLimitRuleDocument(
                "rule-2",
                "User Rate Limit",
                true,
                LimitScope.PER_USER,
                "userId",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band2),
                ruleSetId
        );

        repository.upsert(rule1);
        repository.upsert(rule2);

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent());
        RateLimitRuleSet ruleSet = maybeRuleSet.get();
        assertEquals(2, ruleSet.getRules().size(), "Should have 2 rules");

        // Verify first rule
        RateLimitRule loadedRule1 = ruleSet.getRules().stream()
                .filter(r -> r.getId().equals("rule-1"))
                .findFirst()
                .orElseThrow();
        assertEquals("IP Rate Limit", loadedRule1.getName());
        assertEquals(LimitScope.PER_IP, loadedRule1.getScope());

        // Verify second rule
        RateLimitRule loadedRule2 = ruleSet.getRules().stream()
                .filter(r -> r.getId().equals("rule-2"))
                .findFirst()
                .orElseThrow();
        assertEquals("User Rate Limit", loadedRule2.getName());
        assertEquals(LimitScope.PER_USER, loadedRule2.getScope());
    }

    @Test
    @DisplayName("Can load rule with multiple bands (multi-band rate limiting)")
    void findById_shouldLoadRuleWithMultipleBands() {
        // given
        String ruleSetId = "multi-band-set";

        RateLimitBandDocument band1 = new RateLimitBandDocument(1L, 10L, "10 per second");
        RateLimitBandDocument band2 = new RateLimitBandDocument(60L, 100L, "100 per minute");
        RateLimitBandDocument band3 = new RateLimitBandDocument(3600L, 1000L, "1000 per hour");

        RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                "multi-band-rule",
                "Multi-Band Rule",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band1, band2, band3),
                ruleSetId
        );

        repository.upsert(ruleDoc);

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent());
        RateLimitRule rule = maybeRuleSet.get().getRules().get(0);
        assertEquals(3, rule.getBands().size(), "Should have 3 bands");

        // Verify each band
        List<RateLimitBand> bands = rule.getBands();
        assertEquals(10L, bands.get(0).getCapacity());
        assertEquals(Duration.ofSeconds(1), bands.get(0).getWindow());

        assertEquals(100L, bands.get(1).getCapacity());
        assertEquals(Duration.ofSeconds(60), bands.get(1).getWindow());

        assertEquals(1000L, bands.get(2).getCapacity());
        assertEquals(Duration.ofSeconds(3600), bands.get(2).getWindow());
    }

    @Test
    @DisplayName("Can load disabled rules")
    void findById_shouldLoadDisabledRules() {
        // given
        String ruleSetId = "disabled-rule-set";

        RateLimitBandDocument band = new RateLimitBandDocument(1L, 100L, "per-second");

        RateLimitRuleDocument disabledRule = new RateLimitRuleDocument(
                "disabled-rule",
                "Disabled Rule",
                false,  // disabled
                LimitScope.GLOBAL,
                "global",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band),
                ruleSetId
        );

        repository.upsert(disabledRule);

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent());
        RateLimitRule rule = maybeRuleSet.get().getRules().get(0);
        assertFalse(rule.isEnabled(), "Rule should be disabled");
        assertEquals(LimitScope.GLOBAL, rule.getScope());
    }

    @Test
    @DisplayName("Repository upsert should insert new rule")
    void repository_upsertShouldInsertNewRule() {
        // given
        RateLimitBandDocument band = new RateLimitBandDocument(1L, 50L, "test");
        RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                "new-rule",
                "New Rule",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band),
                "test-set"
        );

        // when
        repository.upsert(ruleDoc);

        // then - use domain object API
        List<RateLimitRule> found = repository.findByRuleSetId("test-set");
        assertEquals(1, found.size());
        assertEquals("new-rule", found.get(0).getId());
    }

    @Test
    @DisplayName("Repository upsert should update existing rule")
    void repository_upsertShouldUpdateExistingRule() {
        // given
        RateLimitBandDocument band1 = new RateLimitBandDocument(1L, 50L, "initial");
        RateLimitRuleDocument initialRule = new RateLimitRuleDocument(
                "update-rule",
                "Initial Name",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band1),
                "update-set"
        );
        repository.upsert(initialRule);

        // when - update with same id but different name
        RateLimitBandDocument band2 = new RateLimitBandDocument(1L, 100L, "updated");
        RateLimitRuleDocument updatedRule = new RateLimitRuleDocument(
                "update-rule",  // same id
                "Updated Name",  // different name
                true,
                LimitScope.PER_IP,  // different scope
                "ip",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band2),
                "update-set"
        );
        repository.upsert(updatedRule);

        // then - use domain object API
        List<RateLimitRule> found = repository.findByRuleSetId("update-set");
        assertEquals(1, found.size(), "Should still have only 1 rule");
        assertEquals("Updated Name", found.get(0).getName());
        assertEquals(LimitScope.PER_IP, found.get(0).getScope());
        assertEquals(100L, found.get(0).getBands().get(0).getCapacity());
    }

    @Test
    @DisplayName("Repository deleteById should remove rule")
    void repository_deleteByIdShouldRemoveRule() {
        // given
        RateLimitBandDocument band = new RateLimitBandDocument(1L, 50L, "test");
        RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                "delete-me",
                "To Be Deleted",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band),
                "delete-set"
        );
        repository.upsert(ruleDoc);

        // Verify it exists
        assertEquals(1, repository.findByRuleSetId("delete-set").size());

        // when
        repository.deleteById("delete-me");

        // then
        assertEquals(0, repository.findByRuleSetId("delete-set").size());
    }

    @Test
    @DisplayName("Can load rules with different OnLimitExceedPolicy values")
    void findById_shouldLoadRulesWithDifferentPolicies() {
        // given
        String ruleSetId = "policy-test-set";

        RateLimitBandDocument band = new RateLimitBandDocument(1L, 100L, "per-second");

        // Note: Currently only REJECT_REQUEST is available in OnLimitExceedPolicy
        // This test demonstrates that the policy is correctly persisted and loaded
        RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                "policy-rule",
                "Policy Test Rule",
                true,
                LimitScope.PER_API_KEY,
                "apiKey",
                OnLimitExceedPolicy.REJECT_REQUEST,
                List.of(band),
                ruleSetId
        );

        repository.upsert(ruleDoc);

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent());
        RateLimitRule rule = maybeRuleSet.get().getRules().get(0);
        assertEquals(OnLimitExceedPolicy.REJECT_REQUEST, rule.getOnLimitExceedPolicy());
    }

    @Test
    @DisplayName("Can load rules with all LimitScope types")
    void findById_shouldLoadRulesWithAllScopeTypes() {
        // given
        String ruleSetId = "scope-test-set";
        RateLimitBandDocument band = new RateLimitBandDocument(1L, 100L, "per-second");

        // Test each scope type
        LimitScope[] scopes = {
                LimitScope.GLOBAL,
                LimitScope.PER_IP,
                LimitScope.PER_USER,
                LimitScope.PER_API_KEY
        };

        for (int i = 0; i < scopes.length; i++) {
            RateLimitRuleDocument ruleDoc = new RateLimitRuleDocument(
                    "scope-rule-" + i,
                    "Scope Test " + scopes[i],
                    true,
                    scopes[i],
                    "key-" + i,
                    OnLimitExceedPolicy.REJECT_REQUEST,
                    List.of(band),
                    ruleSetId
            );
            repository.upsert(ruleDoc);
        }

        // when
        Optional<RateLimitRuleSet> maybeRuleSet = provider.findById(ruleSetId);

        // then
        assertTrue(maybeRuleSet.isPresent());
        RateLimitRuleSet ruleSet = maybeRuleSet.get();
        assertEquals(4, ruleSet.getRules().size());

        // Verify each scope type is present
        for (LimitScope scope : scopes) {
            assertTrue(
                    ruleSet.getRules().stream().anyMatch(r -> r.getScope() == scope),
                    "Should have rule with scope: " + scope
            );
        }
    }

    /**
     * Test KeyResolver implementation
     * Adjust method signature to match the actual interface if needed.
     */
    private static class TestKeyResolver implements KeyResolver {

        @Override
        public RateLimitKey resolve(RequestContext context) {
            return new RateLimitKey("test-key");
        }
    }
}
