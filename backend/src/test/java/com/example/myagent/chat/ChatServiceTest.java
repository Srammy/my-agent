package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ConfirmationKind;
import com.example.myagent.toolconfirmation.ToolCallSnapshot;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationRecord;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import com.example.myagent.toolconfirmation.ToolConfirmationStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-04T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-04T09:45:00");

  @Mock private SessionService sessionService;
  @Mock private ChatAgentGateway chatAgentGateway;
  @Mock private PermissionService permissionService;
  @Mock private ToolConfirmationService toolConfirmationService;

  @Test
  void streamBuildsCurrentUsersChatRequestBeforeCallingGateway() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.ACCEPT_EDITS);
    when(chatAgentGateway.stream(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.replyStart(), StreamEventDto.done()));

    ChatService chatService = new ChatService(
        sessionService, chatAgentGateway, permissionService, toolConfirmationService);

    List<StreamEventDto> events = chatService.stream(USER, "s_123", "hello").collectList().block();

    assertThat(events).extracting(StreamEventDto::type).containsExactly("reply_start", "done");

    ArgumentCaptor<ChatAgentRequest> requestCaptor = ArgumentCaptor.forClass(ChatAgentRequest.class);
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sessionService, permissionService, chatAgentGateway);
    inOrder.verify(sessionService).requireOwnedSession(USER, "s_123");
    inOrder.verify(permissionService).getModeForOwnedSession("s_123");
    inOrder.verify(chatAgentGateway).stream(requestCaptor.capture());
    assertThat(requestCaptor.getValue())
        .isEqualTo(
            new ChatAgentRequest(USER.id(), "s_123", "hello", PermissionMode.ACCEPT_EDITS));
  }

  @Test
  void confirmUsesClaimedRecordAndCompletesAfterGatewayEvents() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.ACCEPT_EDITS);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.replyStart(), StreamEventDto.done()));
    when(toolConfirmationService.complete("confirm_123", claim.processingToken(), true)).thenReturn(Mono.empty());

    ChatService chatService = new ChatService(
        sessionService, chatAgentGateway, permissionService, toolConfirmationService);

    List<StreamEventDto> events = chatService.confirm(USER, "s_123", "confirm_123", true).collectList().block();

    assertThat(events).extracting(StreamEventDto::type).containsExactly("reply_start", "done");
    ArgumentCaptor<ChatToolConfirmationRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
        sessionService, permissionService, toolConfirmationService, chatAgentGateway);
    inOrder.verify(sessionService).requireOwnedSession(USER, "s_123");
    inOrder.verify(permissionService).getModeForOwnedSession("s_123");
    inOrder.verify(toolConfirmationService).claim(USER.id(), "s_123", "confirm_123");
    inOrder.verify(chatAgentGateway).confirm(requestCaptor.capture());
    inOrder.verify(toolConfirmationService).complete("confirm_123", claim.processingToken(), true);
    assertThat(requestCaptor.getValue()).isEqualTo(new ChatToolConfirmationRequest(
        USER.id(), "s_123", PermissionMode.ACCEPT_EDITS, "reply_123",
        claim.record().toolCall(), true));
  }

  @Test
  void confirmForwardsFalseToGatewayAndComplete() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(Flux.empty());
    when(toolConfirmationService.complete("confirm_123", claim.processingToken(), false)).thenReturn(Mono.empty());

    new ChatService(sessionService, chatAgentGateway, permissionService, toolConfirmationService)
        .confirm(USER, "s_123", "confirm_123", false)
        .blockLast();

    ArgumentCaptor<ChatToolConfirmationRequest> requestCaptor =
        ArgumentCaptor.forClass(ChatToolConfirmationRequest.class);
    verify(chatAgentGateway).confirm(requestCaptor.capture());
    assertThat(requestCaptor.getValue().confirmed()).isFalse();
    verify(toolConfirmationService).complete("confirm_123", claim.processingToken(), false);
  }

  @Test
  void confirmGatewayErrorReleasesLeaseAndReturnsOneErrorEvent() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.error(new IllegalStateException("gateway failed")));
    when(toolConfirmationService.release("confirm_123", claim.processingToken())).thenReturn(Mono.empty());

    List<StreamEventDto> events = new ChatService(
        sessionService, chatAgentGateway, permissionService, toolConfirmationService)
        .confirm(USER, "s_123", "confirm_123", true).collectList().block();

    assertThat(events).containsExactly(StreamEventDto.error("gateway failed"));
    verify(toolConfirmationService).release("confirm_123", claim.processingToken());
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void confirmCompleteErrorDoesNotReleaseLeaseAndReturnsOneErrorEvent() {
    ToolConfirmationClaim claim = claim("reply_123", "tool_123");
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);
    when(toolConfirmationService.claim(USER.id(), "s_123", "confirm_123")).thenReturn(Mono.just(claim));
    when(chatAgentGateway.confirm(org.mockito.ArgumentMatchers.any())).thenReturn(Flux.empty());
    when(toolConfirmationService.complete("confirm_123", claim.processingToken(), true))
        .thenReturn(Mono.error(new IllegalStateException("complete failed")));

    List<StreamEventDto> events = new ChatService(
        sessionService, chatAgentGateway, permissionService, toolConfirmationService)
        .confirm(USER, "s_123", "confirm_123", true).collectList().block();

    assertThat(events).containsExactly(StreamEventDto.error("complete failed"));
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .release(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void confirmOwnedSessionErrorDoesNotAccessPermissionClaimOrGateway() {
    when(sessionService.requireOwnedSession(USER, "missing"))
        .thenThrow(new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND));

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> new ChatService(sessionService, chatAgentGateway, permissionService, toolConfirmationService)
            .confirm(USER, "missing", "confirm_123", true).blockLast());

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
        () -> new ChatService(sessionService, chatAgentGateway, permissionService, toolConfirmationService)
            .confirm(USER, "s_123", "confirm_123", true).blockLast());

    verify(chatAgentGateway, org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.any());
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    verify(toolConfirmationService, org.mockito.Mockito.never())
        .release(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private ToolConfirmationClaim claim(String replyId, String toolCallId) {
    ToolCallSnapshot toolCall = new ToolCallSnapshot(toolCallId, "deploy", java.util.Map.of("env", "prod"));
    ToolConfirmationRecord record = new ToolConfirmationRecord(
        "confirm_123", USER.id(), "s_123", replyId, toolCall, ConfirmationKind.USER_CONFIRM,
        Instant.parse("2026-07-04T09:45:00Z"), ToolConfirmationStatus.PENDING, null, null, null);
    return new ToolConfirmationClaim(record, "token_123");
  }
}
