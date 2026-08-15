package com.example.myagent.knowledge.search;

import com.example.myagent.config.KnowledgeProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEmbeddingService {

  private final EmbeddingModel embeddingModel;
  private final KnowledgeProperties properties;

  public KnowledgeEmbeddingService(
      EmbeddingModel embeddingModel, KnowledgeProperties properties) {
    this.embeddingModel = embeddingModel;
    this.properties = properties;
  }

  public float[] embed(String text) {
    float[] vector = embeddingModel.embed(text);
    int expectedDimensions = properties.embedding().dimensions();
    if (vector == null || vector.length != expectedDimensions) {
      throw new IllegalStateException(
          "Embedding dimension mismatch: expected "
              + expectedDimensions
              + " but got "
              + (vector == null ? 0 : vector.length));
    }
    return vector;
  }
}
