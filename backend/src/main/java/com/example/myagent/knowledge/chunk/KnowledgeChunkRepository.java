package com.example.myagent.knowledge.chunk;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeChunkRepository {

  private static final RowMapper<KnowledgeChunk> ROW_MAPPER = KnowledgeChunkRepository::mapRow;

  private final JdbcTemplate jdbcTemplate;

  public KnowledgeChunkRepository(
      @Qualifier("knowledgePostgresqlJdbcTemplate") JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public int deleteByUserAndDocument(Long userId, String documentId) {
    return jdbcTemplate.update(
        "delete from document_chunks where user_id = ? and document_id = ?", userId, documentId);
  }

  public int insertBatch(List<KnowledgeChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return 0;
    }
    String sql = """
        insert into document_chunks (
          user_id, document_id, chunk_id, chunk_index, chunk_text, chunk_summary,
          char_start, char_end, metadata_json, created_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
        """;
    int[] results = jdbcTemplate.batchUpdate(sql, chunks, chunks.size(), KnowledgeChunkRepository::bind);
    int inserted = 0;
    for (int result : results) {
      inserted += result;
    }
    return inserted;
  }

  public List<KnowledgeChunk> findByUserAndDocument(Long userId, String documentId) {
    return jdbcTemplate.query(
        """
        select id, user_id, document_id, chunk_id, chunk_index, chunk_text, chunk_summary,
               char_start, char_end, metadata_json::text, created_at, updated_at
          from document_chunks
         where user_id = ? and document_id = ?
         order by chunk_index asc, id asc
        """,
        ROW_MAPPER,
        userId,
        documentId);
  }

  public List<KnowledgeChunk> findByUserAndChunkIds(Long userId, List<String> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", chunkIds.stream().map(ignored -> "?").toList());
    Object[] arguments = new Object[chunkIds.size() + 1];
    arguments[0] = userId;
    System.arraycopy(chunkIds.toArray(), 0, arguments, 1, chunkIds.size());
    return jdbcTemplate.query(
        "select id, user_id, document_id, chunk_id, chunk_index, chunk_text, chunk_summary, "
            + "char_start, char_end, metadata_json::text, created_at, updated_at "
            + "from document_chunks where user_id = ? and chunk_id in ("
            + placeholders
            + ") order by document_id, chunk_index, id",
        ROW_MAPPER,
        arguments);
  }

  private static void bind(PreparedStatement statement, KnowledgeChunk chunk) throws SQLException {
    statement.setObject(1, chunk.userId(), Types.BIGINT);
    statement.setString(2, chunk.documentId());
    statement.setString(3, chunk.chunkId());
    statement.setObject(4, chunk.chunkIndex(), Types.INTEGER);
    statement.setString(5, chunk.chunkText());
    statement.setString(6, chunk.chunkSummary());
    statement.setObject(7, chunk.charStart(), Types.INTEGER);
    statement.setObject(8, chunk.charEnd(), Types.INTEGER);
    statement.setString(9, chunk.metadataJson());
    statement.setObject(10, chunk.createdAt() == null ? LocalDateTime.now() : chunk.createdAt());
    statement.setObject(11, chunk.updatedAt() == null ? LocalDateTime.now() : chunk.updatedAt());
  }

  private static KnowledgeChunk mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
    return new KnowledgeChunk(
        resultSet.getLong("id"),
        resultSet.getLong("user_id"),
        resultSet.getString("document_id"),
        resultSet.getString("chunk_id"),
        resultSet.getInt("chunk_index"),
        resultSet.getString("chunk_text"),
        resultSet.getString("chunk_summary"),
        (Integer) resultSet.getObject("char_start"),
        (Integer) resultSet.getObject("char_end"),
        resultSet.getString("metadata_json"),
        resultSet.getObject("created_at", LocalDateTime.class),
        resultSet.getObject("updated_at", LocalDateTime.class));
  }
}
