package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    AgentProperties properties = new AgentProperties(null, null, null,
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
    when(gateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(
        Flux.<StreamEventDto>never()
            .doOnSubscribe(ignored -> gatewaySubscribed.countDown())
            .doOnCancel(gatewayCancelled::countDown));

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
      assertThat(redisTemplate.keys(
          "myagent:session-execution:" + USER.id() + ":" + SESSION_ID + ":active:*")
          .collectList().block()).isEmpty();
    } finally {
      if (execution != null) {
        execution.dispose();
      }
      coordinatorB.destroy();
      coordinatorA.destroy();
    }
  }

  @Test
  void deleteCompletionWaitsForConfirmationTrackedByAnotherCoordinatorToStop() throws Exception {
    RedisSessionExecutionCoordinator deleteCoordinator = coordinator();
    RedisSessionExecutionCoordinator executionCoordinator = coordinator();
    Disposable execution = null;
    try {
      awaitCoordinatorSubscribers(2);
      ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
      ChatSessionEntity session = new ChatSessionEntity(
          SESSION_ID, USER.id(), "title", LocalDateTime.now(), LocalDateTime.now());
      when(sessionMapper.findOwnedById(USER.id(), SESSION_ID)).thenReturn(session);
      CountDownLatch gatewaySubscribed = new CountDownLatch(1);
      CountDownLatch gatewayCancelled = new CountDownLatch(1);
      when(sessionMapper.deleteOwnedById(USER.id(), SESSION_ID)).thenAnswer(ignored -> {
        assertThat(gatewayCancelled.getCount()).isZero();
        return 1;
      });
      ChatAgentGateway gateway = mock(ChatAgentGateway.class);
      when(gateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(
          Flux.<StreamEventDto>never()
              .doOnSubscribe(ignored -> gatewaySubscribed.countDown())
              .doOnCancel(gatewayCancelled::countDown));
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

      StepVerifier.create(new SessionController(sessionService).deleteSession(USER, SESSION_ID))
          .verifyComplete();

      assertThat(gatewayCancelled.await(5, TimeUnit.SECONDS)).isTrue();
      verify(sessionMapper).deleteOwnedById(eq(USER.id()), eq(SESSION_ID));
    } finally {
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
            "myagent:session-execution:cancel",
            "{\"userId\":-1,\"sessionId\":\"readiness-probe\"}"))
        .filter(count -> count >= expected)
        .next()
        .block(Duration.ofSeconds(5));
    assertThat(subscribers).isNotNull().isGreaterThanOrEqualTo(expected);
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
    return coordinator;
  }
}
