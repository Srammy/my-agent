package com.example.myagent.knowledge.chunk;

import java.time.LocalDateTime;

public record KnowledgeChunk(
    Long id,
    Long userId,
    String documentId,
    String chunkId,
    Integer chunkIndex,
    String chunkText,
    String chunkSummary,
    Integer charStart,
    Integer charEnd,
    String metadataJson,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {}
