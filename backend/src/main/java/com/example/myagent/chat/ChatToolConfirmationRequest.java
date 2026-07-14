package com.example.myagent.chat;

import com.example.myagent.permission.PermissionMode;
import java.util.List;

public record ChatToolConfirmationRequest(
    Long userId,
    String sessionId,
    PermissionMode permissionMode,
    List<ToolCallDecision> decisions) {}
