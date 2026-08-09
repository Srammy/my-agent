package com.example.myagent.knowledge.search;

public record KnowledgeParentDocument(
    String id,
    Long userId,
    String documentId,
    String sourceFilename,
    String contentType,
    int parentIndex,
    Integer pageNumber,
    String content,
    String status) {}
