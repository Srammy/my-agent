package com.example.myagent.knowledge.search;

public record KnowledgeKeywordHit(
    String chunkId,
    String documentId,
    Integer chunkIndex,
    Integer pageNumber,
    String sourceFilename,
    String content) {}
