package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
      @Valid @RequestBody ChatRequest request,
      ServerHttpResponse response) {
    return propagateErrorCode(
        chatService.stream(currentUser, sessionId, request.message()).map(this::toNdjsonLine),
        response);
  }

  @PostMapping(path = "/{sessionId}/tool-confirmations/{confirmationId}", produces = "application/x-ndjson")
  public Flux<String> confirm(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String sessionId,
      @PathVariable String confirmationId,
      @Valid @RequestBody ToolConfirmationRequest request,
      ServerHttpResponse response) {
    return propagateErrorCode(
        chatService
            .confirm(currentUser, sessionId, confirmationId, request.decisions())
            .map(this::toNdjsonLine),
        response);
  }

  private <T> Flux<T> propagateErrorCode(Flux<T> source, ServerHttpResponse response) {
    return source.doOnError(ResponseStatusException.class, error -> {
      String errorCode = error.getHeaders().getFirst("X-Error-Code");
      if (errorCode != null) {
        response.getHeaders().set("X-Error-Code", errorCode);
      }
    });
  }

  private String toNdjsonLine(StreamEventDto event) {
    try {
      return objectMapper.writeValueAsString(event) + "\n";
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize stream event", exception);
    }
  }
}
