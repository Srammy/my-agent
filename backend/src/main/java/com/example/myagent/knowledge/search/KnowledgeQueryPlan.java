package com.example.myagent.knowledge.search;

import java.util.List;

public record KnowledgeQueryPlan(KnowledgeQueryPlanStrategy strategy, List<String> queries) {
  public static KnowledgeQueryPlan direct(String question) {
    return new KnowledgeQueryPlan(KnowledgeQueryPlanStrategy.DIRECT, List.of(question));
  }
}
