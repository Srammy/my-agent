package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.agent.AgentExecution;
import com.example.myagent.auth.CurrentUser;
import com.example.myagent.chat.ChatAgentGateway;
import com.example.myagent.chat.ChatAgentRequest;
import com.example.myagent.chat.ChatService;
import com.example.myagent.chat.StreamEventDto;
import com.example.myagent.knowledge.search.KnowledgeSearchHit;
import com.example.myagent.knowledge.search.KnowledgeSearchService;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionExecutionCoordinator;
import com.example.myagent.session.SessionMode;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class KnowledgeChatServiceTest {

  @Test
  void usesTheNormalAgentWithGroundedKnowledgeContext() {
    SessionService sessions = mock(SessionService.class);
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    PermissionService permissions = mock(PermissionService.class);
    SessionExecutionCoordinator coordinator = mock(SessionExecutionCoordinator.class);
    KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    CurrentUser user = new CurrentUser(7L, "alice", "USER");
    when(sessions.requireOwnedSession(user, "s-1"))
        .thenReturn(new ChatSessionEntity("s-1", 7L, "KB", SessionMode.KNOWLEDGE, now(), now()));
    when(permissions.getModeForOwnedSession("s-1")).thenReturn(PermissionMode.DEFAULT);
    when(search.search(7L, "上线条件是什么？"))
        .thenReturn(List.of(new KnowledgeSearchHit("doc_p_0", "doc-1", "guide.pdf", 3, "上线前完成验收", 0.9)));
    when(gateway.streamExecution(any(ChatAgentRequest.class)))
        .thenReturn(new AgentExecution<>(Flux.just(StreamEventDto.done()), Mono.empty()));
    when(coordinator.track(anyLong(), anyString(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<Flux<StreamEventDto>>) invocation.getArgument(2)).get());

    ChatService service =
        new ChatService(
            sessions,
            gateway,
            permissions,
            mock(ToolConfirmationService.class),
            coordinator,
            null,
            search);

    assertThat(service.stream(user, "s-1", "上线条件是什么？").collectList().block())
        .extracting(StreamEventDto::type)
        .containsExactly("done");
    var request = org.mockito.ArgumentCaptor.forClass(ChatAgentRequest.class);
    verify(gateway).streamExecution(request.capture());
    assertThat(request.getValue().message()).contains("知识库上下文", "上线前完成验收", "guide.pdf");
  }

  @Test
  void refusesWithoutCallingAgentWhenKnowledgeSearchHasNoHit() {
    SessionService sessions = mock(SessionService.class);
    ChatAgentGateway gateway = mock(ChatAgentGateway.class);
    PermissionService permissions = mock(PermissionService.class);
    KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    CurrentUser user = new CurrentUser(7L, "alice", "USER");
    when(sessions.requireOwnedSession(user, "s-1"))
        .thenReturn(new ChatSessionEntity("s-1", 7L, "KB", SessionMode.KNOWLEDGE, now(), now()));
    when(permissions.getModeForOwnedSession("s-1")).thenReturn(PermissionMode.DEFAULT);
    when(search.search(7L, "无关问题")).thenReturn(List.of());

    ChatService service =
        new ChatService(
            sessions,
            gateway,
            permissions,
            mock(ToolConfirmationService.class),
            mock(SessionExecutionCoordinator.class),
            null,
            search);

    assertThat(service.stream(user, "s-1", "无关问题").collectList().block())
        .extracting(StreamEventDto::type)
        .containsExactly("text_delta", "done");
    verify(gateway, never()).streamExecution(any());
  }

  private static LocalDateTime now() {
    return LocalDateTime.of(2026, 8, 9, 12, 0);
  }
}
