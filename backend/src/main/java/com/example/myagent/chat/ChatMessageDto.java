package com.example.myagent.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ChatMessageDto(
    String id,
    String role,
    String content,
    List<Map<String, Object>> events,
    boolean loading,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
