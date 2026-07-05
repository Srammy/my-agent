package com.example.myagent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

class RedisBaseStore implements BaseStore {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final ReactiveStringRedisTemplate redisTemplate;
  private final String keyPrefix;

  RedisBaseStore(ReactiveStringRedisTemplate redisTemplate, String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.keyPrefix = keyPrefix;
  }

  @Override
  public StoreItem get(List<String> namespace, String key) {
    String value = redisTemplate.opsForValue().get(redisKey(namespace, key)).block();
    if (value == null) {
      return null;
    }
    StoredItem storedItem = readJson(value, StoredItem.class);
    return new StoreItem(key, storedItem.value(), storedItem.version());
  }

  @Override
  public void put(List<String> namespace, String key, Map<String, Object> value) {
    StoreItem current = get(namespace, key);
    long nextVersion = current == null ? 1 : current.version() + 1;
    redisTemplate.opsForValue().set(redisKey(namespace, key), writeJson(new StoredItem(value, nextVersion))).block();
  }

  @Override
  public boolean putIfVersion(
      List<String> namespace, String key, Map<String, Object> value, long version) {
    StoreItem current = get(namespace, key);
    if (current == null || current.version() != version) {
      return false;
    }
    redisTemplate
        .opsForValue()
        .set(redisKey(namespace, key), writeJson(new StoredItem(value, version + 1)))
        .block();
    return true;
  }

  @Override
  public List<StoreItem> search(List<String> namespace, int offset, int limit) {
    List<String> keys =
        redisTemplate.keys(namespacePrefix(namespace) + "*").collectList().block();
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    return keys.stream()
        .sorted()
        .skip(offset)
        .limit(limit)
        .map(this::toStoreItem)
        .sorted(Comparator.comparing(StoreItem::key))
        .toList();
  }

  @Override
  public void delete(List<String> namespace, String key) {
    redisTemplate.delete(redisKey(namespace, key)).block();
  }

  private StoreItem toStoreItem(String redisKey) {
    String value = redisTemplate.opsForValue().get(redisKey).block();
    StoredItem storedItem = readJson(value, StoredItem.class);
    String itemKey = decode(redisKey.substring(redisKey.lastIndexOf(':') + 1));
    return new StoreItem(itemKey, storedItem.value(), storedItem.version());
  }

  private String redisKey(List<String> namespace, String key) {
    return namespacePrefix(namespace) + encode(key);
  }

  private String namespacePrefix(List<String> namespace) {
    String encodedNamespace =
        namespace.stream().map(RedisBaseStore::encode).reduce("", (left, right) -> left + right + ":");
    return keyPrefix + encodedNamespace;
  }

  private static String encode(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static String writeJson(Object value) {
    try {
      return OBJECT_MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize AgentScope distributed store item", exception);
    }
  }

  private static <T> T readJson(String value, Class<T> valueType) {
    try {
      return OBJECT_MAPPER.readValue(value, valueType);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException(
          "Failed to deserialize AgentScope distributed store item", exception);
    }
  }

  private record StoredItem(Map<String, Object> value, long version) {}
}
