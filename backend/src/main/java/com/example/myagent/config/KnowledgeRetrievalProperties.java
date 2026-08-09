package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "knowledge.retrieval")
public record KnowledgeRetrievalProperties(
    @DefaultValue("0.02") double minRrfScore,
    @DefaultValue("8") int topK) {}
