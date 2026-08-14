package com.example.myagent.knowledge.etl;

import java.util.List;
import java.util.Map;

public record KnowledgeDocumentContent(
    String documentId,
    Long userId,
    String sourceFilename,
    String contentType,
    List<ChunkDocument> chunks) {

  public record ChunkDocument(
      String chunkId,
      int chunkIndex,
      Integer pageNumber,
      String text,
      Map<String, Object> metadata) {}
}
