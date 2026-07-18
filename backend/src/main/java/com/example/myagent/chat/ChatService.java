package com.example.myagent.chat;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.SessionExecutionCoordinator;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationDecision;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

  private final SessionService sessionService;
  private final ChatAgentGateway chatAgentGateway;
  private final PermissionService permissionService;
  private final ToolConfirmationService toolConfirmationService;
  private final SessionExecutionCoordinator sessionExecutionCoordinator;

  public ChatService(
      SessionService sessionService,
      ChatAgentGateway chatAgentGateway,
      PermissionService permissionService,
      ToolConfirmationService toolConfirmationService,
      SessionExecutionCoordinator sessionExecutionCoordinator) {
    this.sessionService = sessionService;
    this.chatAgentGateway = chatAgentGateway;
    this.permissionService = permissionService;
    this.toolConfirmationService = toolConfirmationService;
    this.sessionExecutionCoordinator = sessionExecutionCoordinator;
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
        .flatMapMany(request -> sessionExecutionCoordinator.track(
            currentUser.id(), sessionId, () -> chatAgentGateway.stream(request)));
  }

  public Flux<StreamEventDto> confirm(
      CurrentUser currentUser, String sessionId, String confirmationId,
      List<ToolConfirmationDecisionRequest> decisions) {
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
              Map<String, Boolean> byId = new HashMap<>();
              boolean invalid = decisions == null || decisions.stream().anyMatch(
                  decision -> decision == null || decision.toolCallId() == null
                      || byId.put(decision.toolCallId(), decision.confirmed()) != null);
              invalid = invalid
                  || byId.size() != claim.record().toolCalls().size()
                  || claim.record().toolCalls().stream().anyMatch(tool -> !byId.containsKey(tool.id()));
              if (invalid) {
                return toolConfirmationService
                    .release(confirmationId, claim.processingToken())
                    .then(Mono.<StreamEventDto>error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Decisions must match every pending tool call exactly")))
                    .flux();
              }
              List<ToolCallDecision> trustedDecisions = claim.record().toolCalls().stream()
                  .map(tool -> new ToolCallDecision(tool, byId.get(tool.id())))
                  .toList();
              List<ToolConfirmationDecision> persisted = trustedDecisions.stream()
                  .map(decision -> new ToolConfirmationDecision(
                      decision.toolCall().id(), decision.confirmed()))
                  .toList();
              ChatToolConfirmationRequest request = new ChatToolConfirmationRequest(
                  currentUser.id(), sessionId, context.permissionMode(),
                  trustedDecisions);
              return toolConfirmationService
                  .consume(confirmationId, claim.processingToken(), persisted)
                  .thenMany(
                      Flux.defer(
                          () -> sessionExecutionCoordinator.track(
                              currentUser.id(),
                              sessionId,
                              () -> chatAgentGateway
                                  .confirm(request)
                                  .onErrorResume(
                                      error -> Flux.just(StreamEventDto.error(errorMessage(error)))))));
            });
  }

  private static String errorMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private record ConfirmationContext(
      com.example.myagent.permission.PermissionMode permissionMode, ToolConfirmationClaim claim) {}
}
