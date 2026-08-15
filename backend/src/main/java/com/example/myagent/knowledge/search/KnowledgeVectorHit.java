package com.example.myagent.knowledge.search;

public record KnowledgeVectorHit(
    String chunkId,
    String documentId,
    Integer chunkIndex,
    Integer pageNumber,
    String sourceFilename,
    String content) {}
