package com.example.myagent.chat;

import java.util.List;

public record ChatAgentRequest(
    Long userId, String sessionId, String message, List<String> materializedSkillRoots) {

  public static final String MATERIALIZED_SKILL_ROOTS_CONTEXT_KEY = "materializedSkillRoots";

  public ChatAgentRequest {
    materializedSkillRoots =
        materializedSkillRoots == null ? List.of() : List.copyOf(materializedSkillRoots);
  }
}
