package com.example.myagent.knowledge.search;

import com.example.myagent.knowledge.chunk.KnowledgeChunk;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class KnowledgePgVectorService {

  private static final int EMBEDDING_BATCH_SIZE = 10;
  private final VectorStore vectorStore;
  private final JdbcTemplate jdbcTemplate;

  public KnowledgePgVectorService(
      @Qualifier("knowledgeVectorStore") VectorStore vectorStore,
      @Qualifier("knowledgePostgresqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.vectorStore = vectorStore;
    this.jdbcTemplate = jdbcTemplate;
  }

  public void deleteByUserAndDocument(Long userId, String documentId) {
    jdbcTemplate.update(
        "delete from vector_store where metadata ->> 'userId' = ? and metadata ->> 'documentId' = ?",
        String.valueOf(userId),
        documentId);
  }

  public void indexDocuments(List<KnowledgeDocumentContent.ChunkDocument> chunks) {
    if (chunks == null || chunks.isEmpty()) return;
    List<Document> documents = new ArrayList<>(chunks.size());
    for (KnowledgeDocumentContent.ChunkDocument chunk : chunks) {
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.putAll(chunk.metadata());
      metadata.put("chunkId", chunk.chunkId());
      metadata.put("chunkIndex", chunk.chunkIndex());
      documents.add(Document.builder()
          .id(vectorDocumentId(chunk.chunkId()))
          .text(chunk.text())
          .metadata(metadata)
          .build());
    }
    for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE) {
      int end = Math.min(start + EMBEDDING_BATCH_SIZE, documents.size());
      vectorStore.add(documents.subList(start, end));
    }
  }

  public List<KnowledgeVectorHit> search(
      Long userId, String question, int topK, java.util.Collection<String> documentIds) {
    if (userId == null || question == null || question.isBlank() || topK <= 0) return List.of();
    SearchRequest request = SearchRequest.builder()
        .query(question)
        .topK(topK)
        .filterExpression(new FilterExpressionBuilder().eq("userId", userId).build())
        .build();
    return vectorStore.similaritySearch(request).stream()
        .filter(document -> documentIds == null || documentIds.isEmpty()
            || documentIds.contains(String.valueOf(document.getMetadata().get("documentId"))))
        .map(document -> new KnowledgeVectorHit(
            stringValue(document.getMetadata(), "chunkId"),
            stringValue(document.getMetadata(), "documentId"),
            integerValue(document.getMetadata(), "chunkIndex"),
            integerValue(document.getMetadata(), "pageNumber"),
            stringValue(document.getMetadata(), "sourceFilename"),
            document.getText()))
        .toList();
  }

  private static String vectorDocumentId(String chunkId) {
    return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String stringValue(Map<String, Object> metadata, String key) {
    Object value = metadata == null ? null : metadata.get(key);
    return value == null ? null : value.toString();
  }

  private static Integer integerValue(Map<String, Object> metadata, String key) {
    Object value = metadata == null ? null : metadata.get(key);
    if (value == null) return null;
    return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
  }

}
