package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat/sessions")
public class ChatController {

  private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

  private final ChatService chatService;
  private final ObjectMapper objectMapper;

  public ChatController(ChatService chatService, ObjectMapper objectMapper) {
    this.chatService = chatService;
    this.objectMapper = objectMapper;
  }

  @PostMapping(path = "/{sessionId}/stream", produces = "application/x-ndjson")
  public Flux<String> stream(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String sessionId,
      @Valid @RequestBody ChatRequest request) {
    return chatService.stream(currentUser, sessionId, request.message()).map(this::toNdjsonLine);
  }

  private String toNdjsonLine(StreamEventDto event) {
    try {
      return objectMapper.writeValueAsString(event) + "\n";
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize stream event", exception);
    }
  }
}
