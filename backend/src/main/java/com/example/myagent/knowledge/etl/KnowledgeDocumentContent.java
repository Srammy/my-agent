package com.example.myagent.knowledge.etl;

import java.util.List;
import java.util.Map;

public record KnowledgeDocumentContent(
    String documentId,
    Long userId,
    String sourceFilename,
    String contentType,
    List<ParentDocument> parents) {

  public record ParentDocument(
      String parentId,
      int parentIndex,
      Integer pageNumber,
      String text,
      Map<String, Object> metadata,
      List<ChildDocument> children) {}

  public record ChildDocument(
      String childId,
      String parentId,
      int childIndex,
      Integer pageNumber,
      String text,
      Map<String, Object> metadata) {}
}
