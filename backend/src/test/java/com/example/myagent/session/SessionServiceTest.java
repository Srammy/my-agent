package com.example.myagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.chat.ChatMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

  private static final CurrentUser USER_A = new CurrentUser(1L, "alice", "USER");

  @Mock private ChatSessionMapper chatSessionMapper;
  @Mock private ChatMessageMapper chatMessageMapper;
  @Mock private SessionExecutionCoordinator sessionExecutionCoordinator;

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionService = new SessionService(chatSessionMapper, chatMessageMapper, sessionExecutionCoordinator);
  }

  @Test
  void createSessionUsesCurrentUserAndDefaultTitle() {
    when(chatSessionMapper.insert(any(ChatSessionEntity.class))).thenReturn(1);

    ChatSessionEntity created = sessionService.createSession(USER_A, "   ", null);

    ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
    verify(chatSessionMapper).insert(captor.capture());
    ChatSessionEntity saved = captor.getValue();
    assertThat(saved.getId()).startsWith("s_").hasSize(34);
    assertThat(saved.getUserId()).isEqualTo(USER_A.id());
    assertThat(saved.getTitle()).isEqualTo("\u65b0\u4f1a\u8bdd");
    assertThat(saved.getMode()).isEqualTo(SessionMode.NORMAL);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());

    assertThat(created.getId()).isEqualTo(saved.getId());
    assertThat(created.getUserId()).isEqualTo(saved.getUserId());
    assertThat(created.getTitle()).isEqualTo(saved.getTitle());
    assertThat(created.getMode()).isEqualTo(SessionMode.NORMAL);
  }

  @Test
  void createSessionPersistsExplicitKnowledgeMode() {
    when(chatSessionMapper.insert(any(ChatSessionEntity.class))).thenReturn(1);

    ChatSessionEntity created =
        sessionService.createSession(USER_A, "Knowledge base", SessionMode.KNOWLEDGE);

    ArgumentCaptor<ChatSessionEntity> captor = ArgumentCaptor.forClass(ChatSessionEntity.class);
    verify(chatSessionMapper).insert(captor.capture());
    assertThat(captor.getValue().getMode()).isEqualTo(SessionMode.KNOWLEDGE);
    assertThat(created.getMode()).isEqualTo(SessionMode.KNOWLEDGE);
  }

  @Test
  void listSessionsReturnsOnlyCurrentUsersSessions() {
    ChatSessionEntity session =
        new ChatSessionEntity(
            "s_a",
            USER_A.id(),
            "Alice session",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(chatSessionMapper.findByUserId(USER_A.id())).thenReturn(List.of(session));

    List<ChatSessionEntity> sessions = sessionService.listSessions(USER_A);

    assertThat(sessions).containsExactly(session);
    verify(chatSessionMapper).findByUserId(USER_A.id());
  }

  @Test
  void requireOwnedSessionRejectsOtherUsersSession() {
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_b")).thenReturn(null);

    assertThatThrownBy(() -> sessionService.requireOwnedSession(USER_A, "s_b"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void deleteSessionUsesCurrentUserScope() {
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a"))
        .thenReturn(new ChatSessionEntity(
            "s_a", USER_A.id(), "Alice session", LocalDateTime.now(), LocalDateTime.now()));
    when(sessionExecutionCoordinator.cancelAndAwait(USER_A.id(), "s_a"))
        .thenReturn(Mono.empty());
    when(chatSessionMapper.deleteOwnedById(USER_A.id(), "s_a")).thenReturn(1);

    sessionService.deleteSession(USER_A, "s_a").block();

    verify(sessionExecutionCoordinator).cancelAndAwait(USER_A.id(), "s_a");
    verify(chatMessageMapper).deleteByOwnedSession(USER_A.id(), "s_a");
    verify(chatSessionMapper).deleteOwnedById(USER_A.id(), "s_a");
  }

  @Test
  void deleteSessionRejectsOtherUsersSession() {
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_b")).thenReturn(null);

    assertThatThrownBy(() -> sessionService.deleteSession(USER_A, "s_b").block())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verify(sessionExecutionCoordinator, org.mockito.Mockito.never())
        .cancelAndAwait(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
    verify(chatSessionMapper, org.mockito.Mockito.never())
        .deleteOwnedById(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void deleteSessionDoesNotDeleteBeforeCancellationCompletes() {
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a"))
        .thenReturn(new ChatSessionEntity(
            "s_a", USER_A.id(), "Alice session", LocalDateTime.now(), LocalDateTime.now()));
    when(sessionExecutionCoordinator.cancelAndAwait(USER_A.id(), "s_a"))
        .thenReturn(Mono.never());

    StepVerifier.create(sessionService.deleteSession(USER_A, "s_a"))
        .then(() -> verify(sessionExecutionCoordinator, org.mockito.Mockito.timeout(1000))
            .cancelAndAwait(USER_A.id(), "s_a"))
        .thenCancel()
        .verify();

    verify(chatSessionMapper, org.mockito.Mockito.never())
        .deleteOwnedById(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void renameSessionUpdatesOnlyCurrentUsersSession() {
    LocalDateTime createdAt = LocalDateTime.parse("2026-07-18T09:30:00");
    ChatSessionEntity existing =
        new ChatSessionEntity("s_a", USER_A.id(), "Old title", createdAt, createdAt);
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a")).thenReturn(existing);
    when(chatSessionMapper.updateTitleOwnedById(eq(USER_A.id()), eq("s_a"), eq("New title"), any()))
        .thenReturn(1);
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a"))
        .thenReturn(existing)
        .thenReturn(new ChatSessionEntity("s_a", USER_A.id(), "New title", createdAt, createdAt.plusMinutes(1)));

    ChatSessionEntity renamed = sessionService.renameSession(USER_A, "s_a", " New title ");

    assertThat(renamed.getTitle()).isEqualTo("New title");
    verify(chatSessionMapper).updateTitleOwnedById(eq(USER_A.id()), eq("s_a"), eq("New title"), any());
  }

  @Test
  void renameSessionUsesDefaultTitleForBlankInput() {
    ChatSessionEntity existing =
        new ChatSessionEntity("s_a", USER_A.id(), "Old title", LocalDateTime.now(), LocalDateTime.now());
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a")).thenReturn(existing);
    when(chatSessionMapper.updateTitleOwnedById(eq(USER_A.id()), eq("s_a"), eq("\u65b0\u4f1a\u8bdd"), any()))
        .thenReturn(1);
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_a"))
        .thenReturn(existing)
        .thenReturn(new ChatSessionEntity("s_a", USER_A.id(), "\u65b0\u4f1a\u8bdd", existing.getCreatedAt(), LocalDateTime.now()));

    ChatSessionEntity renamed = sessionService.renameSession(USER_A, "s_a", "   ");

    assertThat(renamed.getTitle()).isEqualTo("\u65b0\u4f1a\u8bdd");
  }

  @Test
  void renameSessionRejectsOtherUsersSession() {
    when(chatSessionMapper.findOwnedById(USER_A.id(), "s_b")).thenReturn(null);

    assertThatThrownBy(() -> sessionService.renameSession(USER_A, "s_b", "New title"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verify(chatSessionMapper, org.mockito.Mockito.never())
        .updateTitleOwnedById(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }
}
