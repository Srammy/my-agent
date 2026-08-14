package com.example.myagent.knowledge.search;

public record KnowledgeChunkIndexDocument(
    String chunkId,
    Long userId,
    String documentId,
    int chunkIndex,
    Integer pageNumber,
    String sourceFilename,
    String content,
    String status) {}
