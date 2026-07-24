package com.example.myagent.toolconfirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ToolConfirmationServiceTest {
  @Mock private ReactiveStringRedisTemplate redisTemplate;
  @Mock private ReactiveValueOperations<String, String> valueOperations;
  @Mock private AgentProperties properties;
  @Mock private AgentProperties.StateStore stateStore;
  @Mock private AgentProperties.StateStore.Redis redis;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private ToolConfirmationService service;

  @BeforeEach
  void setUp() {
    when(properties.stateStore()).thenReturn(stateStore);
    when(stateStore.redis()).thenReturn(redis);
    when(redis.keyPrefix()).thenReturn("prefix:");
    service = new ToolConfirmationService(redisTemplate, objectMapper, properties);
  }

  @Test
  void snapshotRoundTripPreservesNullInputAndIsDefensive() {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("optional", null);
    input.put("command", "pwd");
    ToolUseBlock original = new ToolUseBlock("call-1", "shell", input);

    ToolCallSnapshot snapshot = ToolCallSnapshot.from(original);
    input.put("command", "changed");
    ToolUseBlock restored = snapshot.toToolUseBlock();
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("optional", null);
    expected.put("command", "pwd");

    assertThat(restored.getId()).isEqualTo(original.getId());
    assertThat(restored.getName()).isEqualTo(original.getName());
    assertThat(restored.getInput()).containsExactlyEntriesOf(expected);
    assertThatThrownBy(() -> snapshot.input().put("another", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void createStoresPendingJsonWithUuidAndThirtyMinuteTtl() throws Exception {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.set(any(), any(), eq(Duration.ofMinutes(30)))).thenReturn(Mono.just(true));

    ToolConfirmationRecord record = service.create(7L, "session", "reply", toolCalls(), ConfirmationKind.USER_CONFIRM).block();

    assertThat(UUID.fromString(record.confirmationId()).version()).isEqualTo(4);
    assertThat(record.status()).isEqualTo(ToolConfirmationStatus.PENDING);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(key.capture(), json.capture(), eq(Duration.ofMinutes(30)));
    assertThat(key.getValue()).isEqualTo("prefix:tool-confirmations:" + record.confirmationId());
    ToolConfirmationRecord stored = objectMapper.readValue(json.getValue(), ToolConfirmationRecord.class);
    assertThat(stored).usingRecursiveComparison().isEqualTo(record);
    assertThat(record.userId()).isEqualTo("7");
    assertThat(stored.userId()).isEqualTo("7");
    assertThat(stored.sessionId()).isEqualTo("session");
    assertThat(stored.replyId()).isEqualTo("reply");
    assertThat(stored.kind()).isEqualTo(ConfirmationKind.USER_CONFIRM);
    assertThat(stored.createdAt()).isNotNull();
  }

  @Test
  void createFailsWhenRedisRejectsSet() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.set(any(), any(), any(Duration.class))).thenReturn(Mono.just(false));
    StepVerifier.create(service.create(7L, "session", "reply", toolCalls(), ConfirmationKind.USER_CONFIRM))
        .expectError(IllegalStateException.class).verify();
  }

  @Test
  void claimUsesRedisTimeAndPassesOwnerTokenAndLease() throws Exception {
    when(redisTemplate.execute(any(RedisScript.class), eq(List.of("prefix:tool-confirmations:id")), anyList()))
        .thenAnswer(invocation -> {
          List<?> args = invocation.getArgument(2);
          String token = (String) args.get(args.size() - 2);
          return Flux.just(jsonRecord("id", ToolConfirmationStatus.PROCESSING, token, 30000L, null));
        });

    ToolConfirmationClaim claim = service.claim(7L, "session", "id").block();

    @SuppressWarnings("unchecked") ArgumentCaptor<RedisScript<String>> script = ArgumentCaptor.forClass(RedisScript.class);
    @SuppressWarnings("unchecked") ArgumentCaptor<List<Object>> args = ArgumentCaptor.forClass(List.class);
    verify(redisTemplate).execute(script.capture(), eq(List.of("prefix:tool-confirmations:id")), args.capture());
    assertThat(script.getValue().getScriptAsString()).contains("redis.call('TIME')");
    assertThat(args.getValue()).hasSize(4);
    assertThat(args.getValue().get(0)).isEqualTo("7");
    assertThat(args.getValue().get(1)).isEqualTo("session");
    assertThat(UUID.fromString((String) args.getValue().get(2)).version()).isEqualTo(4);
    assertThat(args.getValue().get(3)).isEqualTo("30000");
    assertThat(claim.processingToken()).isEqualTo(claim.record().processingToken());
  }

  @Test
  void claimMapsMissingAndUnownedToNotFoundAndConflictToConflict() {
    assertClaimStatus("__NOT_FOUND__", 404);
    assertClaimStatus("__NOT_OWNED__", 404);
    assertClaimStatus("__CONFLICT__", 409);
  }

  @Test
  void consumePassesTokenAndDecisionsAndMapsConflict() throws Exception {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just("__CONFLICT__"));
    List<ToolConfirmationDecision> decisions = List.of(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false));
    assertStatus(() -> service.consume("id", "token", decisions).block(), 409);
    verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("prefix:tool-confirmations:id")),
        eq(List.of("token", objectMapper.writeValueAsString(decisions))));
  }

  @Test
  void consumeAcceptsOkAndMapsMissingToNotFound() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
        .thenReturn(Flux.just("__OK__"), Flux.just("__NOT_FOUND__"));

    StepVerifier.create(service.consume("id", "token", List.of())).verifyComplete();
    assertStatus(() -> service.consume("id", "token", List.of()).block(), 404);
  }

  @Test
  void releaseAcceptsOkAndMapsConflict() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
        .thenReturn(Flux.just("__OK__"), Flux.just("__CONFLICT__"));
    StepVerifier.create(service.release("id", "token")).verifyComplete();
    assertStatus(() -> service.release("id", "token").block(), 409);
  }

  @Test
  void rollbackIfProcessingReturnsWhetherTheSameTokenWasReleased() {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
        .thenReturn(Flux.just(1L), Flux.just(0L), Flux.just(0L));

    StepVerifier.create(service.rollbackIfProcessing("id", "token"))
        .expectNext(true)
        .verifyComplete();
    StepVerifier.create(service.rollbackIfProcessing("id", "wrong-token"))
        .expectNext(false)
        .verifyComplete();
    StepVerifier.create(service.rollbackIfProcessing("missing", "token"))
        .expectNext(false)
        .verifyComplete();

    verify(redisTemplate).execute(
        any(RedisScript.class),
        eq(List.of("prefix:tool-confirmations:id")),
        eq(List.of("token")));
  }

  private void assertClaimStatus(String result, int status) {
    when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(result));
    assertStatus(() -> service.claim(7L, "session", "id").block(), status);
  }

  private void assertStatus(ThrowingCallable action, int status) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
  }

  private String jsonRecord(String id, ToolConfirmationStatus status, String token, Long lease, Boolean confirmed) throws Exception {
    return objectMapper.writeValueAsString(new ToolConfirmationRecord(id, "7", "session", "reply",
        List.of(ToolCallSnapshot.from(toolCall())), ConfirmationKind.USER_CONFIRM, Instant.now(), status, token, lease,
        confirmed == null ? null : List.of(new ToolConfirmationDecision("call-1", confirmed))));
  }

  private ToolUseBlock toolCall() {
    return new ToolUseBlock("call-1", "shell", Map.of("command", "pwd"));
  }

  private List<ToolUseBlock> toolCalls() {
    return List.of(toolCall(), new ToolUseBlock("call-2", "read_file", Map.of("path", "a.md")));
  }
}
