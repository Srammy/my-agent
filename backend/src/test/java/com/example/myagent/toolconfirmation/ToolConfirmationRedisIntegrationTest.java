package com.example.myagent.toolconfirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ToolConfirmationRedisIntegrationTest {
  private static final long LARGE_USER_ID = 9_007_199_254_740_993L;
  private static final String PREFIX = "integration:";

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redisTemplate;
  private static ObjectMapper objectMapper;
  private ToolConfirmationService service;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
    objectMapper = Jackson2ObjectMapperBuilder.json().build();
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    redisTemplate.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();
    AgentProperties properties = new AgentProperties(null, null, null,
        new AgentProperties.StateStore("redis", new AgentProperties.StateStore.Redis("unused", PREFIX)),
        null, null, null);
    service = new ToolConfirmationService(redisTemplate, objectMapper, properties);
  }

  @Test
  void claimUsesLeaseAllowsExpiredReclaimAndPreservesTtl() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    Duration createdTtl = ttl(key);
    assertThat(createdTtl).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(30));
    JsonNode pending = json(key);
    assertThat(pending.get("userId").isTextual()).isTrue();
    assertThat(pending.get("userId").asText()).isEqualTo(Long.toString(LARGE_USER_ID));

    ToolConfirmationClaim first = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    JsonNode processing = json(key);
    assertThat(processing.get("status").asText()).isEqualTo("PROCESSING");
    assertThat(processing.get("processingToken").asText()).isEqualTo(first.processingToken());
    assertThat(processing.get("leaseExpiresAtEpochMs").asLong() - System.currentTimeMillis())
        .isBetween(28_000L, 30_000L);
    assertTtlNotReset(createdTtl, ttl(key));
    assertStatus(() -> service.claim(LARGE_USER_ID, "session", created.confirmationId()).block(), HttpStatus.CONFLICT);

    Duration remaining = ttl(key);
    ObjectNode expired = (ObjectNode) processing;
    expired.put("leaseExpiresAtEpochMs", System.currentTimeMillis() - 1);
    redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(expired), remaining).block();
    ToolConfirmationClaim reclaimed = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    assertThat(reclaimed.processingToken()).isNotEqualTo(first.processingToken());
    assertTtlNotReset(remaining, ttl(key));
  }

  @Test
  void completeConsumesRecordClearsLeaseAndPreservesTtl() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    Duration before = ttl(key);

    service.complete(created.confirmationId(), claim.processingToken(), true).block();

    JsonNode completed = json(key);
    assertThat(completed.get("status").asText()).isEqualTo("CONSUMED");
    assertThat(completed.get("confirmed").asBoolean()).isTrue();
    assertThat(completed.has("processingToken")).isFalse();
    assertThat(completed.has("leaseExpiresAtEpochMs")).isFalse();
    assertTtlNotReset(before, ttl(key));
  }

  @Test
  void releaseRestoresPendingClearsLeaseAndPreservesTtl() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    Duration before = ttl(key);

    service.release(created.confirmationId(), claim.processingToken()).block();

    JsonNode released = json(key);
    assertThat(released.get("status").asText()).isEqualTo("PENDING");
    assertThat(released.has("processingToken")).isFalse();
    assertThat(released.has("leaseExpiresAtEpochMs")).isFalse();
    assertTtlNotReset(before, ttl(key));
  }

  @Test
  void rejectsWrongOwnerSessionAndTokens() {
    ToolConfirmationRecord created = create();
    assertStatus(() -> service.claim(LARGE_USER_ID - 1, "session", created.confirmationId()).block(), HttpStatus.NOT_FOUND);
    assertStatus(() -> service.claim(LARGE_USER_ID, "other", created.confirmationId()).block(), HttpStatus.NOT_FOUND);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    assertStatus(() -> service.complete(created.confirmationId(), "wrong", true).block(), HttpStatus.CONFLICT);
    assertStatus(() -> service.release(created.confirmationId(), "wrong").block(), HttpStatus.CONFLICT);
    service.release(created.confirmationId(), claim.processingToken()).block();
  }

  private ToolConfirmationRecord create() {
    return service.create(LARGE_USER_ID, "session", "reply",
        new ToolUseBlock("call", "shell", Map.of("command", "pwd")), ConfirmationKind.USER_CONFIRM).block();
  }

  private String key(ToolConfirmationRecord record) {
    return PREFIX + "tool-confirmations:" + record.confirmationId();
  }

  private JsonNode json(String key) throws Exception {
    return objectMapper.readTree(redisTemplate.opsForValue().get(key).block());
  }

  private Duration ttl(String key) {
    return redisTemplate.getExpire(key).block();
  }

  private void assertTtlNotReset(Duration before, Duration after) {
    assertThat(after).isPositive().isLessThanOrEqualTo(before);
  }

  private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus status) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode()).isEqualTo(status));
  }
}
