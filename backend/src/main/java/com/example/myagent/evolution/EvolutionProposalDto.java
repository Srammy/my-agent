package com.example.myagent.evolution;

import java.time.LocalDateTime;

public record EvolutionProposalDto(
    Long id,
    String sessionId,
    EvolutionProposalType type,
    String title,
    String summary,
    String content,
    EvolutionProposalStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime appliedAt) {

  static EvolutionProposalDto fromEntity(EvolutionProposalEntity entity) {
    return new EvolutionProposalDto(
        entity.getId(),
        entity.getSessionId(),
        entity.getType(),
        entity.getTitle(),
        entity.getSummary(),
        entity.getContent(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getAppliedAt());
  }
}
