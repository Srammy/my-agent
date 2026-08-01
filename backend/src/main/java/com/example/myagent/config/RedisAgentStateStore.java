package com.example.myagent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAgentStateStore implements AgentStateStore {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final StringRedisTemplate redisTemplate;
  private final String keyPrefix;

  RedisAgentStateStore(StringRedisTemplate redisTemplate, String keyPrefix) {
    this.redisTemplate = redisTemplate;
    this.keyPrefix = keyPrefix;
  }

  @Override
  public void save(String agentId, String sessionId, String stateKey, State state) {
    redisTemplate.opsForValue().set(key(agentId, sessionId, stateKey), serializeState(state));
  }

  @Override
  public void save(String agentId, String sessionId, String stateKey, List<? extends State> states) {
    List<String> serializedStates = states.stream().map(this::serializeState).toList();
    redisTemplate.opsForValue().set(key(agentId, sessionId, stateKey), writeJson(serializedStates));
  }

  @Override
  public <T extends State> Optional<T> get(
      String agentId, String sessionId, String stateKey, Class<T> stateType) {
    String value = redisTemplate.opsForValue().get(key(agentId, sessionId, stateKey));
    if (value == null) {
      return Optional.empty();
    }
    return Optional.of(deserializeState(value, stateType));
  }

  @Override
  public <T extends State> List<T> getList(
      String agentId, String sessionId, String stateKey, Class<T> stateType) {
    String value = redisTemplate.opsForValue().get(key(agentId, sessionId, stateKey));
    if (value == null) {
      return List.of();
    }
    List<String> serializedStates = readJson(value, new TypeReference<>() {});
    return serializedStates.stream().map(serialized -> deserializeState(serialized, stateType)).toList();
  }

  @Override
  public boolean exists(String agentId, String sessionId) {
    List<String> keys = keysByPrefix(sessionPrefix(agentId, sessionId));
    return keys != null && !keys.isEmpty();
  }

  @Override
  public void delete(String agentId, String sessionId) {
    redisTemplate
        .delete(keysByPrefix(sessionPrefix(agentId, sessionId)));
  }

  @Override
  public void delete(String agentId, String sessionId, String stateKey) {
    redisTemplate.delete(key(agentId, sessionId, stateKey));
  }

  @Override
  public Set<String> listSessionIds(String agentId) {
    List<String> keys = keysByPrefix(agentPrefix(agentId));
    Set<String> sessionIds = new LinkedHashSet<>();
    if (keys == null) {
      return sessionIds;
    }
    for (String key : keys) {
      String tail = key.substring(agentPrefix(agentId).length());
      int delimiter = tail.indexOf(':');
      if (delimiter > 0) {
        sessionIds.add(decode(tail.substring(0, delimiter)));
      }
    }
    return sessionIds;
  }

  private List<String> keysByPrefix(String prefix) {
    Set<String> keys = redisTemplate.keys(prefix + "*");
    return keys == null ? List.of() : List.copyOf(keys);
  }

  private String serializeState(State state) {
    if (state instanceof AgentState agentState) {
      return agentState.toJson();
    }
    return writeJson(state);
  }

  private <T extends State> T deserializeState(String value, Class<T> stateType) {
    if (AgentState.class.equals(stateType)) {
      return stateType.cast(AgentState.fromJsonString(value));
    }
    return readJson(value, stateType);
  }

  private String key(String agentId, String sessionId, String stateKey) {
    return sessionPrefix(agentId, sessionId) + encode(stateKey);
  }

  private String sessionPrefix(String agentId, String sessionId) {
    return agentPrefix(agentId) + encode(sessionId) + ":";
  }

  private String agentPrefix(String agentId) {
    return keyPrefix + encode(agentId) + ":";
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
      throw new IllegalStateException("Failed to serialize AgentScope state", exception);
    }
  }

  private static <T> T readJson(String value, Class<T> valueType) {
    try {
      return OBJECT_MAPPER.readValue(value, valueType);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to deserialize AgentScope state", exception);
    }
  }

  private static <T> T readJson(String value, TypeReference<T> valueType) {
    try {
      return OBJECT_MAPPER.readValue(value, valueType);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to deserialize AgentScope state", exception);
    }
  }
}
