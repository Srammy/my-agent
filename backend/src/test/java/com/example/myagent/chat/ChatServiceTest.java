package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentExecution;
import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionExecutionCoordinator;
import com.example.myagent.session.SessionMode;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolCallSnapshot;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationDecision;
import com.example.myagent.toolconfirmation.ToolConfirmationRecord;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import com.example.myagent.toolconfirmation.ToolConfirmationStatus;
import com.example.myagent.knowledge.search.KnowledgeSearchHit;
import com.example.myagent.knowledge.search.KnowledgeSearchService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-04T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-04T09:45:00");

  @Mock private SessionService sessionService;
  @Mock private ChatAgentGateway chatAgentGateway;
  @Mock private PermissionService permissionService;
  @Mock private ToolConfirmationService toolConfirmationService;
  @Mock private SessionExecutionCoordinator sessionExecutionCoordinator;
  @Mock private ChatMessageService chatMessageService;
  @Mock private KnowledgeSearchService knowledgeSearchService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUpExecutionAdapters() {
    org.mockito.Mockito.lenient()
        .when(chatAgentGateway.streamExecution(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> new AgentExecution<>(
            chatAgentGateway.stream(invocation.getArgument(0)), Mono.empty()));
    org.mockito.Mockito.lenient()
        .when(chatAgentGateway.confirmExecution(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> new AgentExecution<>(
            Flux.defer(() -> chatAgentGateway.confirm(invocation.getArgument(0))), Mono.empty()));
    org.mockito.Mockito.lenient()
        .when(sessionExecutionCoordinator.track(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation ->
            ((Supplier<Flux<StreamEventDto>>) invocation.getArgument(2)).get());
    org.mockito.Mockito.lenient()
        .when(sessionExecutionCoordinator.track(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          Supplier<Mono<Void>> preflight = invocation.getArgument(2);
          Supplier<Flux<StreamEventDto>> source = invocation.getArgument(3);
          return Mono.defer(preflight).thenMany(Flux.defer(source));
        });
    org.mockito.Mockito.lenient()
        .when(chatMessageService.createMessage(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenAnswer(invocation -> new ChatMessageEntity(
            "assistant".equals(invocation.getArgument(2)) ? "m_assistant" : "m_user",
            invocation.getArgument(1),
            invocation.getArgument(0),
            invocation.getArgument(2),
            invocation.getArgument(3),
            "[]",
            invocation.getArgument(4),
            CREATED_AT,
            UPDATED_AT));
    org.mockito.Mockito.lenient()
        .when(chatMessageService.toPersistedEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          StreamEventDto event = invocation.getArgument(0);
          return Map.<String, Object>of("id", "event_" + event.type(), "type", event.type());
        });
  }

  @Test
  void confirmationGatewayRequestDoesNotExposeUnusedReplyId() {
    assertThat(java.util.Arrays.stream(ChatToolConfirmationRequest.class.getRecordComponents())
            .map(component -> component.getName())
            .toList())
        .doesNotContain("replyId");
  }

  @Test
  void streamBuildsCurrentUsersChatRequestBeforeCallingGateway() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.ACCEPT_EDITS);
    when(chatAgentGateway.stream(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.replyStart(), StreamEventDto.done()));

    ChatService chatService = newChatService();

    List<StreamEventDto> events = chatService.stream(USER, "s_123", "hello").collectList().block();

    assertThat(events).extracting(StreamEventDto::type).containsExactly("reply_start", "done");

    ArgumentCaptor<ChatAgentRequest> requestCaptor = ArgumentCaptor.forClass(ChatAgentRequest.class);
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionService, permissionService, chatAgentGateway);
    inOrder.verify(sessionService).requireOwnedSession(USER, "s_123");
    inOrder.verify(permissionService).getModeForOwnedSession("s_123");
    inOrder.verify(chatAgentGateway).stream(requestCaptor.capture());
    verify(sessionExecutionCoordinator).track(
        org.mockito.ArgumentMatchers.eq(USER.id()),
        org.mockito.ArgumentMatchers.eq("s_123"),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    assertThat(requestCaptor.getValue())
        .isEqualTo(
            new ChatAgentRequest(USER.id(), "s_123", "hello", PermissionMode.ACCEPT_EDITS));
  }

  @Test
  void streamPersistsUserAssistantContentEventsAndCompletion() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(chatAgentGateway.stream(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(
            StreamEventDto.replyStart(),
            StreamEventDto.textDelta("hi"),
            StreamEventDto.toolCall("read_file", Map.of("path", "a.md")),
            StreamEventDto.done()));

    List<StreamEventDto> events = newChatService().stream(USER, "s_123", " hello ").collectList().block();

    assertThat(events).extracting(StreamEventDto::type)
        .containsExactly("reply_start", "text_delta", "tool_call", "done");
    verify(chatMessageService).createMessage(USER.id(), "s_123", "user", "hello", false);
    verify(chatMessageService).createMessage(USER.id(), "s_123", "assistant", "", true);
    verify(chatMessageService)
        .toPersistedEvent(argThat(event -> "tool_call".equals(event.type())));
    verify(chatMessageService)
        .updateAssistant(eq(USER.id()), eq("m_assistant"), eq("hi"), anyList(), eq(false));
  }

  @Test
  void knowledgeAnswerAppendsReferenceSourcesAsTheFinalTextEvent() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity(
            "s_123", USER.id(), "Knowledge", SessionMode.KNOWLEDGE, CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(knowledgeSearchService.search(USER.id(), "deadline"))
        .thenReturn(List.of(new KnowledgeSearchHit(
            "parent-1", "doc-1", "plan.pdf", 3, "release deadline", 0.5d)));
    when(chatAgentGateway.stream(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(
            StreamEventDto.replyStart(), StreamEventDto.textDelta("回答内容"), StreamEventDto.done()));

    List<StreamEventDto> events = knowledgeChatService().stream(USER, "s_123", "deadline").collectList().block();

    assertThat(events).extracting(StreamEventDto::type)
        .containsExactly("reply_start", "text_delta", "text_delta", "done");
    assertThat(events.get(2).payload().get("delta"))
        .isEqualTo("\n\n参考来源：\n- plan.pdf，第 3 页");
  }

  @Test
  void confirmConstructsGatewayBeforeConsumingClaimedRecordAndSubscribingEvents() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.ACCEPT_EDITS);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.replyStart(), StreamEventDto.done()));

    ChatService chatService = newChatService();

    List<StreamEventDto> events = chatService.confirm(USER, "s_123", "confirm_123", requested(true)).collectList().block();

    assertThat(events).extracting(StreamEventDto::type).containsExactly("reply_start", "done");
    ArgumentCaptor<ChatToolConfirmationRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
        sessionService, permissionService, toolConfirmationService, chatAgentGateway);
    inOrder.verify(sessionService).requireOwnedSession(USER, "s_123");
    inOrder.verify(permissionService).getModeForOwnedSession("s_123");
    inOrder.verify(toolConfirmationService).claim(USER.id(), "s_123", "confirm_123");
    inOrder.verify(chatAgentGateway).confirmExecution(requestCaptor.capture());
    inOrder.verify(toolConfirmationService).consume("confirm_123", claim.processingToken(), persisted(true));
    inOrder.verify(chatAgentGateway).confirm(requestCaptor.getValue());
    verify(sessionExecutionCoordinator).track(
        org.mockito.ArgumentMatchers.eq(USER.id()),
        org.mockito.ArgumentMatchers.eq("s_123"),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    assertThat(requestCaptor.getValue()).isEqualTo(new ChatToolConfirmationRequest(
        USER.id(), "s_123", PermissionMode.ACCEPT_EDITS,
        List.of(new ToolCallDecision(claim.record().toolCalls().getFirst(), true))));
  }

  @Test
  void confirmPersistsTextDeltaToLatestAssistantMessage() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    ChatMessageEntity assistant = new ChatMessageEntity(
        "m_assistant",
        "s_123",
        USER.id(),
        "assistant",
        "Before ",
        "[]",
        true,
        CREATED_AT,
        UPDATED_AT);
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    when(chatMessageService.latestAssistant(USER.id(), "s_123")).thenReturn(assistant);
    when(chatMessageService.readEvents(assistant)).thenReturn(List.of());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.textDelta("Done"), StreamEventDto.done()));

    newChatService().confirm(USER, "s_123", "confirm_123", requested(true)).collectList().block();

    verify(chatMessageService)
        .updateAssistant(eq(USER.id()), eq("m_assistant"), eq("Before Done"), anyList(), eq(false));
  }

  @Test
  void confirmPersistsAndForwardsGroupedDecisionsInStoredToolOrder() {
    ToolCallSnapshot first = new ToolCallSnapshot("call-1", "read_file", java.util.Map.of("path", "a.md"));
    ToolCallSnapshot second = new ToolCallSnapshot("call-2", "shell_command", java.util.Map.of("command", "npm test"));
    ToolConfirmationRecord record = new ToolConfirmationRecord(
        "confirm_123", USER.id().toString(), "s_123", "reply_123", List.of(first, second),
        ConfirmationKind.USER_CONFIRM, Instant.parse("2026-07-04T09:45:00Z"),
        ToolConfirmationStatus.PENDING, null, null, null);
    ToolConfirmationClaim claim = new ToolConfirmationClaim(record, "token_123");
    List<ToolConfirmationDecisionRequest> requested = List.of(
        new ToolConfirmationDecisionRequest("call-2", false),
        new ToolConfirmationDecisionRequest("call-1", true));
    List<ToolConfirmationDecision> persisted = List.of(
        new ToolConfirmationDecision("call-1", true),
        new ToolConfirmationDecision("call-2", false));
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity(
            "s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", "token_123", persisted)).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(Flux.empty());

    newChatService()
        .confirm(USER, "s_123", "confirm_123", requested).blockLast();

    verify(toolConfirmationService).consume("confirm_123", "token_123", persisted);
    ArgumentCaptor<ChatToolConfirmationRequest> captor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    verify(chatAgentGateway).confirm(captor.capture());
    assertThat(captor.getValue().decisions()).containsExactly(
        new ToolCallDecision(first, true), new ToolCallDecision(second, false));
  }

  @Test
  void confirmConsumesBeforeAnUnfinishedGatewayRecoveryFlow() throws InterruptedException {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    CountDownLatch gatewaySubscribed = new CountDownLatch(1);
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(
        Flux.<StreamEventDto>never().doOnSubscribe(ignored -> gatewaySubscribed.countDown()));

    reactor.core.Disposable subscription = newChatService()
        .confirm(USER, "s_123", "confirm_123", requested(true)).subscribe();

    assertThat(gatewaySubscribed.await(5, TimeUnit.SECONDS)).isTrue();
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(toolConfirmationService, chatAgentGateway);
    inOrder.verify(toolConfirmationService).consume("confirm_123", claim.processingToken(), persisted(true));
    inOrder.verify(chatAgentGateway).confirm(org.mockito.ArgumentMatchers.any());
    subscription.dispose();
  }

  @Test
  void cancellingAnUnfinishedGatewayRecoveryFlowDoesNotUndoConsumption() throws InterruptedException {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    CountDownLatch gatewaySubscribed = new CountDownLatch(1);
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(
        Flux.<StreamEventDto>never().doOnSubscribe(ignored -> gatewaySubscribed.countDown()));

    reactor.core.Disposable subscription = newChatService()
        .confirm(USER, "s_123", "confirm_123", requested(true)).subscribe();

    assertThat(gatewaySubscribed.await(5, TimeUnit.SECONDS)).isTrue();
    subscription.dispose();

    verify(toolConfirmationService).claim(USER.id(), "s_123", "confirm_123");
    verify(toolConfirmationService).consume("confirm_123", claim.processingToken(), persisted(true));
    org.mockito.Mockito.verifyNoMoreInteractions(toolConfirmationService);
  }

  @Test
  void confirmForwardsFalseToGatewayAfterConsumption() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(false))).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(Flux.empty());

    newChatService()
        .confirm(USER, "s_123", "confirm_123", requested(false))
        .blockLast();

    ArgumentCaptor<ChatToolConfirmationRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    verify(chatAgentGateway).confirm(requestCaptor.capture());
    assertThat(requestCaptor.getValue().decisions().getFirst().confirmed()).isFalse();
    verify(toolConfirmationService).consume("confirm_123", claim.processingToken(), persisted(false));
  }

  @Test
  void confirmGatewayErrorKeepsConsumedRecordAndReturnsOneErrorEvent() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.error(new IllegalStateException("gateway failed")));

    List<StreamEventDto> events = newChatService()
        .confirm(USER, "s_123", "confirm_123", requested(true)).collectList().block();

    assertThat(events).containsExactly(StreamEventDto.error("gateway failed"));
    verify(toolConfirmationService).consume("confirm_123", claim.processingToken(), persisted(true));
  }

  @Test
  void confirmGatewayConstructionFailureRollsBackAndReturnsRetryableCode() {
    ToolConfirmationClaim claim = stubValidClaim();
    org.mockito.Mockito.doThrow(new IllegalStateException("gateway construction failed"))
        .when(chatAgentGateway).confirmExecution(any());
    when(toolConfirmationService.rollbackIfProcessing(
        "confirm_123", claim.processingToken())).thenReturn(Mono.just(true));

    StepVerifier.create(newChatService().confirm(
            USER, "s_123", "confirm_123", requested(true)))
        .expectErrorSatisfies(error -> assertErrorCode(
            error, HttpStatus.SERVICE_UNAVAILABLE, "TOOL_CONFIRMATION_RETRYABLE"))
        .verify();
    verify(toolConfirmationService, never()).consume(any(), any(), anyList());
  }

  @Test
  void confirmCoordinatorRegistrationFailureRollsBackBeforeSourceSubscription() {
    ToolConfirmationClaim claim = stubValidClaim();
    org.mockito.Mockito.doReturn(Flux.error(new IllegalStateException("registration failed")))
        .when(sessionExecutionCoordinator).track(
            anyLong(), anyString(), any(), any(), any());
    when(toolConfirmationService.rollbackIfProcessing(
        "confirm_123", claim.processingToken())).thenReturn(Mono.just(true));

    StepVerifier.create(newChatService().confirm(
            USER, "s_123", "confirm_123", requested(true)))
        .expectErrorSatisfies(error -> assertErrorCode(
            error, HttpStatus.SERVICE_UNAVAILABLE, "TOOL_CONFIRMATION_RETRYABLE"))
        .verify();
    verify(toolConfirmationService, never()).consume(any(), any(), anyList());
  }

  @Test
  void uncertainConsumeResultDoesNotReopenTheConfirmation() {
    ToolConfirmationClaim claim = stubValidClaim();
    when(toolConfirmationService.consume("confirm_123", claim.processingToken(), persisted(true)))
        .thenReturn(Mono.error(new IllegalStateException("response lost")));
    when(toolConfirmationService.rollbackIfProcessing(
        "confirm_123", claim.processingToken())).thenReturn(Mono.just(false));

    StepVerifier.create(newChatService().confirm(
            USER, "s_123", "confirm_123", requested(true)))
        .expectErrorSatisfies(error -> assertErrorCode(
            error, HttpStatus.CONFLICT, "TOOL_CONFIRMATION_CONSUMED"))
        .verify();
    verify(chatAgentGateway, never()).confirm(any());
  }

  @Test
  void cancellationAfterConsumptionPreservesCancellationAndConsumedMetadata() {
    ToolConfirmationClaim claim = stubValidClaim();
    when(toolConfirmationService.consume(
        "confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    ResponseStatusException cancellation = sessionCancelling();
    org.mockito.Mockito.doAnswer(invocation -> {
          Supplier<Mono<Void>> preflight = invocation.getArgument(2);
          return Mono.defer(preflight)
              .thenMany(Flux.<StreamEventDto>error(cancellation));
        })
        .when(sessionExecutionCoordinator).track(
            anyLong(), anyString(), any(), any(), any());

    StepVerifier.create(newChatService().confirm(
            USER, "s_123", "confirm_123", requested(true)))
        .expectErrorSatisfies(error -> {
          assertErrorCode(error, HttpStatus.CONFLICT, "SESSION_CANCELLING");
          assertThat(((ResponseStatusException) error).getHeaders()
              .getFirst("X-Tool-Confirmation-Consumed")).isEqualTo("true");
        })
        .verify();

    verify(toolConfirmationService, never()).rollbackIfProcessing(any(), any());
    verify(chatAgentGateway, never()).confirm(any());
  }

  @Test
  void eventFailureAfterConsumptionStaysConsumedAndReturnsAnErrorEvent() {
    ToolConfirmationClaim claim = stubValidClaim();
    when(toolConfirmationService.consume(
        "confirm_123", claim.processingToken(), persisted(true))).thenReturn(Mono.empty());
    org.mockito.Mockito.doReturn(new AgentExecution<>(
            Flux.error(new IllegalStateException("tool failed")), Mono.empty()))
        .when(chatAgentGateway).confirmExecution(any());

    StepVerifier.create(newChatService().confirm(
            USER, "s_123", "confirm_123", requested(true)))
        .expectNext(StreamEventDto.error("tool failed"))
        .verifyComplete();
    verify(toolConfirmationService, never()).rollbackIfProcessing(any(), any());
  }

  @Test
  void confirmOwnedSessionErrorDoesNotAccessPermissionClaimOrGateway() {
    when(sessionService.requireOwnedSession(USER, "missing"))
        .thenThrow(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND));

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> newChatService()
            .confirm(USER, "missing", "confirm_123", requested(true)).blockLast());

    verify(permissionService, org.mockito.Mockito.never()).getModeForOwnedSession("missing");
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .claim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    verify(chatAgentGateway, org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void confirmClaimErrorDoesNotAccessGatewayOrTransitionLease() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123"))
        .thenReturn(Mono.error(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.CONFLICT)));

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> newChatService()
            .confirm(USER, "s_123", "confirm_123", requested(true)).blockLast());

    verify(chatAgentGateway, org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any());
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .consume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void confirmReleasesClaimAndRejectsMissingDuplicateOrUnknownToolIds() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity(
            "s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123"))
        .thenReturn(Mono.just(claim));
    when(toolConfirmationService.release("confirm_123", claim.processingToken()))
        .thenReturn(Mono.empty());
    ChatService service = newChatService();

    List<List<ToolConfirmationDecisionRequest>> invalidRequests = List.of(
        List.of(),
        List.of(
            new ToolConfirmationDecisionRequest("tool_123", true),
            new ToolConfirmationDecisionRequest("tool_123", false)),
        List.of(new ToolConfirmationDecisionRequest("unknown", true)));
    for (List<ToolConfirmationDecisionRequest> request : invalidRequests) {
      ResponseStatusException error = org.junit.jupiter.api.Assertions.assertThrows(
          ResponseStatusException.class,
          () -> service.confirm(USER, "s_123", "confirm_123", request).blockLast());
      assertThat(error.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    verify(toolConfirmationService, org.mockito.Mockito.times(3))
        .release("confirm_123", claim.processingToken());
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .consume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList());
    verify(chatAgentGateway, org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any());
  }

  private ToolConfirmationClaim claim(String replyId, String toolCallId) {
    ToolCallSnapshot toolCall = new ToolCallSnapshot(toolCallId, "deploy", java.util.Map.of("env", "prod"));
    ToolConfirmationRecord record = new ToolConfirmationRecord(
        "confirm_123", USER.id().toString(), "s_123", replyId, List.of(toolCall), ConfirmationKind.USER_CONFIRM,
        Instant.parse("2026-07-04T09:45:00Z"), ToolConfirmationStatus.PENDING, null, null, null);
    return new ToolConfirmationClaim(record, "token_123");
  }

  private ToolConfirmationClaim stubValidClaim() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123")).thenReturn(
        new ChatSessionEntity(
            "s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123"))
        .thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123"))
        .thenReturn(Mono.just(claim));
    return claim;
  }

  private void assertErrorCode(
      Throwable error, HttpStatus status, String expectedCode) {
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    ResponseStatusException responseError = (ResponseStatusException) error;
    assertThat(responseError.getStatusCode()).isEqualTo(status);
    assertThat(responseError.getHeaders().getFirst("X-Error-Code"))
        .isEqualTo(expectedCode);
  }

  private ResponseStatusException sessionCancelling() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Session is being cancelled") {
      private final HttpHeaders headers = cancellationHeaders();

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }

  private HttpHeaders cancellationHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Error-Code", "SESSION_CANCELLING");
    return headers;
  }

  private List<ToolConfirmationDecisionRequest> requested(boolean confirmed) {
    return List.of(new ToolConfirmationDecisionRequest("tool_123", confirmed));
  }

  private List<ToolConfirmationDecision> persisted(boolean confirmed) {
    return List.of(new ToolConfirmationDecision("tool_123", confirmed));
  }

  @Test
  void streamPassesUnderlyingCompletionToExecutionCoordinator() {
    Sinks.Empty<Void> completion = Sinks.empty();
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    org.mockito.Mockito.doReturn(
            new AgentExecution<>(Flux.just(StreamEventDto.done()), completion.asMono()))
        .when(chatAgentGateway).streamExecution(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(invocation ->
            ((Supplier<Flux<StreamEventDto>>) invocation.getArgument(2)).get())
        .when(sessionExecutionCoordinator).track(
            org.mockito.ArgumentMatchers.eq(USER.id()),
            org.mockito.ArgumentMatchers.eq("s_123"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());

    ChatService service = new ChatService(
        sessionService,
        chatAgentGateway,
        permissionService,
        toolConfirmationService,
        sessionExecutionCoordinator,
        chatMessageService);
    assertThat(service.stream(USER, "s_123", "hello").collectList().block())
        .containsExactly(StreamEventDto.done());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Supplier<Mono<Void>>> completionCaptor =
        ArgumentCaptor.forClass(Supplier.class);
    verify(sessionExecutionCoordinator).track(
        org.mockito.ArgumentMatchers.eq(USER.id()),
        org.mockito.ArgumentMatchers.eq("s_123"),
        org.mockito.ArgumentMatchers.any(),
        completionCaptor.capture());
    StepVerifier.create(completionCaptor.getValue().get())
        .expectSubscription()
        .then(() -> completion.tryEmitEmpty())
        .verifyComplete();
  }

  private ChatService newChatService() {
    return new ChatService(
        sessionService,
        chatAgentGateway,
        permissionService,
        toolConfirmationService,
        sessionExecutionCoordinator,
        chatMessageService);
  }

  @SuppressWarnings("unchecked")
  private ChatService knowledgeChatService() {
    ObjectProvider<KnowledgeSearchService> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(knowledgeSearchService);
    return new ChatService(
        sessionService,
        chatAgentGateway,
        permissionService,
        toolConfirmationService,
        sessionExecutionCoordinator,
        chatMessageService,
        provider);
  }
}
