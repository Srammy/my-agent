package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.config.KnowledgeRetrievalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KnowledgeConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(KnowledgeTestConfiguration.class);

  @Test
  void bindsConfiguredKnowledgeProperties() {
    contextRunner
        .withPropertyValues(
            "knowledge.embedding.provider=openai",
            "knowledge.embedding.model=text-embedding-test",
            "knowledge.embedding.dimensions=1024",
            "knowledge.embedding.api-key-env=KNOWLEDGE_EMBEDDING_API_KEY",
            "knowledge.multimodal.provider=dashscope",
            "knowledge.multimodal.model=qwen-vl-test",
            "knowledge.multimodal.api-key-env=KNOWLEDGE_MULTIMODAL_API_KEY",
            "knowledge.elasticsearch.url=http://elasticsearch:9200",
            "knowledge.elasticsearch.username=elastic",
            "knowledge.elasticsearch.password=",
            "knowledge.elasticsearch.chunk-index=knowledge-chunks",
            "knowledge.kafka.topic=knowledge-ingest",
            "knowledge.kafka.group=knowledge-consumers",
            "knowledge.kafka.bootstrap-servers=kafka:9092",
            "knowledge.storage.root=./.knowledge/storage",
            "knowledge.postgresql.url=jdbc:postgresql://postgres:5432/myagent_knowledge",
            "knowledge.postgresql.username=knowledge",
            "knowledge.postgresql.password=secret",
            "knowledge.pgvector.table-name=vector_store",
            "knowledge.pgvector.dimensions=1536",
            "knowledge.pgvector.initialize-schema=false",
            "knowledge.retrieval.min-rrf-score=0.03",
            "knowledge.retrieval.top-k=12",
            "knowledge.retrieval.channel-top-k=24",
            "knowledge.retrieval.rrf-k=80",
            "knowledge.retrieval.neighbor-window=2",
            "knowledge.retrieval.query-planning-enabled=true")
        .run(
            context -> {
              KnowledgeProperties properties = context.getBean(KnowledgeProperties.class);
              KnowledgeRetrievalProperties retrieval = context.getBean(KnowledgeRetrievalProperties.class);
              assertThat(properties.embedding().provider()).isEqualTo("openai");
              assertThat(properties.embedding().model()).isEqualTo("text-embedding-test");
              assertThat(properties.embedding().dimensions()).isEqualTo(1024);
              assertThat(properties.embedding().apiKeyEnv())
                  .isEqualTo("KNOWLEDGE_EMBEDDING_API_KEY");
              assertThat(properties.multimodal().provider()).isEqualTo("dashscope");
              assertThat(properties.multimodal().model()).isEqualTo("qwen-vl-test");
              assertThat(properties.multimodal().apiKeyEnv())
                  .isEqualTo("KNOWLEDGE_MULTIMODAL_API_KEY");
              assertThat(properties.elasticsearch().url()).isEqualTo("http://elasticsearch:9200");
              assertThat(properties.elasticsearch().username()).isEqualTo("elastic");
              assertThat(properties.elasticsearch().password()).isEmpty();
              assertThat(properties.elasticsearch().chunkIndex()).isEqualTo("knowledge-chunks");
              assertThat(properties.kafka().topic()).isEqualTo("knowledge-ingest");
              assertThat(properties.kafka().group()).isEqualTo("knowledge-consumers");
              assertThat(properties.kafka().bootstrapServers()).isEqualTo("kafka:9092");
              assertThat(properties.storage().root()).isEqualTo("./.knowledge/storage");
              assertThat(properties.postgresql().url())
                  .isEqualTo("jdbc:postgresql://postgres:5432/myagent_knowledge");
              assertThat(properties.postgresql().username()).isEqualTo("knowledge");
              assertThat(properties.postgresql().password()).isEqualTo("secret");
              assertThat(properties.pgvector().tableName()).isEqualTo("vector_store");
              assertThat(properties.pgvector().dimensions()).isEqualTo(1536);
              assertThat(properties.pgvector().initializeSchema()).isFalse();
              assertThat(retrieval.minRrfScore()).isEqualTo(0.03);
              assertThat(retrieval.topK()).isEqualTo(12);
              assertThat(retrieval.channelTopK()).isEqualTo(24);
              assertThat(retrieval.rrfK()).isEqualTo(80);
              assertThat(retrieval.neighborWindow()).isEqualTo(2);
              assertThat(retrieval.queryPlanningEnabled()).isTrue();
            });
  }

  @Test
  void appliesKnowledgeDefaultsFromConfigurationClass() {
    contextRunner.run(
        context -> {
          KnowledgeProperties properties = context.getBean(KnowledgeProperties.class);
          KnowledgeRetrievalProperties retrieval = context.getBean(KnowledgeRetrievalProperties.class);
          assertThat(properties.embedding().provider()).isEqualTo("dashscope");
          assertThat(properties.embedding().model()).isEqualTo("text-embedding-v4");
          assertThat(properties.embedding().dimensions()).isEqualTo(1024);
          assertThat(properties.embedding().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
          assertThat(properties.multimodal().provider()).isEqualTo("dashscope");
          assertThat(properties.multimodal().model()).isEqualTo("qwen3.7-plus");
          assertThat(properties.multimodal().apiKeyEnv()).isEqualTo("DASHSCOPE_API_KEY");
          assertThat(properties.elasticsearch().url()).isEqualTo("http://elasticsearch:9200");
          assertThat(properties.elasticsearch().username()).isEqualTo("elastic");
          assertThat(properties.elasticsearch().password()).isEmpty();
          assertThat(properties.elasticsearch().chunkIndex())
              .isEqualTo("myagent_knowledge_chunks");
          assertThat(properties.kafka().topic())
              .isEqualTo("myagent.knowledge.document.process");
          assertThat(properties.kafka().group()).isEqualTo("myagent-knowledge-etl");
          assertThat(properties.kafka().bootstrapServers()).isEqualTo("kafka:9092");
          assertThat(properties.storage().root()).isEqualTo("./.knowledge/storage");
          assertThat(properties.postgresql().url())
              .isEqualTo("jdbc:postgresql://postgres:5432/myagent_knowledge");
          assertThat(properties.postgresql().username()).isEqualTo("postgres");
          assertThat(properties.postgresql().password()).isEmpty();
          assertThat(properties.pgvector().tableName()).isEqualTo("vector_store");
          assertThat(properties.pgvector().dimensions()).isEqualTo(1024);
          assertThat(properties.pgvector().initializeSchema()).isTrue();
          assertThat(retrieval.minRrfScore()).isEqualTo(0.02);
          assertThat(retrieval.topK()).isEqualTo(8);
          assertThat(retrieval.channelTopK()).isEqualTo(50);
          assertThat(retrieval.rrfK()).isEqualTo(60);
          assertThat(retrieval.neighborWindow()).isEqualTo(1);
          assertThat(retrieval.queryPlanningEnabled()).isTrue();
        });
  }

  @org.springframework.boot.test.context.TestConfiguration
  @EnableConfigurationProperties({KnowledgeProperties.class, KnowledgeRetrievalProperties.class})
  static class KnowledgeTestConfiguration {}
}
