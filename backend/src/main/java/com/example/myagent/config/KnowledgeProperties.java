package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(
    @DefaultValue Embedding embedding,
    @DefaultValue Multimodal multimodal,
    @DefaultValue Elasticsearch elasticsearch,
    @DefaultValue Kafka kafka,
    @DefaultValue Storage storage) {

  public record Embedding(
      @DefaultValue("dashscope") String provider,
      @DefaultValue("text-embedding-v4") String model,
      @DefaultValue("1024") int dimensions,
      @DefaultValue("DASHSCOPE_API_KEY") String apiKeyEnv) {}

  public record Multimodal(
      @DefaultValue("dashscope") String provider,
      @DefaultValue("qwen3.7-plus") String model,
      @DefaultValue("DASHSCOPE_API_KEY") String apiKeyEnv) {}

  public record Elasticsearch(
      @DefaultValue("http://elasticsearch:9200") String url,
      @DefaultValue("elastic") String username,
      @DefaultValue("") String password,
      @DefaultValue("myagent_knowledge_parents") String parentIndex,
      @DefaultValue("myagent_knowledge_children") String childIndex) {}

  public record Kafka(
      @DefaultValue("myagent.knowledge.document.process") String topic,
      @DefaultValue("myagent-knowledge-etl") String group,
      @DefaultValue("kafka:9092") String bootstrapServers) {}

  public record Storage(@DefaultValue("./.knowledge/storage") String root) {}
}
