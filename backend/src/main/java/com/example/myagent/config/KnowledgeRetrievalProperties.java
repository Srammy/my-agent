package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "knowledge.retrieval")
public record KnowledgeRetrievalProperties(
    @DefaultValue("0.02") double minRrfScore,
    @DefaultValue("8") int topK,
    @DefaultValue("50") int channelTopK,
    @DefaultValue("60") int rrfK,
    @DefaultValue("1") int neighborWindow,
    @DefaultValue("true") boolean queryPlanningEnabled) {

  public KnowledgeRetrievalProperties(double minRrfScore, int topK) {
    this(minRrfScore, topK, 50, 60, 1, true);
  }
}
