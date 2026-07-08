package com.example.myagent.chat;

import com.example.myagent.permission.PermissionMode;

public record ChatAgentRequest(
    Long userId,
    String sessionId,
    String message,
    PermissionMode permissionMode) {

  public static final String PERMISSION_MODE_CONTEXT_KEY = "permissionMode";

  public ChatAgentRequest {
    permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
  }
}
