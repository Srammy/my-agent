package com.example.myagent.knowledge.search;

public record KnowledgeSearchHit(
    String parentId,
    String documentId,
    String sourceFilename,
    Integer pageNumber,
    String content,
    double score) {}
