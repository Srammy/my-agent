package com.example.myagent.chat;

public record ChatAgentRequest(Long userId, String sessionId, String message) {}
