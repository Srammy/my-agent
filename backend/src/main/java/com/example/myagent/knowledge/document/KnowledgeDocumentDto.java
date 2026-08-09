package com.example.myagent.knowledge.document;

import java.time.LocalDateTime;

public record KnowledgeDocumentDto(
    String id,
    String originalFilename,
    String contentType,
    Long sizeBytes,
    KnowledgeDocumentStatus status,
    Integer parentCount,
    Integer childCount,
    String errorMessage,
    LocalDateTime createdAt) {

  public static KnowledgeDocumentDto fromEntity(KnowledgeDocumentEntity entity) {
    return new KnowledgeDocumentDto(
        entity.getId(),
        entity.getOriginalFilename(),
        entity.getContentType(),
        entity.getSizeBytes(),
        entity.getStatus(),
        entity.getParentCount(),
        entity.getChildCount(),
        entity.getErrorMessage(),
        entity.getCreatedAt());
  }
}
