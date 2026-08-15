package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.example.myagent.knowledge.search.KnowledgeChunkIndexDocument;
import com.example.myagent.knowledge.search.KnowledgeElasticsearchIndexManager;
import com.example.myagent.knowledge.search.KnowledgeIndexService;
import com.example.myagent.knowledge.search.KnowledgePgVectorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeIndexServiceTest {

  @Test
  void writesFlatChunksToPostgresVectorStoreAndKeywordIndex() {
    KnowledgeElasticsearchIndexManager indexManager = mock(KnowledgeElasticsearchIndexManager.class);
    KnowledgeChunkRepository chunkRepository = mock(KnowledgeChunkRepository.class);
    KnowledgePgVectorService vectorService = mock(KnowledgePgVectorService.class);
    KnowledgeIndexService service = new KnowledgeIndexService(
        indexManager, chunkRepository, vectorService, new ObjectMapper());

    service.index(content());

    verify(chunkRepository).insertBatch(any());
    verify(vectorService).indexDocuments(any());
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(indexManager).writeChunks(captor.capture());
    List<KnowledgeChunkIndexDocument> indexed = captor.getValue();
    assertThat(indexed).singleElement().satisfies(chunk -> {
      assertThat(chunk.chunkId()).isEqualTo("doc-1:0");
      assertThat(chunk.userId()).isEqualTo(7L);
      assertThat(chunk.documentId()).isEqualTo("doc-1");
    });
  }

  @Test
  void cleansAllStoresWhenKeywordIndexWriteFails() {
    KnowledgeElasticsearchIndexManager indexManager = mock(KnowledgeElasticsearchIndexManager.class);
    KnowledgeChunkRepository chunkRepository = mock(KnowledgeChunkRepository.class);
    KnowledgePgVectorService vectorService = mock(KnowledgePgVectorService.class);
    doThrow(new IllegalStateException("keyword failed")).when(indexManager).writeChunks(any());
    KnowledgeIndexService service = new KnowledgeIndexService(
        indexManager, chunkRepository, vectorService, new ObjectMapper());

    assertThatThrownBy(() -> service.index(content())).hasMessage("keyword failed");
    verify(indexManager, org.mockito.Mockito.atLeast(2)).deleteByDocument(7L, "doc-1");
    verify(vectorService, org.mockito.Mockito.atLeast(2)).deleteByUserAndDocument(7L, "doc-1");
    verify(chunkRepository, org.mockito.Mockito.atLeast(2)).deleteByUserAndDocument(7L, "doc-1");
  }

  private static KnowledgeDocumentContent content() {
    return new KnowledgeDocumentContent(
        "doc-1", 7L, "source.pdf", "application/pdf",
        List.of(new KnowledgeDocumentContent.ChunkDocument(
            "doc-1:0", 0, 1, "content", Map.of("userId", 7L, "documentId", "doc-1"))));
  }
}
