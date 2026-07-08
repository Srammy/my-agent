package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.SessionService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

  private final SessionService sessionService;
  private final ChatAgentGateway chatAgentGateway;
  private final PermissionService permissionService;

  public ChatService(
      SessionService sessionService,
      ChatAgentGateway chatAgentGateway,
      PermissionService permissionService) {
    this.sessionService = sessionService;
    this.chatAgentGateway = chatAgentGateway;
    this.permissionService = permissionService;
  }

  public Flux<StreamEventDto> stream(CurrentUser currentUser, String sessionId, String message) {
    return Mono.fromCallable(
            () -> {
              sessionService.requireOwnedSession(currentUser, sessionId);
              return new ChatAgentRequest(
                  currentUser.id(),
                  sessionId,
                  message,
                  permissionService.getModeForOwnedSession(sessionId));
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(chatAgentGateway::stream);
  }
}
