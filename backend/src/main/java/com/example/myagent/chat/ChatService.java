package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

  private final SessionService sessionService;
  private final ChatAgentGateway chatAgentGateway;
  private final PermissionService permissionService;
  private final ToolConfirmationService toolConfirmationService;

  public ChatService(
      SessionService sessionService,
      ChatAgentGateway chatAgentGateway,
      PermissionService permissionService,
      ToolConfirmationService toolConfirmationService) {
    this.sessionService = sessionService;
    this.chatAgentGateway = chatAgentGateway;
    this.permissionService = permissionService;
    this.toolConfirmationService = toolConfirmationService;
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

  public Flux<StreamEventDto> confirm(
      CurrentUser currentUser, String sessionId, String confirmationId, boolean confirmed) {
    return Mono.fromCallable(
            () -> {
              sessionService.requireOwnedSession(currentUser, sessionId);
              return permissionService.getModeForOwnedSession(sessionId);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(
            permissionMode ->
                toolConfirmationService
                    .claim(currentUser.id(), sessionId, confirmationId)
                    .map(claim -> new ConfirmationContext(permissionMode, claim)))
        .flatMapMany(
            context -> {
              ToolConfirmationClaim claim = context.claim();
              ChatToolConfirmationRequest request =
                  new ChatToolConfirmationRequest(
                      currentUser.id(),
                      sessionId,
                      context.permissionMode(),
                      claim.record().replyId(),
                      claim.record().toolCall(),
                      confirmed);
              return chatAgentGateway
                  .confirm(request)
                  .materialize()
                  .concatMap(
                      signal -> {
                        if (signal.isOnNext()) {
                          return Mono.just(signal.get());
                        }
                        if (signal.isOnError()) {
                          return toolConfirmationService
                              .release(confirmationId, claim.processingToken())
                              .onErrorResume(ignored -> Mono.empty())
                              .thenReturn(StreamEventDto.error(errorMessage(signal.getThrowable())));
                        }
                        return toolConfirmationService
                            .complete(confirmationId, claim.processingToken(), confirmed)
                            .then(Mono.<StreamEventDto>empty())
                            .onErrorResume(
                                error -> Mono.just(StreamEventDto.error(errorMessage(error))));
                      });
            });
  }

  private static String errorMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private record ConfirmationContext(
      com.example.myagent.permission.PermissionMode permissionMode, ToolConfirmationClaim claim) {}
}
