package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(
    @DefaultValue Embedding embedding,
    @DefaultValue Multimodal multimodal,
    @DefaultValue Elasticsearch elasticsearch,
    @DefaultValue Kafka kafka,
    @DefaultValue Storage storage,
    @DefaultValue Postgresql postgresql,
    @DefaultValue Pgvector pgvector,
    @DefaultValue Retrieval retrieval) {

  public KnowledgeProperties(
      Embedding embedding,
      Multimodal multimodal,
      Elasticsearch elasticsearch,
      Kafka kafka,
      Storage storage) {
    this(embedding, multimodal, elasticsearch, kafka, storage, new Postgresql(), new Pgvector(), new Retrieval());
  }

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

  public record Postgresql(
      @DefaultValue("jdbc:postgresql://postgres:5432/myagent_knowledge") String url,
      @DefaultValue("postgres") String username,
      @DefaultValue("") String password,
      @DefaultValue("public") String schema,
      @DefaultValue("db/knowledge-migration") String migrationLocations) {

    public Postgresql() {
      this(
          "jdbc:postgresql://postgres:5432/myagent_knowledge",
          "postgres",
          "",
          "public",
          "db/knowledge-migration");
    }
  }

  public record Pgvector(
      @DefaultValue("vector_store") String tableName,
      @DefaultValue("1024") int dimensions,
      @DefaultValue("true") boolean initializeSchema) {

    public Pgvector() {
      this("vector_store", 1024, true);
    }
  }

  public record Retrieval(
      @DefaultValue("50") int channelTopK,
      @DefaultValue("60") int rrfK,
      @DefaultValue("1") int neighborWindow,
      @DefaultValue("true") boolean queryPlanningEnabled) {

    public Retrieval() {
      this(50, 60, 1, true);
    }
  }
}
