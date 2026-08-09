package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.example.myagent.knowledge.search.KnowledgeChildDocument;
import com.example.myagent.knowledge.search.KnowledgeElasticsearchIndexManager;
import com.example.myagent.knowledge.search.KnowledgeEmbeddingService;
import com.example.myagent.knowledge.search.KnowledgeIndexService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class KnowledgeIndexServiceTest {

  @Test
  void embedsChildrenWithConfiguredDimensionsAndKeepsOwnershipMetadata() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(any(String.class))).thenReturn(new float[] {0.1f, 0.2f});
    KnowledgeEmbeddingService embeddingService =
        new KnowledgeEmbeddingService(embeddingModel, properties(2));
    KnowledgeElasticsearchIndexManager indexManager = mock(KnowledgeElasticsearchIndexManager.class);
    KnowledgeIndexService service = new KnowledgeIndexService(indexManager, embeddingService);

    KnowledgeDocumentContent content = content(7L, "doc-1");
    service.index(content);

    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(indexManager).writeParents(any());
    verify(indexManager).writeChildren(captor.capture());
    List<KnowledgeChildDocument> children = captor.getValue();
    assertThat(children).singleElement().satisfies(child -> {
      assertThat(child.id()).isEqualTo("doc-1_p_0_c_0");
      assertThat(child.userId()).isEqualTo(7L);
      assertThat(child.documentId()).isEqualTo("doc-1");
      assertThat(child.embedding()).containsExactly(0.1f, 0.2f);
    });
  }

  @Test
  void rejectsEmbeddingWithUnexpectedConfiguredDimension() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(any(String.class))).thenReturn(new float[] {0.1f});
    KnowledgeEmbeddingService embeddingService =
        new KnowledgeEmbeddingService(embeddingModel, properties(2));

    assertThatThrownBy(() -> embeddingService.embed("text"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected 2");
  }

  @Test
  void removesPartialIndexWhenChildBulkWriteFails() {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.embed(any(String.class))).thenReturn(new float[] {0.1f, 0.2f});
    KnowledgeEmbeddingService embeddingService =
        new KnowledgeEmbeddingService(embeddingModel, properties(2));
    KnowledgeElasticsearchIndexManager indexManager = mock(KnowledgeElasticsearchIndexManager.class);
    doThrow(new IllegalStateException("child bulk failed"))
        .when(indexManager)
        .writeChildren(any());
    KnowledgeIndexService service = new KnowledgeIndexService(indexManager, embeddingService);

    assertThatThrownBy(() -> service.index(content(7L, "doc-1")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("child bulk failed");
    verify(indexManager, org.mockito.Mockito.times(2)).deleteByDocument(7L, "doc-1");
  }

  private static KnowledgeDocumentContent content(Long userId, String documentId) {
    var child =
        new KnowledgeDocumentContent.ChildDocument(
            documentId + "_p_0_c_0",
            documentId + "_p_0",
            0,
            1,
            "content",
            java.util.Map.of("userId", userId, "documentId", documentId));
    var parent =
        new KnowledgeDocumentContent.ParentDocument(
            documentId + "_p_0",
            0,
            1,
            "content",
            java.util.Map.of("userId", userId, "documentId", documentId),
            List.of(child));
    return new KnowledgeDocumentContent(documentId, userId, "source.pdf", "application/pdf", List.of(parent));
  }

  private static KnowledgeProperties properties(int dimensions) {
    return new KnowledgeProperties(
        new KnowledgeProperties.Embedding("test", "embedding-test", dimensions, "KEY"),
        new KnowledgeProperties.Multimodal("test", "vision-test", "KEY"),
        new KnowledgeProperties.Elasticsearch(
            "http://localhost:9200", "elastic", "", "parents", "children"),
        new KnowledgeProperties.Kafka("topic", "group", "localhost:9092"),
        new KnowledgeProperties.Storage("target/storage"));
  }
}
