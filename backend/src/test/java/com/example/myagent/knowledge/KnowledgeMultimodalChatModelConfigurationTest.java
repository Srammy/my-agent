package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

class KnowledgeMultimodalChatModelConfigurationTest {

  @Test
  void createsChatModelWithConfiguredMultimodalModel() {
    KnowledgeProperties properties =
        new KnowledgeProperties(
            new KnowledgeProperties.Embedding("dashscope", "text-embedding-v4", 1024, "DASHSCOPE_API_KEY"),
            new KnowledgeProperties.Multimodal("dashscope", "qwen-vl-test", "DASHSCOPE_API_KEY"),
            new KnowledgeProperties.Elasticsearch(
                "http://elasticsearch:9200", "elastic", "", "parents", "children"),
            new KnowledgeProperties.Kafka("topic", "group", "kafka:9092"),
            new KnowledgeProperties.Storage("./.knowledge/storage"));

    ChatModel model =
        new KnowledgeMultimodalChatModelConfiguration(name -> "test-key")
            .knowledgeMultimodalChatModel(properties);

    assertThat(model).isNotNull();
    assertThat(model.getDefaultOptions().getModel()).isEqualTo("qwen-vl-test");
  }
}
