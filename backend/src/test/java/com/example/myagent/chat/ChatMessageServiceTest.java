package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-18T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-18T09:31:00");

  @Mock private ChatMessageMapper chatMessageMapper;
  @Mock private SessionService sessionService;

  private ChatMessageService service;

  @BeforeEach
  void setUp() {
    service = new ChatMessageService(chatMessageMapper, sessionService, new ObjectMapper());
  }

  @Test
  void listMessagesRequiresOwnedSessionAndReturnsMessagesInMapperOrder() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Chat", CREATED_AT, UPDATED_AT));
    when(chatMessageMapper.findByOwnedSession(USER.id(), "s_123"))
        .thenReturn(List.of(
            message("m_1", "user", "hello", "[]", CREATED_AT),
            message("m_2", "assistant", "hi", "[]", CREATED_AT.plusSeconds(1))));

    List<ChatMessageDto> messages = service.listMessages(USER, "s_123");

    assertThat(messages).extracting(ChatMessageDto::id).containsExactly("m_1", "m_2");
    verify(sessionService).requireOwnedSession(USER, "s_123");
    verify(chatMessageMapper).findByOwnedSession(USER.id(), "s_123");
  }

  @Test
  void mapperUsesUpdatedAtAsTieBreakerForSameSecondMessages() throws Exception {
    String mapperSource =
        Files.readString(Path.of("src/main/java/com/example/myagent/chat/ChatMessageMapper.java"));

    assertThat(mapperSource)
        .contains(".orderByAsc(ChatMessageEntity::getCreatedAt)\n"
            + "            .orderByAsc(ChatMessageEntity::getUpdatedAt)");
  }

  @Test
  void listMessagesDeserializesPersistedEvents() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Chat", CREATED_AT, UPDATED_AT));
    when(chatMessageMapper.findByOwnedSession(USER.id(), "s_123"))
        .thenReturn(List.of(message(
            "m_1",
            "assistant",
            "Need permission",
            """
            [{"id":"event_1","type":"permission_required","confirmationId":"c_1","toolCalls":[{"toolCallId":"call_1","toolName":"read_file","toolInput":{"path":"a.md"}}]}]
            """,
            CREATED_AT)));

    ChatMessageDto message = service.listMessages(USER, "s_123").getFirst();

    assertThat(message.events()).singleElement().satisfies(event -> {
      assertThat(event).containsEntry("id", "event_1");
      assertThat(event).containsEntry("type", "permission_required");
      assertThat(event).containsEntry("confirmationId", "c_1");
      assertThat(event.get("toolCalls")).isInstanceOf(List.class);
    });
  }

  @Test
  void toPersistedEventKeepsPayloadAndAddsStableUiId() {
    Map<String, Object> event =
        service.toPersistedEvent(StreamEventDto.toolCall("read_file", Map.of("path", "a.md")));

    assertThat(event.get("id")).asString().startsWith("event_");
    assertThat(event)
        .containsEntry("type", "tool_call")
        .containsEntry("tool", "read_file")
        .containsEntry("input", Map.of("path", "a.md"));
  }

  private ChatMessageEntity message(
      String id, String role, String content, String eventsJson, LocalDateTime createdAt) {
    return new ChatMessageEntity(
        id, "s_123", USER.id(), role, content, eventsJson, false, createdAt, createdAt);
  }
}
