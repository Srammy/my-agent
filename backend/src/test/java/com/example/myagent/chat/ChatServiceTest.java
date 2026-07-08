package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-04T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-04T09:45:00");

  @Mock private SessionService sessionService;
  @Mock private ChatAgentGateway chatAgentGateway;
  @Mock private PermissionService permissionService;

  @Test
  void streamBuildsCurrentUsersChatRequestBeforeCallingGateway() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.ACCEPT_EDITS);
    when(chatAgentGateway.stream(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(StreamEventDto.replyStart(), StreamEventDto.done()));

    ChatService chatService = new ChatService(sessionService, chatAgentGateway, permissionService);

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
}
