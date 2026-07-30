package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class RedisBaseStoreTest {

  @Mock private ReactiveStringRedisTemplate redisTemplate;
  @Mock private ReactiveValueOperations<String, String> valueOperations;

  @Test
  void searchTreatsSecondArgumentAsLimitAndThirdArgumentAsOffset() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    String namespacePrefix = "prefix:" + encode("workspace") + ":";
    String keyA = namespacePrefix + encode("a.txt");
    String keyB = namespacePrefix + encode("b.txt");
    String keyC = namespacePrefix + encode("c.txt");

    when(redisTemplate.keys(namespacePrefix + "*"))
        .thenReturn(Flux.just(keyC, keyA, keyB));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(keyA)).thenReturn(Mono.just(itemJson("A", 1)));
    when(valueOperations.get(keyB)).thenReturn(Mono.just(itemJson("B", 2)));
    when(valueOperations.get(keyC)).thenReturn(Mono.just(itemJson("C", 3)));

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
        .thenReturn(Flux.just(existingKey, deletedKey));
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(existingKey)).thenReturn(Mono.just(itemJson("existing", 1)));
    when(valueOperations.get(deletedKey)).thenReturn(Mono.empty());

    List<StoreItem> result = store.search(List.of("workspace"), 10, 0);

    assertThat(result).extracting(StoreItem::key).containsExactly("existing.txt");
  }

  @Test
  void putIfVersionUsesAtomicScriptAndAllowsVersionZeroCreate() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            anyList()))
        .thenReturn(Flux.just(1L));

    boolean updated = store.putIfVersion(List.of("workspace"), "new.txt", Map.of("body", "hello"), 0);

    assertThat(updated).isTrue();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Object>> argsCaptor = ArgumentCaptor.forClass(List.class);
    verify(redisTemplate)
        .execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            argsCaptor.capture());
    assertThat(argsCaptor.getValue().get(0)).isEqualTo("0");
    assertThat((String) argsCaptor.getValue().get(1)).contains("\"version\":1");
  }

  @Test
  void putIfVersionReturnsFalseWhenAtomicScriptRejectsUpdate() {
    RedisBaseStore store = new RedisBaseStore(redisTemplate, "prefix:");
    when(redisTemplate.execute(
            org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of("prefix:" + encode("workspace") + ":" + encode("new.txt"))),
            anyList()))
        .thenReturn(Flux.just(0L));

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
