package com.example.myagent.knowledge.search;

import com.example.myagent.knowledge.chunk.KnowledgeChunk;
import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeIndexService {

  private final KnowledgeElasticsearchIndexManager indexManager;
  private final KnowledgeChunkRepository chunkRepository;
  private final KnowledgePgVectorService vectorService;
  private final ObjectMapper objectMapper;

  public KnowledgeIndexService(
      KnowledgeElasticsearchIndexManager indexManager,
      KnowledgeChunkRepository chunkRepository,
      KnowledgePgVectorService vectorService,
      ObjectMapper objectMapper) {
    this.indexManager = indexManager;
    this.chunkRepository = chunkRepository;
    this.vectorService = vectorService;
    this.objectMapper = objectMapper;
  }

  public void index(KnowledgeDocumentContent content) {
    List<KnowledgeChunk> chunks = content.chunks().stream().map(chunk -> toChunk(content, chunk)).toList();
    List<KnowledgeChunkIndexDocument> searchDocuments = content.chunks().stream()
        .map(chunk -> new KnowledgeChunkIndexDocument(
            chunk.chunkId(), content.userId(), content.documentId(), chunk.chunkIndex(),
            chunk.pageNumber(), content.sourceFilename(), chunk.text(), "READY"))
        .toList();
    try {
      indexManager.deleteByDocument(content.userId(), content.documentId());
      vectorService.deleteByUserAndDocument(content.userId(), content.documentId());
      chunkRepository.deleteByUserAndDocument(content.userId(), content.documentId());
      chunkRepository.insertBatch(chunks);
      vectorService.indexDocuments(content.chunks());
      indexManager.writeChunks(searchDocuments);
    } catch (RuntimeException error) {
      cleanup(content.userId(), content.documentId());
      throw error;
    }
  }

  private KnowledgeChunk toChunk(
      KnowledgeDocumentContent content, KnowledgeDocumentContent.ChunkDocument chunk) {
    try {
      Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
      return new KnowledgeChunk(
          null,
          content.userId(),
          content.documentId(),
          chunk.chunkId(),
          chunk.chunkIndex(),
          chunk.text(),
          null,
          integerValue(metadata, "charStart"),
          integerValue(metadata, "charEnd"),
          objectMapper.writeValueAsString(metadata),
          LocalDateTime.now(),
          LocalDateTime.now());
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Knowledge chunk metadata serialization failed", error);
    }
  }

  private void cleanup(Long userId, String documentId) {
    try { indexManager.deleteByDocument(userId, documentId); } catch (RuntimeException ignored) { }
    try { vectorService.deleteByUserAndDocument(userId, documentId); } catch (RuntimeException ignored) { }
    try { chunkRepository.deleteByUserAndDocument(userId, documentId); } catch (RuntimeException ignored) { }
  }

  private static Integer integerValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value == null) return null;
    return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
  }
}
