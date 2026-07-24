package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentExecution;
import com.example.myagent.auth.CurrentUser;
import com.example.myagent.config.AgentProperties;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.ChatSessionMapper;
import com.example.myagent.session.RedisSessionExecutionCoordinator;
import com.example.myagent.session.SessionController;
import com.example.myagent.session.SessionExecutionCoordinator;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationDecision;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@Testcontainers
class ChatServiceConfirmationIntegrationTest {
  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final String SESSION_ID = "session";
  private static final String PREFIX = "chat-confirmation-integration:";

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redisTemplate;
  private AgentProperties properties;
  private ToolConfirmationService toolConfirmationService;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    redisTemplate.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();
    properties = new AgentProperties(null, null, null,
        new AgentProperties.StateStore("redis", new AgentProperties.StateStore.Redis("unused", PREFIX)),
        null, null, null);
    toolConfirmationService = new ToolConfirmationService(
        redisTemplate, Jackson2ObjectMapperBuilder.json().build(), properties);
  }

  @Test
  void cancellingGatewayConfirmationKeepsTheRedisRecordConsumed() throws Exception {
    String confirmationId = toolConfirmationService.create(
        USER.id(), SESSION_ID, "reply", List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
        ConfirmationKind.USER_CONFIRM).block().confirmationId();
    CountDownLatch gatewaySubscribed = new CountDownLatch(1);
    CountDownLatch gatewayCancelled = new CountDownLatch(1);
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any())).thenReturn(
        new AgentExecution<>(
            Flux.<StreamEventDto>never()
                .doOnSubscribe(ignored -> gatewaySubscribed.countDown())
                .doOnCancel(gatewayCancelled::countDown),
            Mono.empty()));

    ChatService chatService = new ChatService(
        sessionService(), gateway, permissionService(), toolConfirmationService,
        executionCoordinator());
    Disposable subscription = chatService.confirm(USER, SESSION_ID, confirmationId,
        List.of(new ToolConfirmationDecisionRequest("call", true))).subscribe();

    assertThat(gatewaySubscribed.await(5, TimeUnit.SECONDS)).isTrue();
    subscription.dispose();
    assertThat(gatewayCancelled.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> toolConfirmationService.claim(USER.id(), SESSION_ID, confirmationId).block())
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void registrationFailureMakesConfirmationRetryable() {
    String confirmationId = toolConfirmationService.create(
        USER.id(), SESSION_ID, "reply",
        List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
        ConfirmationKind.USER_CONFIRM).block().confirmationId();
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new AgentExecution<>(Flux.never(), Mono.empty()));
    SessionExecutionCoordinator rejectingCoordinator = mock(SessionExecutionCoordinator.class);
    when(rejectingCoordinator.track(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.error(new IllegalStateException("registration failed")));

    StepVerifier.create(new ChatService(
            sessionService(),
            gateway,
            permissionService(),
            toolConfirmationService,
            rejectingCoordinator).confirm(
            USER,
            SESSION_ID,
            confirmationId,
            List.of(new ToolConfirmationDecisionRequest("call", true))))
        .expectErrorSatisfies(error -> assertThat(
            ((ResponseStatusException) error).getHeaders().getFirst("X-Error-Code"))
            .isEqualTo("TOOL_CONFIRMATION_RETRYABLE"))
        .verify();

    ToolConfirmationClaim retry =
        toolConfirmationService.claim(USER.id(), SESSION_ID, confirmationId).block();
    assertThat(retry).isNotNull();
    toolConfirmationService.release(confirmationId, retry.processingToken()).block();
  }

  @Test
  void consumePreflightFailureWithRealCoordinatorDoesNotPoisonTheSession()
      throws Exception {
    ToolConfirmationService failingConsumeService = new ToolConfirmationService(
        redisTemplate, Jackson2ObjectMapperBuilder.json().build(), properties) {
      @Override
      public Mono<Void> consume(
          String confirmationId,
          String processingToken,
          List<ToolConfirmationDecision> decisions) {
        return Mono.error(new IllegalStateException("consume failed"));
      }
    };
    String confirmationId = failingConsumeService.create(
        USER.id(), SESSION_ID, "reply",
        List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
        ConfirmationKind.USER_CONFIRM).block().confirmationId();
    AtomicBoolean sourceSubscribed = new AtomicBoolean();
    CountDownLatch completionDisposed = new CountDownLatch(1);
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new AgentExecution<>(
            Flux.defer(() -> {
              sourceSubscribed.set(true);
              return Flux.just(StreamEventDto.done());
            }),
            Mono.<Void>never().doOnCancel(completionDisposed::countDown)));
    RedisSessionExecutionCoordinator realCoordinator = coordinator();
    try {
      ChatService chatService = new ChatService(
          sessionService(),
          gateway,
          permissionService(),
          failingConsumeService,
          realCoordinator);

      StepVerifier.create(chatService.confirm(
              USER,
              SESSION_ID,
              confirmationId,
              List.of(new ToolConfirmationDecisionRequest("call", true))))
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getHeaders()
                .getFirst("X-Error-Code")).isEqualTo("TOOL_CONFIRMATION_RETRYABLE");
          })
          .verify();

      assertThat(sourceSubscribed).isFalse();
      assertThat(completionDisposed.await(5, TimeUnit.SECONDS)).isTrue();
      awaitNoExecutionRegistration(SESSION_ID);

      ToolConfirmationClaim retry =
          failingConsumeService.claim(USER.id(), SESSION_ID, confirmationId).block();
      assertThat(retry).isNotNull();
      failingConsumeService.release(confirmationId, retry.processingToken()).block();

      StepVerifier.create(realCoordinator.track(
              USER.id(), SESSION_ID, () -> Flux.just("started")))
          .expectNext("started")
          .verifyComplete();
      awaitNoExecutionRegistration(SESSION_ID);
    } finally {
      realCoordinator.destroy();
    }
  }

  @Test
  void remoteCancellationDuringConfirmationPreflightPreservesCancellationAndRollsBack()
      throws Exception {
    CountDownLatch consumeSubscribed = new CountDownLatch(1);
    CountDownLatch consumeCancelled = new CountDownLatch(1);
    ToolConfirmationService pausedConsumeService = new ToolConfirmationService(
        redisTemplate, Jackson2ObjectMapperBuilder.json().build(), properties) {
      @Override
      public Mono<Void> consume(
          String confirmationId,
          String processingToken,
          List<ToolConfirmationDecision> decisions) {
        return Mono.<Void>never()
            .doOnSubscribe(ignored -> consumeSubscribed.countDown())
            .doOnCancel(consumeCancelled::countDown);
      }
    };
    String confirmationId = pausedConsumeService.create(
        USER.id(), SESSION_ID, "reply",
        List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
        ConfirmationKind.USER_CONFIRM).block().confirmationId();
    AtomicBoolean sourceSubscribed = new AtomicBoolean();
    CountDownLatch completionDisposed = new CountDownLatch(1);
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new AgentExecution<>(
            Flux.defer(() -> {
              sourceSubscribed.set(true);
              return Flux.never();
            }),
            Mono.<Void>never().doOnCancel(completionDisposed::countDown)));
    RedisSessionExecutionCoordinator cancellingCoordinator = coordinator();
    RedisSessionExecutionCoordinator executionCoordinator = coordinator();
    Disposable execution = null;
    try {
      awaitCoordinatorSubscribers(2);
      AtomicReference<Throwable> failure = new AtomicReference<>();
      CountDownLatch terminated = new CountDownLatch(1);
      ChatService chatService = new ChatService(
          sessionService(),
          gateway,
          permissionService(),
          pausedConsumeService,
          executionCoordinator);

      execution = chatService.confirm(
              USER,
              SESSION_ID,
              confirmationId,
              List.of(new ToolConfirmationDecisionRequest("call", true)))
          .doOnError(failure::set)
          .doFinally(ignored -> terminated.countDown())
          .subscribe(ignored -> {}, ignored -> {});
      assertThat(consumeSubscribed.await(5, TimeUnit.SECONDS)).isTrue();

      cancellingCoordinator.cancelAndAwait(USER.id(), SESSION_ID)
          .block(Duration.ofSeconds(10));

      assertThat(terminated.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(consumeCancelled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(completionDisposed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(sourceSubscribed).isFalse();
      assertThat(failure.get()).isInstanceOfSatisfying(
          ResponseStatusException.class,
          error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getHeaders().getFirst("X-Error-Code"))
                .isEqualTo("SESSION_CANCELLING");
          });
      awaitNoExecutionRegistration(SESSION_ID);

      ToolConfirmationClaim retry =
          pausedConsumeService.claim(USER.id(), SESSION_ID, confirmationId).block();
      assertThat(retry).isNotNull();
      pausedConsumeService.release(confirmationId, retry.processingToken()).block();
    } finally {
      if (execution != null) {
        execution.dispose();
      }
      executionCoordinator.destroy();
      cancellingCoordinator.destroy();
    }
  }

  @Test
  void eventSourceFailureKeepsConfirmationConsumed() {
    String confirmationId = toolConfirmationService.create(
        USER.id(), SESSION_ID, "reply",
        List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
        ConfirmationKind.USER_CONFIRM).block().confirmationId();
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    Sinks.Empty<Void> completion = Sinks.empty();
    when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new AgentExecution<>(
            Flux.<StreamEventDto>error(new IllegalStateException("started failure"))
                .doFinally(ignored -> completion.tryEmitEmpty()),
            completion.asMono()));
    RedisSessionExecutionCoordinator realCoordinator = coordinator();
    try {
      ChatService chatService = new ChatService(
          sessionService(),
          gateway,
          permissionService(),
          toolConfirmationService,
          realCoordinator);

      StepVerifier.create(chatService.confirm(
              USER,
              SESSION_ID,
              confirmationId,
              List.of(new ToolConfirmationDecisionRequest("call", true))))
          .expectNext(StreamEventDto.error("started failure"))
          .verifyComplete();

      assertThatThrownBy(() -> toolConfirmationService.claim(
          USER.id(), SESSION_ID, confirmationId).block())
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    } finally {
      realCoordinator.destroy();
    }
  }

  @Test
  void cancellationPublishedByOneCoordinatorStopsExecutionTrackedByAnother() throws Exception {
    RedisSessionExecutionCoordinator coordinatorA = coordinator();
    RedisSessionExecutionCoordinator coordinatorB = coordinator();
    Disposable execution = null;
    try {
      awaitCoordinatorSubscribers(2);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch stopped = new CountDownLatch(1);
      execution = coordinatorB.track(
              USER.id(), SESSION_ID,
              () -> Flux.<Integer>never()
                  .doOnSubscribe(ignored -> started.countDown())
                  .doOnCancel(stopped::countDown))
          .subscribe();
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

      coordinatorA.cancelAndAwait(USER.id(), SESSION_ID).block();

      assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(execution.isDisposed()).isTrue();
      String sessionPrefix =
          "myagent:agent-state:session-execution:" + USER.id() + ":" + SESSION_ID;
      assertThat(redisTemplate.opsForValue().get(sessionPrefix + ":active-count").block()).isNull();
      assertThat(redisTemplate.opsForSet()
          .size(sessionPrefix + ":pending-completion").block()).isZero();
    } finally {
      if (execution != null) {
        execution.dispose();
      }
      coordinatorB.destroy();
      coordinatorA.destroy();
    }
  }

  @Test
  void deleteReturns204OnlyAfterRemoteConfirmationExecutionCompletes() throws Exception {
    RedisSessionExecutionCoordinator deleteCoordinator = coordinator();
    RedisSessionExecutionCoordinator executionCoordinator = coordinator();
    Sinks.Empty<Void> toolCompletion = Sinks.empty();
    Disposable execution = null;
    CompletableFuture<Void> deletion = null;
    try {
      awaitCoordinatorSubscribers(2);
      ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
      ChatSessionEntity session = new ChatSessionEntity(
          SESSION_ID, USER.id(), "title", LocalDateTime.now(), LocalDateTime.now());
      when(sessionMapper.findOwnedById(USER.id(), SESSION_ID)).thenReturn(session);
      CountDownLatch gatewaySubscribed = new CountDownLatch(1);
      CountDownLatch gatewayCancelled = new CountDownLatch(1);
      AtomicBoolean rejectedBatchSubscribed = new AtomicBoolean();
      when(sessionMapper.deleteOwnedById(USER.id(), SESSION_ID)).thenReturn(1);
      ChatAgentGateway gateway = mock(ChatAgentGateway.class);
      when(gateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
          .thenReturn(new AgentExecution<>(
              Flux.<StreamEventDto>never()
                  .doOnSubscribe(ignored -> gatewaySubscribed.countDown())
                  .doOnCancel(gatewayCancelled::countDown),
              toolCompletion.asMono()))
          .thenReturn(new AgentExecution<>(
              Flux.<StreamEventDto>never()
                  .doOnSubscribe(ignored -> rejectedBatchSubscribed.set(true)),
              Mono.empty()));
      SessionService sessionService = new SessionService(sessionMapper, deleteCoordinator);
      ChatService chatService = new ChatService(
          sessionService, gateway, permissionService(), toolConfirmationService,
          executionCoordinator);
      String confirmationId = toolConfirmationService.create(
          USER.id(), SESSION_ID, "reply",
          List.of(new ToolUseBlock("call", "shell", Map.of("command", "pwd"))),
          ConfirmationKind.USER_CONFIRM).block().confirmationId();
      execution = chatService.confirm(
              USER, SESSION_ID, confirmationId,
              List.of(new ToolConfirmationDecisionRequest("call", true)))
          .subscribe();
      assertThat(gatewaySubscribed.await(5, TimeUnit.SECONDS)).isTrue();

      deletion = new SessionController(sessionService)
          .deleteSession(USER, SESSION_ID)
          .toFuture();

      assertThat(gatewayCancelled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(deletion).isNotDone();
      verify(sessionMapper, never()).deleteOwnedById(USER.id(), SESSION_ID);

      String rejectedConfirmationId = toolConfirmationService.create(
          USER.id(), SESSION_ID, "reply-2",
          List.of(new ToolUseBlock("call-2", "shell", Map.of("command", "pwd"))),
          ConfirmationKind.USER_CONFIRM).block().confirmationId();
      StepVerifier.create(chatService.confirm(
              USER,
              SESSION_ID,
              rejectedConfirmationId,
              List.of(new ToolConfirmationDecisionRequest("call-2", true))))
          .expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(ResponseStatusException.class);
            assertThat(((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
          })
          .verify();
      assertThat(rejectedBatchSubscribed).isFalse();

      toolCompletion.tryEmitEmpty();

      deletion.get(5, TimeUnit.SECONDS);
      verify(sessionMapper).deleteOwnedById(eq(USER.id()), eq(SESSION_ID));
    } finally {
      toolCompletion.tryEmitEmpty();
      if (deletion != null) {
        deletion.cancel(true);
      }
      if (execution != null) {
        execution.dispose();
      }
      executionCoordinator.destroy();
      deleteCoordinator.destroy();
    }
  }

  private SessionService sessionService() {
    SessionService sessionService = mock(SessionService.class);
    when(sessionService.requireOwnedSession(USER, SESSION_ID)).thenReturn(
        new ChatSessionEntity(SESSION_ID, USER.id(), "title", LocalDateTime.now(), LocalDateTime.now()));
    return sessionService;
  }

  private PermissionService permissionService() {
    PermissionService permissionService = mock(PermissionService.class);
    when(permissionService.getModeForOwnedSession(SESSION_ID)).thenReturn(PermissionMode.DEFAULT);
    return permissionService;
  }

  private RedisSessionExecutionCoordinator coordinator() {
    return new RedisSessionExecutionCoordinator(
        redisTemplate, Jackson2ObjectMapperBuilder.json().build());
  }

  private void awaitCoordinatorSubscribers(long expected) {
    Long subscribers = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
        .concatMap(ignored -> redisTemplate.convertAndSend(
            "myagent:agent-state:session-execution:cancel",
            "{\"userId\":-1,\"sessionId\":\"readiness-probe\"}"))
        .filter(count -> count >= expected)
        .next()
        .block(Duration.ofSeconds(5));
    assertThat(subscribers).isNotNull().isGreaterThanOrEqualTo(expected);
  }

  private void awaitNoExecutionRegistration(String sessionId) {
    String sessionPrefix =
        "myagent:agent-state:session-execution:" + USER.id() + ":" + sessionId;
    Boolean cleared = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
        .concatMap(ignored -> Mono.zip(
            redisTemplate.opsForValue().get(sessionPrefix + ":active-count")
                .defaultIfEmpty(""),
            redisTemplate.opsForSet().size(sessionPrefix + ":pending-completion")))
        .filter(state -> state.getT1().isEmpty() && state.getT2() == 0L)
        .next()
        .thenReturn(true)
        .block(Duration.ofSeconds(5));
    assertThat(cleared).isTrue();
  }

  @SuppressWarnings("unchecked")
  private SessionExecutionCoordinator executionCoordinator() {
    SessionExecutionCoordinator coordinator = mock(SessionExecutionCoordinator.class);
    when(coordinator.track(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation ->
            ((java.util.function.Supplier<Flux<StreamEventDto>>) invocation.getArgument(2)).get());
    when(coordinator.track(
        org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> Mono.defer(
                (java.util.function.Supplier<Mono<Void>>) invocation.getArgument(2))
            .thenMany(Flux.defer(
                (java.util.function.Supplier<Flux<StreamEventDto>>) invocation.getArgument(3))));
    return coordinator;
  }
}
