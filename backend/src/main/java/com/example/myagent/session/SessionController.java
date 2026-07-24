package com.example.myagent.session;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/chat/sessions")
public class SessionController {

  private final SessionService sessionService;

  public SessionController(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @PostMapping
  public Mono<ChatSessionDto> createSession(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestBody(required = false) CreateSessionRequest request) {
    return Mono.fromCallable(
            () ->
                ChatSessionDto.fromEntity(
                    sessionService.createSession(
                        currentUser, request != null ? request.title() : null)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping
  public Mono<List<ChatSessionDto>> listSessions(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(
            () ->
                sessionService.listSessions(currentUser).stream()
                    .map(ChatSessionDto::fromEntity)
                    .toList())
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/{sessionId}")
  public Mono<ChatSessionDto> getSession(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable String sessionId) {
    return Mono.fromCallable(
            () -> ChatSessionDto.fromEntity(sessionService.requireOwnedSession(currentUser, sessionId)))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteSession(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable String sessionId) {
    return sessionService.deleteSession(currentUser, sessionId);
  }
}
