package com.example.myagent.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgePgVectorServiceTest {

  @Test
  void indexesChunksInBatchesOfAtMostTwenty() {
    VectorStore vectorStore = mock(VectorStore.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    KnowledgePgVectorService service = new KnowledgePgVectorService(vectorStore, jdbcTemplate);
    List<List<org.springframework.ai.document.Document>> batches = new ArrayList<>();
    doAnswer(invocation -> {
      batches.add(invocation.getArgument(0));
      return null;
    }).when(vectorStore).add(anyList());

    service.indexDocuments(chunks(45));

    assertThat(batches).extracting(List::size).containsExactly(10, 10, 10, 10, 5);
  }

  @Test
  void usesUuidForPgVectorIdAndKeepsBusinessChunkIdInMetadata() {
    VectorStore vectorStore = mock(VectorStore.class);
    KnowledgePgVectorService service = new KnowledgePgVectorService(vectorStore, mock(JdbcTemplate.class));
    List<org.springframework.ai.document.Document> indexed = new ArrayList<>();
    doAnswer(invocation -> {
      indexed.addAll(invocation.getArgument(0));
      return null;
    }).when(vectorStore).add(anyList());

    service.indexDocuments(chunks(1));

    assertThat(indexed).singleElement().satisfies(document -> {
      assertThat(UUID.fromString(document.getId())).isNotNull();
      assertThat(document.getMetadata()).containsEntry("chunkId", "doc-1:0");
    });
  }

  private static List<KnowledgeDocumentContent.ChunkDocument> chunks(int count) {
    List<KnowledgeDocumentContent.ChunkDocument> chunks = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      chunks.add(new KnowledgeDocumentContent.ChunkDocument(
          "doc-1:" + index,
          index,
          1,
          "chunk " + index,
          Map.of("userId", 7L, "documentId", "doc-1")));
    }
    return chunks;
  }
}
