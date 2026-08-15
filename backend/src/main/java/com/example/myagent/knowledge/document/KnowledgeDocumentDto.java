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
    Integer chunkCount,
    String errorMessage,
    LocalDateTime createdAt) {

  public KnowledgeDocumentDto(
      String id,
      String originalFilename,
      String contentType,
      Long sizeBytes,
      KnowledgeDocumentStatus status,
      Integer parentCount,
      Integer childCount,
      String errorMessage,
      LocalDateTime createdAt) {
    this(id, originalFilename, contentType, sizeBytes, status, parentCount, childCount, 0, errorMessage, createdAt);
  }

  public static KnowledgeDocumentDto fromEntity(KnowledgeDocumentEntity entity) {
    return new KnowledgeDocumentDto(
        entity.getId(),
        entity.getOriginalFilename(),
        entity.getContentType(),
        entity.getSizeBytes(),
        entity.getStatus(),
        entity.getParentCount(),
        entity.getChildCount(),
        entity.getChunkCount() == null ? 0 : entity.getChunkCount(),
        entity.getErrorMessage(),
        entity.getCreatedAt());
  }
}
