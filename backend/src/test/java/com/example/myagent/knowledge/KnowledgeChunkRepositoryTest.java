package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeChunkRepositoryTest {

  @Test
  void deletesOnlyChunksOwnedByTheRequestedUserAndDocument() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.update(
            "delete from document_chunks where user_id = ? and document_id = ?", 7L, "doc-1"))
        .thenReturn(3);
    KnowledgeChunkRepository repository = new KnowledgeChunkRepository(jdbcTemplate);

    assertThat(repository.deleteByUserAndDocument(7L, "doc-1")).isEqualTo(3);

    verify(jdbcTemplate)
        .update(
            eq("delete from document_chunks where user_id = ? and document_id = ?"),
            eq(7L),
            eq("doc-1"));
  }

  @Test
  void doesNotQueryPostgresWhenChunkIdListIsEmpty() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    KnowledgeChunkRepository repository = new KnowledgeChunkRepository(jdbcTemplate);

    assertThat(repository.findByUserAndChunkIds(7L, List.of())).isEmpty();

    org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
  }
}
