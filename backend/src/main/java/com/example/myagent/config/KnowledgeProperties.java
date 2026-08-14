package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(
    Embedding embedding,
    Multimodal multimodal,
    Elasticsearch elasticsearch,
    Kafka kafka,
    Storage storage,
    Chunking chunking,
    Postgresql postgresql,
    Pgvector pgvector,
    Retrieval retrieval) {

  @ConstructorBinding
  public KnowledgeProperties {
    if (embedding == null) embedding = new Embedding("dashscope", "text-embedding-v4", 1024, "DASHSCOPE_API_KEY");
    if (multimodal == null) multimodal = new Multimodal("dashscope", "qwen3.7-plus", "DASHSCOPE_API_KEY");
    if (elasticsearch == null) elasticsearch = new Elasticsearch("http://elasticsearch:9200", "elastic", "", "myagent_knowledge_chunks");
    if (kafka == null) kafka = new Kafka("myagent.knowledge.document.process", "myagent-knowledge-etl", "kafka:9092");
    if (storage == null) storage = new Storage("./.knowledge/storage");
    if (chunking == null) chunking = new Chunking();
    if (postgresql == null) postgresql = new Postgresql();
    if (pgvector == null) pgvector = new Pgvector();
    if (retrieval == null) retrieval = new Retrieval();
  }

  public KnowledgeProperties(
      Embedding embedding,
      Multimodal multimodal,
      Elasticsearch elasticsearch,
      Kafka kafka,
      Storage storage) {
    this(embedding, multimodal, elasticsearch, kafka, storage, new Chunking(), new Postgresql(), new Pgvector(), new Retrieval());
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
      @DefaultValue("myagent_knowledge_chunks") String chunkIndex) {

    @ConstructorBinding
    public Elasticsearch {}

    public Elasticsearch(
        String url, String username, String password, String ignoredParentIndex, String ignoredChildIndex) {
      this(url, username, password, "myagent_knowledge_chunks");
    }
  }

  public record Kafka(
      @DefaultValue("myagent.knowledge.document.process") String topic,
      @DefaultValue("myagent-knowledge-etl") String group,
      @DefaultValue("kafka:9092") String bootstrapServers) {}

  public record Storage(@DefaultValue("./.knowledge/storage") String root) {}

  public record Chunking(
      @DefaultValue("240") int targetTokens,
      @DefaultValue("320") int maxTokens,
      @DefaultValue("32") int overlapTokens) {

    public Chunking() {
      this(240, 320, 32);
    }
  }

  public record Postgresql(
      @DefaultValue("jdbc:postgresql://postgres:5432/myagent_knowledge") String url,
      @DefaultValue("postgres") String username,
      @DefaultValue("") String password,
      @DefaultValue("public") String schema,
      @DefaultValue("db/knowledge-migration") String migrationLocations) {

    @ConstructorBinding
    public Postgresql {}

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

    @ConstructorBinding
    public Pgvector {}

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
