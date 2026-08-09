package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.search.KnowledgeEmbeddingService;
import com.example.myagent.knowledge.search.KnowledgeSearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchServiceTest {

  @Test
  void sendsKeywordAndVectorBranchesThroughNativeRrfAndReturnsUserScopedParent() throws Exception {
    ElasticsearchClient client = mock(ElasticsearchClient.class);
    KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
    when(embeddingService.embed("deadline")).thenReturn(new float[] {0.1f, 0.2f});
    when(client.search(any(SearchRequest.class), eq(Map.class)))
        .thenReturn(
            SearchResponse.of(
                response ->
                    response.took(1).timedOut(false).shards(shards -> shards.total(1).successful(1).failed(0)).hits(
                        hits ->
                            hits.hits(
                                hit ->
                                    hit.id("doc-1_p_0_c_0")
                                        .score(0.8)
                                        .source(Map.of("parentId", "doc-1_p_0"))))))
        .thenReturn(
            SearchResponse.of(
                response ->
                    response.took(1).timedOut(false).shards(shards -> shards.total(1).successful(1).failed(0)).hits(
                        hits ->
                            hits.hits(
                                hit ->
                                    hit.id("doc-1_p_0")
                                        .source(
                                            Map.of(
                                                "documentId", "doc-1",
                                                "sourceFilename", "release.pdf",
                                                "pageNumber", 2,
                                                "content", "release deadline details"))))));

    KnowledgeSearchService service =
        new KnowledgeSearchService(client, properties(), embeddingService);

    var results = service.search(7L, "deadline", 5, List.of("doc-1"));

    assertThat(results).singleElement().satisfies(hit -> {
      assertThat(hit.parentId()).isEqualTo("doc-1_p_0");
      assertThat(hit.documentId()).isEqualTo("doc-1");
      assertThat(hit.pageNumber()).isEqualTo(2);
      assertThat(hit.content()).contains("deadline");
    });
    var requestCaptor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
    verify(client, org.mockito.Mockito.times(2)).search(requestCaptor.capture(), eq(Map.class));
    SearchRequest childRequest = requestCaptor.getAllValues().get(0);
    assertThat(childRequest.rank()).isNotNull();
    assertThat(childRequest.knn()).hasSize(1);
    assertThat(childRequest.query()).isNotNull();
  }

  @Test
  void returnsNoAnswerContextWhenRrfHasNoHits() throws Exception {
    ElasticsearchClient client = mock(ElasticsearchClient.class);
    KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
    when(embeddingService.embed("unknown")).thenReturn(new float[] {0.1f, 0.2f});
    when(client.search(any(SearchRequest.class), eq(Map.class)))
        .thenReturn(
            SearchResponse.of(
                response ->
                    response.took(1).timedOut(false)
                        .shards(shards -> shards.total(1).successful(1).failed(0))
                        .hits(hits -> hits.hits(List.of()))));

    KnowledgeSearchService service =
        new KnowledgeSearchService(client, properties(), embeddingService);

    assertThat(service.search(7L, "unknown")).isEmpty();
    verify(client).search(any(SearchRequest.class), eq(Map.class));
  }

  private static KnowledgeProperties properties() {
    return new KnowledgeProperties(
        new KnowledgeProperties.Embedding("test", "embedding-test", 2, "KEY"),
        new KnowledgeProperties.Multimodal("test", "vision-test", "KEY"),
        new KnowledgeProperties.Elasticsearch(
            "http://localhost:9200", "elastic", "", "parents", "children"),
        new KnowledgeProperties.Kafka("topic", "group", "localhost:9092"),
        new KnowledgeProperties.Storage("target/storage"));
  }
}
