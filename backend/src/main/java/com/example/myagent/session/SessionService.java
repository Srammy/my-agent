package com.example.myagent.session;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.chat.ChatMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class SessionService {

  private static final String DEFAULT_TITLE = "\u65b0\u4f1a\u8bdd";
  private static final int TITLE_MAX_LENGTH = 120;

  private final ChatSessionMapper chatSessionMapper;
  private final ChatMessageMapper chatMessageMapper;
  private final SessionExecutionCoordinator sessionExecutionCoordinator;

  public SessionService(
      ChatSessionMapper chatSessionMapper,
      SessionExecutionCoordinator sessionExecutionCoordinator) {
    this(chatSessionMapper, null, sessionExecutionCoordinator);
  }

  @Autowired
  public SessionService(
      ChatSessionMapper chatSessionMapper,
      ChatMessageMapper chatMessageMapper,
      SessionExecutionCoordinator sessionExecutionCoordinator) {
    this.chatSessionMapper = chatSessionMapper;
    this.chatMessageMapper = chatMessageMapper;
    this.sessionExecutionCoordinator = sessionExecutionCoordinator;
  }

  public ChatSessionEntity createSession(CurrentUser currentUser, String title) {
    return createSession(currentUser, title, SessionMode.NORMAL);
  }

  public ChatSessionEntity createSession(
      CurrentUser currentUser, String title, SessionMode requestedMode) {
    LocalDateTime now = LocalDateTime.now();
    ChatSessionEntity session = new ChatSessionEntity();
    session.setId("s_" + UUID.randomUUID().toString().replace("-", ""));
    session.setUserId(currentUser.id());
    session.setTitle(normalizeTitle(title));
    session.setMode(requestedMode == null ? SessionMode.NORMAL : requestedMode);
    session.setCreatedAt(now);
    session.setUpdatedAt(now);
    chatSessionMapper.insert(session);
    return session;
  }

  public List<ChatSessionEntity> listSessions(CurrentUser currentUser) {
    return chatSessionMapper.findByUserId(currentUser.id());
  }

  public ChatSessionEntity requireOwnedSession(CurrentUser currentUser, String sessionId) {
    ChatSessionEntity session = chatSessionMapper.findOwnedById(currentUser.id(), sessionId);
    if (session == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }
    return session;
  }

  public ChatSessionEntity renameSession(CurrentUser currentUser, String sessionId, String title) {
    requireOwnedSession(currentUser, sessionId);
    int updated =
        chatSessionMapper.updateTitleOwnedById(
            currentUser.id(), sessionId, normalizeTitle(title), LocalDateTime.now());
    if (updated == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }
    return requireOwnedSession(currentUser, sessionId);
  }

  public Mono<Void> deleteSession(CurrentUser currentUser, String sessionId) {
    return Mono.fromCallable(() -> requireOwnedSession(currentUser, sessionId))
        .subscribeOn(Schedulers.boundedElastic())
        .then(Mono.defer(
            () -> sessionExecutionCoordinator.cancelAndAwait(currentUser.id(), sessionId)))
        .then(Mono.fromCallable(
                () -> {
                  if (chatMessageMapper != null) {
                    chatMessageMapper.deleteByOwnedSession(currentUser.id(), sessionId);
                  }
                  int deleted = chatSessionMapper.deleteOwnedById(currentUser.id(), sessionId);
                  if (deleted == 0) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
                  }
                  return deleted;
                })
            .subscribeOn(Schedulers.boundedElastic()))
        .then();
  }

  private String normalizeTitle(String title) {
    if (!StringUtils.hasText(title)) {
      return DEFAULT_TITLE;
    }

    String normalized = title.trim();
    if (normalized.length() <= TITLE_MAX_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, TITLE_MAX_LENGTH);
  }
}
