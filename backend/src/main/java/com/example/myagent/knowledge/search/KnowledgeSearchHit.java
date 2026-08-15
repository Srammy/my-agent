package com.example.myagent.knowledge.search;

public record KnowledgeSearchHit(
    String chunkId,
    String documentId,
    String sourceFilename,
    Integer pageNumber,
    String content,
    double score,
    Integer chunkIndex) {

  public KnowledgeSearchHit(
      String chunkId,
      String documentId,
      String sourceFilename,
      Integer pageNumber,
      String content,
      double score) {
    this(chunkId, documentId, sourceFilename, pageNumber, content, score, null);
  }

  public String parentId() {
    return chunkId;
  }
}
