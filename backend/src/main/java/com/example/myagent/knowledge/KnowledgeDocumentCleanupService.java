package com.example.myagent.knowledge;

import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import com.example.myagent.knowledge.search.KnowledgeElasticsearchIndexManager;
import com.example.myagent.knowledge.search.KnowledgePgVectorService;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentCleanupService {

  private final KnowledgeElasticsearchIndexManager indexManager;
  private final KnowledgeChunkRepository chunkRepository;
  private final KnowledgePgVectorService vectorService;

  public KnowledgeDocumentCleanupService(
      KnowledgeElasticsearchIndexManager indexManager,
      KnowledgeChunkRepository chunkRepository,
      KnowledgePgVectorService vectorService) {
    this.indexManager = indexManager;
    this.chunkRepository = chunkRepository;
    this.vectorService = vectorService;
  }

  public void cleanup(Long userId, String documentId) {
    indexManager.deleteByDocument(userId, documentId);
    vectorService.deleteByUserAndDocument(userId, documentId);
    chunkRepository.deleteByUserAndDocument(userId, documentId);
  }
}
