package com.example.myagent.toolconfirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    long beforeClaim = redisEpochMillis();
    ToolConfirmationClaim first = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    long afterClaim = redisEpochMillis();
    JsonNode processing = json(key);
    assertThat(processing.get("status").asText()).isEqualTo("PROCESSING");
    assertThat(processing.get("processingToken").asText()).isEqualTo(first.processingToken());
    assertThat(processing.get("leaseExpiresAtEpochMs").asLong())
        .isBetween(beforeClaim + 30_000L, afterClaim + 30_000L);
    assertTtlNotReset(createdTtl, ttl(key));
    assertStatus(() -> service.claim(LARGE_USER_ID, "session", created.confirmationId()).block(), HttpStatus.CONFLICT);

    Duration remaining = ttl(key);
    ObjectNode expired = (ObjectNode) processing;
    expired.put("leaseExpiresAtEpochMs", redisEpochMillis() - 1);
    redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(expired), remaining).block();
    ToolConfirmationClaim reclaimed = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    assertThat(reclaimed.processingToken()).isNotEqualTo(first.processingToken());
    assertTtlNotReset(remaining, ttl(key));
  }

  @Test
  void consumeConsumesRecordClearsLeaseAndPreservesTtl() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    Duration before = ttl(key);

    List<ToolConfirmationDecision> decisions = List.of(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false));
    service.consume(created.confirmationId(), claim.processingToken(), decisions).block();

    JsonNode completed = json(key);
    assertThat(completed.get("status").asText()).isEqualTo("CONSUMED");
    assertThat(objectMapper.treeToValue(completed.get("decisions"), ToolConfirmationDecision[].class))
        .containsExactlyElementsOf(decisions);
    assertThat(completed.has("processingToken")).isFalse();
    assertThat(completed.has("leaseExpiresAtEpochMs")).isFalse();
    assertTtlNotReset(before, ttl(key));
  }

  @Test
  void consumedRecordCannotBeClaimedAgainAfterTheFormerLeaseWindow() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();

    service.consume(created.confirmationId(), claim.processingToken(), List.of()).block();

    ObjectNode consumed = (ObjectNode) json(key);
    // This represents a recovery flow outliving the former 30-second PROCESSING lease.
    consumed.put("leaseExpiresAtEpochMs", redisEpochMillis() - Duration.ofSeconds(31).toMillis());
    redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(consumed), ttl(key)).block();

    assertStatus(
        () -> service.claim(LARGE_USER_ID, "session", created.confirmationId()).block(),
        HttpStatus.CONFLICT);
    assertThat(json(key).get("status").asText()).isEqualTo("CONSUMED");
  }

  @Test
  void rejectsWrongOwnerSessionAndTokens() {
    ToolConfirmationRecord created = create();
    assertStatus(() -> service.claim(LARGE_USER_ID - 1, "session", created.confirmationId()).block(), HttpStatus.NOT_FOUND);
    assertStatus(() -> service.claim(LARGE_USER_ID, "other", created.confirmationId()).block(), HttpStatus.NOT_FOUND);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    assertStatus(() -> service.consume(created.confirmationId(), "wrong", List.of()).block(), HttpStatus.CONFLICT);
    service.consume(created.confirmationId(), claim.processingToken(), List.of()).block();
  }

  @Test
  void releaseReturnsRecordToPendingClearsLeaseAndPreservesTtl() throws Exception {
    ToolConfirmationRecord created = create();
    String key = key(created);
    ToolConfirmationClaim claim = service.claim(LARGE_USER_ID, "session", created.confirmationId()).block();
    Duration before = ttl(key);
    service.release(created.confirmationId(), claim.processingToken()).block();
    JsonNode pending = json(key);
    assertThat(pending.get("status").asText()).isEqualTo("PENDING");
    assertThat(pending.has("processingToken")).isFalse();
    assertThat(pending.has("leaseExpiresAtEpochMs")).isFalse();
    assertTtlNotReset(before, ttl(key));
  }

  private ToolConfirmationRecord create() {
    return service.create(LARGE_USER_ID, "session", "reply",
        List.of(
            new ToolUseBlock("call-1", "read_file", Map.of("path", "a.md")),
            new ToolUseBlock("call-2", "shell_command", Map.of("command", "npm test"))),
        ConfirmationKind.USER_CONFIRM).block();
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

  private long redisEpochMillis() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>(
        "local t = redis.call('TIME'); return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
        Long.class);
    return redisTemplate.execute(script, List.of()).next().block();
  }

  private void assertTtlNotReset(Duration before, Duration after) {
    assertThat(after).isPositive().isLessThanOrEqualTo(before);
  }

  private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, HttpStatus status) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode()).isEqualTo(status));
  }
}
