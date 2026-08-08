package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.session.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageService {

  private static final TypeReference<List<Map<String, Object>>> EVENTS_TYPE =
      new TypeReference<>() {};

  private final ChatMessageMapper chatMessageMapper;
  private final SessionService sessionService;
  private final ObjectMapper objectMapper;

  public ChatMessageService(
      ChatMessageMapper chatMessageMapper,
      SessionService sessionService,
      ObjectMapper objectMapper) {
    this.chatMessageMapper = chatMessageMapper;
    this.sessionService = sessionService;
    this.objectMapper = objectMapper;
  }

  public List<ChatMessageDto> listMessages(CurrentUser currentUser, String sessionId) {
    sessionService.requireOwnedSession(currentUser, sessionId);
    return chatMessageMapper.findByOwnedSession(currentUser.id(), sessionId).stream()
        .map(this::toDto)
        .toList();
  }

  public ChatMessageEntity createMessage(
      Long userId, String sessionId, String role, String content, boolean loading) {
    LocalDateTime now = LocalDateTime.now();
    ChatMessageEntity message = new ChatMessageEntity();
    message.setId("m_" + UUID.randomUUID().toString().replace("-", ""));
    message.setUserId(userId);
    message.setSessionId(sessionId);
    message.setRole(role);
    message.setContent(content == null ? "" : content);
    message.setEventsJson("[]");
    message.setLoading(loading);
    message.setCreatedAt(now);
    message.setUpdatedAt(now);
    chatMessageMapper.insert(message);
    return message;
  }

  public void updateAssistant(
      Long userId,
      String messageId,
      String content,
      List<Map<String, Object>> events,
      boolean loading) {
    chatMessageMapper.updateContentEventsAndLoading(
        userId, messageId, content == null ? "" : content, writeEvents(events), loading, LocalDateTime.now());
  }

  public ChatMessageEntity latestAssistant(Long userId, String sessionId) {
    return chatMessageMapper.findLatestAssistant(userId, sessionId);
  }

  public int deleteBySession(Long userId, String sessionId) {
    return chatMessageMapper.deleteByOwnedSession(userId, sessionId);
  }

  public List<Map<String, Object>> readEvents(ChatMessageEntity message) {
    return readEvents(message.getEventsJson());
  }

  public ChatMessageDto toDto(ChatMessageEntity message) {
    String visibleContent =
        "assistant".equals(message.getRole())
            ? InternalPathRedactor.redact(message.getContent())
            : message.getContent();
    return new ChatMessageDto(
        message.getId(),
        message.getRole(),
        visibleContent,
        readEvents(message),
        Boolean.TRUE.equals(message.getLoading()),
        message.getCreatedAt(),
        message.getUpdatedAt());
  }

  public Map<String, Object> toPersistedEvent(StreamEventDto event) {
    Map<String, Object> persisted = new LinkedHashMap<>();
    persisted.put("id", "event_" + UUID.randomUUID().toString().replace("-", ""));
    persisted.put("type", event.type());
    persisted.putAll(event.jsonFields());
    return persisted;
  }

  private List<Map<String, Object>> readEvents(String eventsJson) {
    if (eventsJson == null || eventsJson.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(eventsJson, EVENTS_TYPE);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to parse chat message events", exception);
    }
  }

  private String writeEvents(List<Map<String, Object>> events) {
    try {
      return objectMapper.writeValueAsString(events == null ? new ArrayList<>() : events);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize chat message events", exception);
    }
  }
}
