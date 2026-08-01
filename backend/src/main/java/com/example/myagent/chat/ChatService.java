package com.example.myagent.chat;

import com.example.myagent.agent.AgentExecution;
import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.SessionExecutionCoordinator;
import com.example.myagent.session.SessionService;
import com.example.myagent.toolconfirmation.ToolConfirmationClaim;
import com.example.myagent.toolconfirmation.ToolConfirmationDecision;
import com.example.myagent.toolconfirmation.ToolConfirmationService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
  private final ChatMessageService chatMessageService;

  public ChatService(
      SessionService sessionService,
      ChatAgentGateway chatAgentGateway,
      PermissionService permissionService,
      ToolConfirmationService toolConfirmationService,
      SessionExecutionCoordinator sessionExecutionCoordinator) {
    this(
        sessionService,
        chatAgentGateway,
        permissionService,
        toolConfirmationService,
        sessionExecutionCoordinator,
        null);
  }

  @Autowired
  public ChatService(
      SessionService sessionService,
      ChatAgentGateway chatAgentGateway,
      PermissionService permissionService,
      ToolConfirmationService toolConfirmationService,
      SessionExecutionCoordinator sessionExecutionCoordinator,
      ChatMessageService chatMessageService) {
    this.sessionService = sessionService;
    this.chatAgentGateway = chatAgentGateway;
    this.permissionService = permissionService;
    this.toolConfirmationService = toolConfirmationService;
    this.sessionExecutionCoordinator = sessionExecutionCoordinator;
    this.chatMessageService = chatMessageService;
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
        .flatMapMany(
            request ->
                Flux.defer(
                    () -> {
                      AgentExecution<StreamEventDto> execution =
                          chatAgentGateway.streamExecution(request);
                      Flux<StreamEventDto> tracked =
                          sessionExecutionCoordinator.track(
                              currentUser.id(), sessionId, execution::events, execution::completion);
                      if (chatMessageService == null) {
                        return tracked;
                      }
                      chatMessageService.createMessage(
                          currentUser.id(),
                          sessionId,
                          "user",
                          message == null ? "" : message.trim(),
                          false);
                      ChatMessageEntity assistantMessage =
                          chatMessageService.createMessage(
                              currentUser.id(), sessionId, "assistant", "", true);
                      StringBuilder assistantContent = new StringBuilder();
                      List<Map<String, Object>> assistantEvents = new ArrayList<>();
                      AtomicBoolean finalized = new AtomicBoolean();
                      return tracked.doOnNext(
                              event ->
                                  persistAssistantEvent(
                                      currentUser.id(),
                                      assistantMessage.getId(),
                                      assistantContent,
                                      assistantEvents,
                                      finalized,
                                      event))
                          .doOnError(
                              error -> {
                                assistantEvents.add(
                                    chatMessageService.toPersistedEvent(
                                        StreamEventDto.error(errorMessage(error))));
                                finalized.set(true);
                                chatMessageService.updateAssistant(
                                    currentUser.id(),
                                    assistantMessage.getId(),
                                    assistantContent.toString(),
                                    assistantEvents,
                                    false);
                              })
                          .doFinally(
                              ignored -> {
                                if (!finalized.get()) {
                                  chatMessageService.updateAssistant(
                                      currentUser.id(),
                                      assistantMessage.getId(),
                                      assistantContent.toString(),
                                      assistantEvents,
                                      false);
                                }
                              });
                    }));
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
              AtomicBoolean consumed = new AtomicBoolean();
              Flux<StreamEventDto> resumed = Flux.defer(() -> {
                ChatMessageEntity assistantMessage =
                    chatMessageService == null
                        ? null
                        : chatMessageService.latestAssistant(currentUser.id(), sessionId);
                StringBuilder assistantContent = new StringBuilder(
                    assistantMessage == null ? "" : assistantMessage.getContent());
                List<Map<String, Object>> assistantEvents = assistantMessage == null
                    ? new ArrayList<>()
                    : new ArrayList<>(chatMessageService.readEvents(assistantMessage));
                AtomicBoolean finalizedMessage = new AtomicBoolean();
                AgentExecution<StreamEventDto> execution =
                    chatAgentGateway.confirmExecution(request);
                return sessionExecutionCoordinator.track(
                    currentUser.id(),
                    sessionId,
                    () -> toolConfirmationService
                        .consume(confirmationId, claim.processingToken(), persisted)
                        .doOnSuccess(ignored -> consumed.set(true)),
                    () -> execution.events().onErrorResume(
                        error -> Flux.just(StreamEventDto.error(errorMessage(error)))),
                    execution::completion)
                    .doOnNext(event -> {
                      if (assistantMessage != null) {
                        persistAssistantEvent(
                            currentUser.id(),
                            assistantMessage.getId(),
                            assistantContent,
                            assistantEvents,
                            finalizedMessage,
                            event);
                      }
                    })
                    .doFinally(ignored -> {
                      if (assistantMessage != null && !finalizedMessage.get()) {
                        chatMessageService.updateAssistant(
                            currentUser.id(),
                            assistantMessage.getId(),
                            assistantContent.toString(),
                            assistantEvents,
                            false);
                      }
                    })
                    .concatWith(Flux.defer(() -> consumed.get()
                        ? Flux.empty()
                        : Flux.error(new IllegalStateException(
                            "Confirmation execution ended before its event source started"))));
              });
              return resumed
                  .onErrorResume(error -> recoverConfirmationFailure(
                      confirmationId, claim.processingToken(), consumed.get(), error))
                  .doOnCancel(() -> {
                    if (!consumed.get()) {
                      toolConfirmationService
                          .rollbackIfProcessing(confirmationId, claim.processingToken())
                          .subscribe(ignored -> {}, ignored -> {});
                    }
                  });
            });
  }

  private Flux<StreamEventDto> recoverConfirmationFailure(
      String confirmationId,
      String processingToken,
      boolean consumed,
      Throwable original) {
    if (consumed) {
      return Flux.error(isSessionCancelling(original)
          ? consumedSessionCancellation((ResponseStatusException) original)
          : consumedFailure(original));
    }
    return toolConfirmationService.rollbackIfProcessing(confirmationId, processingToken)
        .onErrorReturn(false)
        .flatMapMany(rolledBack -> {
          if (rolledBack && isSessionCancelling(original)) {
            return Flux.error(original);
          }
          return Flux.error(rolledBack
              ? retryableFailure(original)
              : consumedFailure(original));
        });
  }

  private static ResponseStatusException retryableFailure(Throwable cause) {
    return new ToolConfirmationRetryableException(cause);
  }

  private static ResponseStatusException consumedFailure(Throwable cause) {
    return new ToolConfirmationConsumedException(cause);
  }

  private static ResponseStatusException consumedSessionCancellation(
      ResponseStatusException cause) {
    return new ConsumedSessionCancellingException(cause);
  }

  private static boolean isSessionCancelling(Throwable error) {
    return error instanceof ResponseStatusException responseError
        && "SESSION_CANCELLING".equals(
            responseError.getHeaders().getFirst("X-Error-Code"));
  }

  private static String errorMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private void persistAssistantEvent(
      Long userId,
      String messageId,
      StringBuilder content,
      List<Map<String, Object>> events,
      AtomicBoolean finalized,
      StreamEventDto event) {
    if ("text_delta".equals(event.type())) {
      Object delta = event.jsonFields().get("delta");
      if (delta instanceof String text) {
        content.append(text);
      }
      chatMessageService.updateAssistant(userId, messageId, content.toString(), events, true);
      return;
    }

    if ("done".equals(event.type())) {
      finalized.set(true);
      chatMessageService.updateAssistant(userId, messageId, content.toString(), events, false);
      return;
    }

    if (isPersistedToolEvent(event)) {
      events.add(chatMessageService.toPersistedEvent(event));
      finalized.set("error".equals(event.type()));
      chatMessageService.updateAssistant(
          userId, messageId, content.toString(), events, !"error".equals(event.type()));
    }
  }

  private boolean isPersistedToolEvent(StreamEventDto event) {
    return "tool_call".equals(event.type())
        || "tool_result".equals(event.type())
        || "permission_required".equals(event.type())
        || "evolution_proposal".equals(event.type())
        || "error".equals(event.type());
  }

  private static final class ToolConfirmationRetryableException
      extends ResponseStatusException {
    private final HttpHeaders headers = new HttpHeaders();

    private ToolConfirmationRetryableException(Throwable cause) {
      super(HttpStatus.SERVICE_UNAVAILABLE, errorMessage(cause), cause);
      headers.set("X-Error-Code", "TOOL_CONFIRMATION_RETRYABLE");
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private static final class ToolConfirmationConsumedException
      extends ResponseStatusException {
    private final HttpHeaders headers = new HttpHeaders();

    private ToolConfirmationConsumedException(Throwable cause) {
      super(HttpStatus.CONFLICT, errorMessage(cause), cause);
      headers.set("X-Error-Code", "TOOL_CONFIRMATION_CONSUMED");
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private static final class ConsumedSessionCancellingException
      extends ResponseStatusException {
    private final HttpHeaders headers = new HttpHeaders();

    private ConsumedSessionCancellingException(ResponseStatusException cause) {
      super(cause.getStatusCode(), cause.getReason(), cause);
      headers.putAll(cause.getHeaders());
      headers.set("X-Tool-Confirmation-Consumed", "true");
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private record ConfirmationContext(
      com.example.myagent.permission.PermissionMode permissionMode, ToolConfirmationClaim claim) {}
}
