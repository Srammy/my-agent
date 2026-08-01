package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisBaseStoreTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  @Test
  void searchTreatsSecondArgumentAsLimitAndThirdArgumentAsOffset() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    String namespacePrefix = "prefix:" + encode("workspace") + ":";
    String keyA = namespacePrefix + encode("a.txt");
    String keyB = namespacePrefix + encode("b.txt");
    String keyC = namespacePrefix + encode("c.txt");

    when(redisTemplate.keys(namespacePrefix + "*"))
        .thenReturn(java.util.Set.of(keyC, keyA, keyB));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(keyA)).thenReturn(itemJson("A", 1));
    when(valueOperations.get(keyB)).thenReturn(itemJson("B", 2));
    when(valueOperations.get(keyC)).thenReturn(itemJson("C", 3));

    List<StoreItem> result = store.search(List.of("workspace"), 2, 1);

    assertThat(result).extracting(StoreItem::key).containsExactly("b.txt", "c.txt");
    assertThat(result).extracting(StoreItem::version).containsExactly(2L, 3L);
  }

  @Test
  void searchSkipsAKeyDeletedAfterEnumeration() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    String namespacePrefix = "prefix:" + encode("workspace") + ":";
    String existingKey = namespacePrefix + encode("existing.txt");
    String deletedKey = namespacePrefix + encode("deleted.txt");

    when(redisTemplate.keys(namespacePrefix + "*"))
        .thenReturn(java.util.Set.of(existingKey, deletedKey));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(existingKey)).thenReturn(itemJson("existing", 1));
    when(valueOperations.get(deletedKey)).thenReturn(null);

    List<StoreItem> result = store.search(List.of("workspace"), 10, 0);

    assertThat(result).extracting(StoreItem::key).containsExactly("existing.txt");
  }

  @Test
  void putIfVersionUsesAtomicScriptAndAllowsVersionZeroCreate() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            eq("0"),
            any(String.class)))
        .thenReturn(1L);

    boolean updated = store.putIfVersion(List.of("workspace"), "new.txt", Map.of("body", "hello"), 0);

    assertThat(updated).isTrue();
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(redisTemplate)
        .execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            eq("0"),
            payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).contains("\"version\":1");
  }

  @Test
  void putIfVersionReturnsFalseWhenAtomicScriptRejectsUpdate() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            eq("7"),
            any(String.class)))
        .thenReturn(0L);

    boolean updated = store.putIfVersion(List.of("workspace"), "new.txt", Map.of("body", "hello"), 7);

    assertThat(updated).isFalse();
  }

  private static String itemJson(String body, long version) {
    return "{\"value\":{\"body\":\"" + body + "\"},\"version\":" + version + "}";
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
