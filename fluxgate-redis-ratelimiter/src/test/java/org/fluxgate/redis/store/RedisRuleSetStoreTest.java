package org.fluxgate.redis.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.*;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link RedisRuleSetStore}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRuleSetStoreTest {

  @Mock private RedisConnectionProvider connectionProvider;

  private RedisRuleSetStore store;

  @BeforeEach
  void setUp() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    store = new RedisRuleSetStore(connectionProvider);
  }

  @Test
  void shouldThrowWhenConnectionProviderIsNull() {
    assertThatThrownBy(() -> new RedisRuleSetStore(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("connectionProvider must not be null");
  }

  @Test
  void shouldSaveRuleSetData() {
    RuleSetData data = new RuleSetData("test-rule", 100, 60, "clientIp");

    store.save(data);

    verify(connectionProvider).hset(eq("fluxgate:ruleset:test-rule"), any(Map.class));
    verify(connectionProvider).sadd("fluxgate:rulesets", "test-rule");
  }

  @Test
  void shouldSaveWithDefaultKeyStrategyWhenNull() {
    RuleSetData data = new RuleSetData("test-rule", 100, 60, null);

    store.save(data);

    verify(connectionProvider).hset(eq("fluxgate:ruleset:test-rule"), any(Map.class));
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

    when(connectionProvider.hgetall("fluxgate:ruleset:test-rule")).thenReturn(hash);

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
    when(connectionProvider.hgetall("fluxgate:ruleset:missing")).thenReturn(Collections.emptyMap());

    Optional<RuleSetData> result = store.findById("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void shouldReturnEmptyWhenHashIsNull() {
    when(connectionProvider.hgetall("fluxgate:ruleset:missing")).thenReturn(null);

    Optional<RuleSetData> result = store.findById("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void shouldUseDefaultValuesWhenFieldsMissing() {
    Map<String, String> hash = new HashMap<>();
    hash.put("ruleSetId", "test-rule");

    when(connectionProvider.hgetall("fluxgate:ruleset:test-rule")).thenReturn(hash);

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
    when(connectionProvider.del("fluxgate:ruleset:test-rule")).thenReturn(1L);

    boolean result = store.delete("test-rule");

    assertThat(result).isTrue();
    verify(connectionProvider).del("fluxgate:ruleset:test-rule");
    verify(connectionProvider).srem("fluxgate:rulesets", "test-rule");
  }

  @Test
  void shouldReturnFalseWhenDeletingNonExistent() {
    when(connectionProvider.del("fluxgate:ruleset:missing")).thenReturn(0L);

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
    when(connectionProvider.smembers("fluxgate:rulesets")).thenReturn(ids);

    List<String> result = store.listAllIds();

    assertThat(result).containsExactlyInAnyOrder("rule1", "rule2", "rule3");
  }

  @Test
  void shouldReturnEmptyListWhenNoIds() {
    when(connectionProvider.smembers("fluxgate:rulesets")).thenReturn(null);

    List<String> result = store.listAllIds();

    assertThat(result).isEmpty();
  }

  @Test
  void shouldFindAll() {
    Set<String> ids = new HashSet<>(Arrays.asList("rule1", "rule2"));
    when(connectionProvider.smembers("fluxgate:rulesets")).thenReturn(ids);

    Map<String, String> hash1 = new HashMap<>();
    hash1.put("ruleSetId", "rule1");
    hash1.put("capacity", "100");
    hash1.put("windowSeconds", "60");

    Map<String, String> hash2 = new HashMap<>();
    hash2.put("ruleSetId", "rule2");
    hash2.put("capacity", "200");
    hash2.put("windowSeconds", "120");

    when(connectionProvider.hgetall("fluxgate:ruleset:rule1")).thenReturn(hash1);
    when(connectionProvider.hgetall("fluxgate:ruleset:rule2")).thenReturn(hash2);

    List<RuleSetData> result = store.findAll();

    assertThat(result).hasSize(2);
  }

  @Test
  void shouldCheckExists() {
    when(connectionProvider.exists("fluxgate:ruleset:test-rule")).thenReturn(true);
    when(connectionProvider.exists("fluxgate:ruleset:missing")).thenReturn(false);

    assertThat(store.exists("test-rule")).isTrue();
    assertThat(store.exists("missing")).isFalse();
  }

  @Test
  void shouldClearAll() {
    Set<String> ids = new HashSet<>(Arrays.asList("rule1", "rule2"));
    when(connectionProvider.smembers("fluxgate:rulesets")).thenReturn(ids);

    store.clearAll();

    verify(connectionProvider).del("fluxgate:ruleset:rule1");
    verify(connectionProvider).del("fluxgate:ruleset:rule2");
    verify(connectionProvider).del("fluxgate:rulesets");
  }

  @Test
  void shouldReturnCorrectMode() {
    when(connectionProvider.getMode()).thenReturn(RedisMode.STANDALONE);
    assertThat(store.getMode()).isEqualTo(RedisMode.STANDALONE);

    when(connectionProvider.getMode()).thenReturn(RedisMode.CLUSTER);
    assertThat(store.getMode()).isEqualTo(RedisMode.CLUSTER);
  }
}
