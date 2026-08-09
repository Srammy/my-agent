package com.example.myagent.knowledge;

import com.example.myagent.knowledge.search.KnowledgeElasticsearchIndexManager;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentCleanupService {

  private final KnowledgeElasticsearchIndexManager indexManager;

  public KnowledgeDocumentCleanupService(KnowledgeElasticsearchIndexManager indexManager) {
    this.indexManager = indexManager;
  }

  public void cleanup(Long userId, String documentId) {
    indexManager.deleteByDocument(userId, documentId);
  }
}
