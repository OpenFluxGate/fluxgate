package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.lettuce.core.api.sync.RedisCommands;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link RedisRuleSetStore}. */
@ExtendWith(MockitoExtension.class)
class RedisRuleSetStoreTest {

  @Mock private RedisCommands<String, String> redisCommands;

  private RedisRuleSetStore store;

  @BeforeEach
  void setUp() {
    store = new RedisRuleSetStore(redisCommands);
  }

  @Test
  void shouldThrowWhenCommandsIsNull() {
    assertThatThrownBy(() -> new RedisRuleSetStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("commands must not be null");
  }

  @Test
  void shouldSaveRuleSetData() {
    RuleSetData data = new RuleSetData("test-rule", 100, 60, "clientIp");

    store.save(data);

    verify(redisCommands).hset(eq("fluxgate:ruleset:test-rule"), any(Map.class));
    verify(redisCommands).sadd("fluxgate:rulesets", "test-rule");
  }

  @Test
  void shouldSaveWithDefaultKeyStrategyWhenNull() {
    RuleSetData data = new RuleSetData("test-rule", 100, 60, null);

    store.save(data);

    verify(redisCommands).hset(eq("fluxgate:ruleset:test-rule"), any(Map.class));
  }

  @Test
  void shouldThrowWhenSavingNullData() {
    assertThatThrownBy(() -> store.save(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetData must not be null");
  }

  @Test
  void shouldThrowWhenSavingDataWithNullId() {
    RuleSetData data = new RuleSetData();

    assertThatThrownBy(() -> store.save(data))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetId must not be null");
  }

  @Test
  void shouldFindById() {
    Map<String, String> hash = new HashMap<>();
    hash.put("ruleSetId", "test-rule");
    hash.put("capacity", "100");
    hash.put("windowSeconds", "60");
    hash.put("keyStrategyId", "clientIp");
    hash.put("createdAt", "1234567890");

    when(redisCommands.hgetall("fluxgate:ruleset:test-rule")).thenReturn(hash);

    Optional<RuleSetData> result = store.findById("test-rule");

    assertThat(result).isPresent();
    assertThat(result.get().getRuleSetId()).isEqualTo("test-rule");
    assertThat(result.get().getCapacity()).isEqualTo(100);
    assertThat(result.get().getWindowSeconds()).isEqualTo(60);
    assertThat(result.get().getKeyStrategyId()).isEqualTo("clientIp");
    assertThat(result.get().getCreatedAt()).isEqualTo(1234567890L);
  }

  @Test
  void shouldReturnEmptyWhenNotFound() {
    when(redisCommands.hgetall("fluxgate:ruleset:missing")).thenReturn(Collections.emptyMap());

    Optional<RuleSetData> result = store.findById("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void shouldReturnEmptyWhenHashIsNull() {
    when(redisCommands.hgetall("fluxgate:ruleset:missing")).thenReturn(null);

    Optional<RuleSetData> result = store.findById("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void shouldUseDefaultValuesWhenFieldsMissing() {
    Map<String, String> hash = new HashMap<>();
    hash.put("ruleSetId", "test-rule");

    when(redisCommands.hgetall("fluxgate:ruleset:test-rule")).thenReturn(hash);

    Optional<RuleSetData> result = store.findById("test-rule");

    assertThat(result).isPresent();
    assertThat(result.get().getCapacity()).isEqualTo(10);
    assertThat(result.get().getWindowSeconds()).isEqualTo(60);
    assertThat(result.get().getKeyStrategyId()).isEqualTo("clientIp");
    assertThat(result.get().getCreatedAt()).isZero();
  }

  @Test
  void shouldThrowWhenFindByIdWithNullId() {
    assertThatThrownBy(() -> store.findById(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetId must not be null");
  }

  @Test
  void shouldDeleteExistingRuleSet() {
    when(redisCommands.del("fluxgate:ruleset:test-rule")).thenReturn(1L);

    boolean result = store.delete("test-rule");

    assertThat(result).isTrue();
    verify(redisCommands).del("fluxgate:ruleset:test-rule");
    verify(redisCommands).srem("fluxgate:rulesets", "test-rule");
  }

  @Test
  void shouldReturnFalseWhenDeletingNonExistent() {
    when(redisCommands.del("fluxgate:ruleset:missing")).thenReturn(0L);

    boolean result = store.delete("missing");

    assertThat(result).isFalse();
  }

  @Test
  void shouldThrowWhenDeleteWithNullId() {
    assertThatThrownBy(() -> store.delete(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ruleSetId must not be null");
  }

  @Test
  void shouldListAllIds() {
    Set<String> ids = new HashSet<>(Arrays.asList("rule1", "rule2", "rule3"));
    when(redisCommands.smembers("fluxgate:rulesets")).thenReturn(ids);

    List<String> result = store.listAllIds();

    assertThat(result).containsExactlyInAnyOrder("rule1", "rule2", "rule3");
  }

  @Test
  void shouldReturnEmptyListWhenNoIds() {
    when(redisCommands.smembers("fluxgate:rulesets")).thenReturn(null);

    List<String> result = store.listAllIds();

    assertThat(result).isEmpty();
  }

  @Test
  void shouldFindAll() {
    Set<String> ids = new HashSet<>(Arrays.asList("rule1", "rule2"));
    when(redisCommands.smembers("fluxgate:rulesets")).thenReturn(ids);

    Map<String, String> hash1 = new HashMap<>();
    hash1.put("ruleSetId", "rule1");
    hash1.put("capacity", "100");
    hash1.put("windowSeconds", "60");

    Map<String, String> hash2 = new HashMap<>();
    hash2.put("ruleSetId", "rule2");
    hash2.put("capacity", "200");
    hash2.put("windowSeconds", "120");

    when(redisCommands.hgetall("fluxgate:ruleset:rule1")).thenReturn(hash1);
    when(redisCommands.hgetall("fluxgate:ruleset:rule2")).thenReturn(hash2);

    List<RuleSetData> result = store.findAll();

    assertThat(result).hasSize(2);
  }

  @Test
  void shouldCheckExists() {
    when(redisCommands.exists("fluxgate:ruleset:test-rule")).thenReturn(1L);
    when(redisCommands.exists("fluxgate:ruleset:missing")).thenReturn(0L);

    assertThat(store.exists("test-rule")).isTrue();
    assertThat(store.exists("missing")).isFalse();
  }

  @Test
  void shouldClearAll() {
    Set<String> ids = new HashSet<>(Arrays.asList("rule1", "rule2"));
    when(redisCommands.smembers("fluxgate:rulesets")).thenReturn(ids);

    store.clearAll();

    verify(redisCommands).del("fluxgate:ruleset:rule1");
    verify(redisCommands).del("fluxgate:ruleset:rule2");
    verify(redisCommands).del("fluxgate:rulesets");
  }
}
