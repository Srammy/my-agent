package com.example.myagent.permission;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.session.SessionService;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {

  private final PermissionModeMapper permissionModeMapper;
  private final SessionService sessionService;

  public PermissionService(PermissionModeMapper permissionModeMapper, SessionService sessionService) {
    this.permissionModeMapper = permissionModeMapper;
    this.sessionService = sessionService;
  }

  public PermissionModeDto getMode(CurrentUser currentUser, String sessionId) {
    sessionService.requireOwnedSession(currentUser, sessionId);
    PermissionModeEntity entity = permissionModeMapper.findBySessionId(sessionId);
    if (entity == null) {
      return new PermissionModeDto(PermissionMode.DEFAULT);
    }
    return new PermissionModeDto(PermissionMode.valueOf(entity.getMode()));
  }

  public PermissionModeDto setMode(CurrentUser currentUser, String sessionId, PermissionModeDto mode) {
    sessionService.requireOwnedSession(currentUser, sessionId);
    permissionModeMapper.upsert(sessionId, mode.mode().name());
    return mode;
  }
}
