package com.example.myagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class KnowledgePgVectorConfiguration {

  @Bean(name = "knowledgeVectorStore")
  VectorStore knowledgeVectorStore(
      KnowledgeProperties properties,
      @Qualifier("knowledgePostgresqlJdbcTemplate") JdbcTemplate jdbcTemplate,
      EmbeddingModel embeddingModel) {
    KnowledgeProperties.Postgresql postgres = properties.postgresql();
    KnowledgeProperties.Pgvector pgvector = properties.pgvector();
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .schemaName(postgres.schema())
        .vectorTableName(pgvector.tableName())
        .dimensions(pgvector.dimensions())
        .initializeSchema(pgvector.initializeSchema())
        .build();
  }
}
