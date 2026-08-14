package com.example.myagent.knowledge.search;

import java.util.List;

public record KnowledgeEvidenceBundle(
    List<KnowledgeSearchHit> hits,
    KnowledgeEvidenceLevel level,
    String guidance) {

  public static KnowledgeEvidenceBundle empty() {
    return new KnowledgeEvidenceBundle(List.of(), KnowledgeEvidenceLevel.NONE, "当前没有可用证据，必须拒答。");
  }
}
