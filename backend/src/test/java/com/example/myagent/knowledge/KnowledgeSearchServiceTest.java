package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myagent.config.KnowledgeRetrievalProperties;
import com.example.myagent.knowledge.chunk.KnowledgeChunk;
import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import com.example.myagent.knowledge.search.KnowledgeElasticsearchIndexManager;
import com.example.myagent.knowledge.search.KnowledgeKeywordHit;
import com.example.myagent.knowledge.search.KnowledgePgVectorService;
import com.example.myagent.knowledge.search.KnowledgeSearchHit;
import com.example.myagent.knowledge.search.KnowledgeSearchService;
import com.example.myagent.knowledge.search.KnowledgeVectorHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeSearchServiceTest {

  @Test
  void mergesKeywordAndVectorRanksAndExpandsNeighborChunk() {
    KnowledgeElasticsearchIndexManager elasticsearch = mock(KnowledgeElasticsearchIndexManager.class);
    KnowledgePgVectorService vectorStore = mock(KnowledgePgVectorService.class);
    KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
    when(elasticsearch.searchKeywords(7L, "deadline", 50, List.of()))
        .thenReturn(List.of(new KnowledgeKeywordHit("doc-1:1", "doc-1", 1, 2, "guide.pdf", "deadline")));
    when(vectorStore.search(7L, "deadline", 50, List.of()))
        .thenReturn(List.of(new KnowledgeVectorHit("doc-1:1", "doc-1", 1, 2, "guide.pdf", "deadline")));
    when(repository.findByUserAndDocument(7L, "doc-1")).thenReturn(List.of(
        chunk("doc-1:0", 0, "before"), chunk("doc-1:1", 1, "deadline"), chunk("doc-1:2", 2, "after")));

    KnowledgeSearchService service = new KnowledgeSearchService(
        elasticsearch, vectorStore, repository,
        new KnowledgeRetrievalProperties(0.02, 8), new ObjectMapper());

    List<KnowledgeSearchHit> hits = service.search(7L, "deadline");

    assertThat(hits).extracting(KnowledgeSearchHit::chunkId)
        .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    assertThat(hits.get(1).score()).isGreaterThan(0.02);
  }

  @Test
  void rejectsLowRrfResults() {
    KnowledgeElasticsearchIndexManager elasticsearch = mock(KnowledgeElasticsearchIndexManager.class);
    KnowledgePgVectorService vectorStore = mock(KnowledgePgVectorService.class);
    KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
    when(elasticsearch.searchKeywords(7L, "unknown", 50, List.of()))
        .thenReturn(List.of(new KnowledgeKeywordHit("doc-1:0", "doc-1", 0, 1, "a.pdf", "x")));
    when(vectorStore.search(7L, "unknown", 50, List.of())).thenReturn(List.of());

    KnowledgeSearchService service = new KnowledgeSearchService(
        elasticsearch, vectorStore, repository,
        new KnowledgeRetrievalProperties(0.02, 8), new ObjectMapper());

    assertThat(service.search(7L, "unknown")).isEmpty();
  }

  private static KnowledgeChunk chunk(String id, int index, String text) {
    return new KnowledgeChunk(null, 7L, "doc-1", id, index, text, null, null, null,
        "{\"sourceFilename\":\"guide.pdf\",\"pageNumber\":2}", null, null);
  }
}
