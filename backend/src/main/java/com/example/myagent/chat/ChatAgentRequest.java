package com.example.myagent.chat;

import com.example.myagent.permission.PermissionMode;
import java.util.List;

public record ChatAgentRequest(
    Long userId,
    String sessionId,
    String message,
    PermissionMode permissionMode) {

  @Deprecated(forRemoval = true)
  public static final String MATERIALIZED_SKILL_ROOTS_CONTEXT_KEY = "materializedSkillRoots";
  public static final String PERMISSION_MODE_CONTEXT_KEY = "permissionMode";

  public ChatAgentRequest {
    permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
  }

  public ChatAgentRequest(
      Long userId,
      String sessionId,
      String message,
      List<String> materializedSkillRoots,
      PermissionMode permissionMode) {
    this(userId, sessionId, message, permissionMode);
  }

  @Deprecated(forRemoval = true)
  public List<String> materializedSkillRoots() {
    return List.of();
  }
}
