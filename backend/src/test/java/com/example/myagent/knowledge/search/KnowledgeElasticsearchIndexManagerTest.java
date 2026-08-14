package com.example.myagent.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.util.ObjectBuilder;
import com.example.myagent.config.KnowledgeProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class KnowledgeElasticsearchIndexManagerTest {

  @Test
  void ignoresUnavailableIndexesDuringDocumentCleanup() throws Exception {
    ElasticsearchClient client = mock(ElasticsearchClient.class);
    DeleteByQueryResponse response = mock(DeleteByQueryResponse.class);
    when(response.timedOut()).thenReturn(false);
    when(response.versionConflicts()).thenReturn(0L);
    when(response.failures()).thenReturn(List.of());
    List<DeleteByQueryRequest> requests = new ArrayList<>();
    doAnswer(
            invocation -> {
              Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>> builder =
                  invocation.getArgument(0);
              requests.add(builder.apply(new DeleteByQueryRequest.Builder()).build());
              return response;
            })
        .when(client)
        .deleteByQuery(any(Function.class));

    KnowledgeElasticsearchIndexManager manager =
        new KnowledgeElasticsearchIndexManager(client, properties());

    manager.deleteByDocument(7L, "doc-1");

    assertThat(requests).hasSize(1).allSatisfy(request -> assertThat(request.ignoreUnavailable()).isTrue());
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
