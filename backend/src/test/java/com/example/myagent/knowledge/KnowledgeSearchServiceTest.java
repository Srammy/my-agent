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
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.search.KnowledgeEmbeddingService;
import com.example.myagent.knowledge.search.KnowledgeSearchHit;
import com.example.myagent.knowledge.search.KnowledgeSearchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSearchServiceTest {

  @Test
  void mergesKeywordAndVectorBranchesWithApplicationRrf() throws Exception {
    ElasticsearchClient client = mock(ElasticsearchClient.class);
    KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
    when(embeddingService.embed("deadline")).thenReturn(new float[] {0.1f, 0.2f});
    when(client.search(any(SearchRequest.class), eq(Map.class)))
        .thenReturn(
            childResponse("child-1", "parent-1", "child-2", "parent-2"),
            childResponse("child-2", "parent-2", "child-3", "parent-3"),
            parentResponse());

    KnowledgeSearchService service =
        new KnowledgeSearchService(client, properties(), embeddingService);

    var results = service.search(7L, "deadline", 5, List.of("doc-1"));

    var requestCaptor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
    verify(client, org.mockito.Mockito.times(3)).search(requestCaptor.capture(), eq(Map.class));
    SearchRequest bm25Request = requestCaptor.getAllValues().get(0);
    SearchRequest knnRequest = requestCaptor.getAllValues().get(1);
    SearchRequest parentRequest = requestCaptor.getAllValues().get(2);
    assertThat(bm25Request.rank()).isNull();
    assertThat(bm25Request.knn()).isEmpty();
    assertThat(knnRequest.rank()).isNull();
    assertThat(knnRequest.knn()).isNotEmpty();
    var ownershipFilters = parentRequest.query().bool().filter().get(0).bool().filter();
    assertThat(ownershipFilters).anySatisfy(filter -> {
      assertThat(filter.isTerm()).isTrue();
      assertThat(filter.term().field()).isEqualTo("userId");
      assertThat(filter.term().value().longValue()).isEqualTo(7L);
    });
    assertThat(ownershipFilters).anySatisfy(filter -> {
      assertThat(filter.isTerm()).isTrue();
      assertThat(filter.term().field()).isEqualTo("status");
      assertThat(filter.term().value().stringValue()).isEqualTo("READY");
    });
    assertThat(ownershipFilters).anySatisfy(filter -> {
      assertThat(filter.isTerms()).isTrue();
      assertThat(filter.terms().field()).isEqualTo("documentId");
      assertThat(filter.terms().terms().value())
          .singleElement()
          .satisfies(value -> assertThat(value.stringValue()).isEqualTo("doc-1"));
    });
    assertThat(results).extracting(KnowledgeSearchHit::parentId)
        .containsExactly("parent-2", "parent-1", "parent-3");
  }

  @Test
  void returnsNoAnswerContextWhenBothBranchesHaveNoHits() throws Exception {
    ElasticsearchClient client = mock(ElasticsearchClient.class);
    KnowledgeEmbeddingService embeddingService = mock(KnowledgeEmbeddingService.class);
    when(embeddingService.embed("unknown")).thenReturn(new float[] {0.1f, 0.2f});
    when(client.search(any(SearchRequest.class), eq(Map.class)))
        .thenReturn(
            emptySearchResponse(),
            emptySearchResponse());

    KnowledgeSearchService service =
        new KnowledgeSearchService(client, properties(), embeddingService);

    assertThat(service.search(7L, "unknown")).isEmpty();
    verify(client, org.mockito.Mockito.times(2)).search(any(SearchRequest.class), eq(Map.class));
  }

  private static SearchResponse<Map> childResponse(
      String firstChildId, String firstParentId, String secondChildId, String secondParentId) {
    return SearchResponse.of(
        response ->
            response.took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(
                    hits ->
                        hits.hits(
                            List.of(
                                Hit.of(
                                    hit ->
                                        hit.index("children")
                                            .id(firstChildId)
                                            .score(0.8)
                                            .source(Map.of("parentId", firstParentId))),
                                Hit.of(
                                    hit ->
                                        hit.index("children")
                                            .id(secondChildId)
                                            .score(0.8)
                                            .source(Map.of("parentId", secondParentId)))))));
  }

  private static SearchResponse<Map> parentResponse() {
    return SearchResponse.of(
        response ->
            response.took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(
                    hits ->
                        hits.hits(
                            List.of(
                                Hit.of(
                                    hit ->
                                        hit.index("parents")
                                            .id("parent-1")
                                            .source(
                                                Map.of(
                                                    "documentId", "doc-1",
                                                    "sourceFilename", "release.pdf",
                                                    "pageNumber", 1,
                                                    "content", "release deadline details"))),
                                Hit.of(
                                    hit ->
                                        hit.index("parents")
                                            .id("parent-2")
                                            .source(
                                                Map.of(
                                                    "documentId", "doc-1",
                                                    "sourceFilename", "release.docx",
                                                    "pageNumber", 2,
                                                    "content", "release acceptance details"))),
                                Hit.of(
                                    hit ->
                                        hit.index("parents")
                                            .id("parent-3")
                                            .source(
                                                Map.of(
                                                    "documentId", "doc-1",
                                                    "sourceFilename", "release.xlsx",
                                                    "pageNumber", 3,
                                                    "content", "release checklist details")))))));
  }

  private static SearchResponse<Map> emptySearchResponse() {
    return SearchResponse.of(
        response ->
            response.took(1).timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(List.of())));
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
