package org.fluxgate.sample.mongo.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Optional;
import org.fluxgate.adapter.mongo.repository.MongoRateLimitRuleRepository;
import org.fluxgate.core.config.LimitScope;
import org.fluxgate.core.config.RateLimitBand;
import org.fluxgate.core.config.RateLimitRule;
import org.fluxgate.core.spi.RateLimitRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The single-rule endpoints address a rule by {@code (ruleSetId, id)}, never by id alone. */
class RuleAdminControllerTest {

  private MongoRateLimitRuleRepository repository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    repository = mock(MongoRateLimitRuleRepository.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new RuleAdminController(repository)).build();
  }

  private static RateLimitRule rule(String ruleSetId, String id) {
    return RateLimitRule.builder(id)
        .name(id)
        .scope(LimitScope.PER_IP)
        .keyStrategyId("clientIp")
        .ruleSetId(ruleSetId)
        .addBand(RateLimitBand.builder(Duration.ofMinutes(1), 10).build())
        .build();
  }

  @Test
  void getRule_looksUpByRuleSetAndId() throws Exception {
    when(repository.findById("set-a", "r1")).thenReturn(Optional.of(rule("set-a", "r1")));

    mockMvc
        .perform(get("/admin/rules/set-a/r1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("r1"))
        .andExpect(jsonPath("$.ruleSetIdOrNull").value("set-a"));
  }

  @Test
  void getRule_missing_isNotFound() throws Exception {
    when(repository.findById("set-a", "nope")).thenReturn(Optional.empty());

    mockMvc.perform(get("/admin/rules/set-a/nope")).andExpect(status().isNotFound());
  }

  @Test
  void deleteRule_deletesByRuleSetAndId() throws Exception {
    when(repository.deleteById("set-a", "r1")).thenReturn(true);

    mockMvc.perform(delete("/admin/rules/set-a/r1")).andExpect(status().isNoContent());
    verify(repository).deleteById("set-a", "r1");
  }

  @Test
  void deleteRule_missing_isNotFound() throws Exception {
    when(repository.deleteById("set-a", "nope")).thenReturn(false);

    mockMvc.perform(delete("/admin/rules/set-a/nope")).andExpect(status().isNotFound());
  }

  @Test
  void requiresTheMongoRepository() {
    assertThatThrownBy(() -> new RuleAdminController(mock(RateLimitRuleRepository.class)))
        .isInstanceOf(IllegalStateException.class);
  }
}
