package com.example.myagent.knowledge.search;

import java.util.List;

public record KnowledgeChildDocument(
    String id,
    Long userId,
    String documentId,
    String parentId,
    String sourceFilename,
    String contentType,
    int childIndex,
    Integer pageNumber,
    String content,
    List<Float> embedding,
    String status) {}
