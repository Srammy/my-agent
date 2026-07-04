package com.example.myagent.session;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessionService {

  private static final String DEFAULT_TITLE = "\u65b0\u4f1a\u8bdd";
  private static final int TITLE_MAX_LENGTH = 120;

  private final ChatSessionMapper chatSessionMapper;

  public SessionService(ChatSessionMapper chatSessionMapper) {
    this.chatSessionMapper = chatSessionMapper;
  }

  public ChatSessionEntity createSession(CurrentUser currentUser, String title) {
    LocalDateTime now = LocalDateTime.now();
    ChatSessionEntity session = new ChatSessionEntity();
    session.setId("s_" + UUID.randomUUID().toString().replace("-", ""));
    session.setUserId(currentUser.id());
    session.setTitle(normalizeTitle(title));
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

  public void deleteSession(CurrentUser currentUser, String sessionId) {
    int deleted = chatSessionMapper.deleteOwnedById(currentUser.id(), sessionId);
    if (deleted == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
    }
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
