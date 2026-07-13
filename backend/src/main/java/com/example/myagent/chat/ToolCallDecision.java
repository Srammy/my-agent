package com.example.myagent.chat;

import com.example.myagent.toolconfirmation.ToolCallSnapshot;

public record ToolCallDecision(ToolCallSnapshot toolCall, boolean confirmed) {}
