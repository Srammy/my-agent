package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.etl.KnowledgeChunkingService;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeChunkingServiceTest {

  @Test
  void createsStableFlatChunksWithUserAndDocumentMetadata() {
    KnowledgeChunkingService service = new KnowledgeChunkingService(properties());

    List<KnowledgeDocumentContent.ChunkDocument> chunks = service.chunk(
        "doc-1", 7L, "guide.md", "text/markdown",
        "# Installation\n\n" + "Python installation details. ".repeat(40), 1, 0);

    assertThat(chunks).isNotEmpty();
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(chunk.chunkId()).isEqualTo("doc-1:" + chunk.chunkIndex());
      assertThat(chunk.metadata()).containsEntry("userId", 7L)
          .containsEntry("documentId", "doc-1")
          .containsEntry("pageNumber", 1);
      assertThat(chunk.text()).isNotBlank();
    });
    assertThat(chunks).extracting(KnowledgeDocumentContent.ChunkDocument::chunkIndex)
        .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
  }

  private static KnowledgeProperties properties() {
    return new KnowledgeProperties(
        new KnowledgeProperties.Embedding("test", "embedding", 2, "KEY"),
        new KnowledgeProperties.Multimodal("test", "vision", "KEY"),
        new KnowledgeProperties.Elasticsearch("http://localhost:9200", "", "", "parents", "children"),
        new KnowledgeProperties.Kafka("topic", "group", "localhost:9092"),
        new KnowledgeProperties.Storage("target"),
        new KnowledgeProperties.Chunking(40, 60, 8),
        new KnowledgeProperties.Postgresql(),
        new KnowledgeProperties.Pgvector(),
        new KnowledgeProperties.Retrieval());
  }
}
