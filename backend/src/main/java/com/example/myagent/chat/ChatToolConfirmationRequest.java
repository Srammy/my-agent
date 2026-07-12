package com.example.myagent.chat;

import com.example.myagent.permission.PermissionMode;
import com.example.myagent.toolconfirmation.ToolCallSnapshot;

public record ChatToolConfirmationRequest(
    Long userId,
    String sessionId,
    PermissionMode permissionMode,
    String replyId,
    ToolCallSnapshot toolCall,
    boolean confirmed) {}
