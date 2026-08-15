package com.example.myagent.session;

import java.time.LocalDateTime;

public record ChatSessionDto(
    String id, String title, SessionMode mode, LocalDateTime createdAt, LocalDateTime updatedAt) {

  public static ChatSessionDto fromEntity(ChatSessionEntity entity) {
    return new ChatSessionDto(
        entity.getId(),
        entity.getTitle(),
        entity.getMode() == null ? SessionMode.NORMAL : entity.getMode(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
