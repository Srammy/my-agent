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
            "knowledge.elasticsearch.parent-index=knowledge-parent",
            "knowledge.elasticsearch.child-index=knowledge-child",
            "knowledge.kafka.topic=knowledge-ingest",
            "knowledge.kafka.group=knowledge-consumers",
            "knowledge.kafka.bootstrap-servers=kafka:9092",
            "knowledge.storage.root=./.knowledge/storage",
            "knowledge.retrieval.min-rrf-score=0.03")
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
              assertThat(properties.elasticsearch().parentIndex())
                  .isEqualTo("knowledge-parent");
              assertThat(properties.elasticsearch().childIndex()).isEqualTo("knowledge-child");
              assertThat(properties.kafka().topic()).isEqualTo("knowledge-ingest");
              assertThat(properties.kafka().group()).isEqualTo("knowledge-consumers");
              assertThat(properties.kafka().bootstrapServers()).isEqualTo("kafka:9092");
              assertThat(properties.storage().root()).isEqualTo("./.knowledge/storage");
              assertThat(retrieval.minRrfScore()).isEqualTo(0.03);
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
          assertThat(properties.elasticsearch().parentIndex())
              .isEqualTo("myagent_knowledge_parents");
          assertThat(properties.elasticsearch().childIndex())
              .isEqualTo("myagent_knowledge_children");
          assertThat(properties.kafka().topic())
              .isEqualTo("myagent.knowledge.document.process");
          assertThat(properties.kafka().group()).isEqualTo("myagent-knowledge-etl");
          assertThat(properties.kafka().bootstrapServers()).isEqualTo("kafka:9092");
          assertThat(properties.storage().root()).isEqualTo("./.knowledge/storage");
          assertThat(retrieval.minRrfScore()).isEqualTo(0.02);
        });
  }

  @org.springframework.boot.test.context.TestConfiguration
  @EnableConfigurationProperties({KnowledgeProperties.class, KnowledgeRetrievalProperties.class})
  static class KnowledgeTestConfiguration {}
}
