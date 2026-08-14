package com.example.myagent.knowledge.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQueryPlanningService {

  private static final int MAX_QUERIES = 3;
  private final ChatModel chatModel;
  private final ObjectMapper objectMapper;

  public KnowledgeQueryPlanningService(
      @Qualifier("knowledgeMultimodalChatModel") ObjectProvider<ChatModel> chatModels,
      ObjectMapper objectMapper) {
    this.chatModel = chatModels.getIfAvailable();
    this.objectMapper = objectMapper;
  }

  KnowledgeQueryPlanningService(ChatModel chatModel, ObjectMapper objectMapper) {
    this.chatModel = chatModel;
    this.objectMapper = objectMapper;
  }

  public KnowledgeQueryPlan plan(String question) {
    String normalized = normalize(question);
    if (chatModel == null) return fallback(normalized);
    try {
      ChatResponse response = chatModel.call(new Prompt(new UserMessage(
          "将用户问题规划为知识库检索查询。只返回 JSON："
              + "{\"strategy\":\"DIRECT|REWRITE|DECOMPOSE\",\"queries\":[\"...\"]}。"
              + "最多 3 条，不能回答问题。用户问题：" + normalized)));
      KnowledgeQueryPlan raw = objectMapper.readValue(
          normalizeJson(response.getResult().getOutput().getText()), KnowledgeQueryPlan.class);
      return validate(raw, normalized);
    } catch (Exception error) {
      return fallback(normalized);
    }
  }

  private KnowledgeQueryPlan validate(KnowledgeQueryPlan raw, String original) {
    if (raw == null || raw.strategy() == null) return fallback(original);
    LinkedHashSet<String> queries = new LinkedHashSet<>();
    if (raw.queries() != null) {
      for (String query : raw.queries()) {
        if (query != null && !query.isBlank()) queries.add(query.replaceAll("\\s+", " ").trim());
      }
    }
    if (queries.isEmpty()) return fallback(original);
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (raw.strategy() == KnowledgeQueryPlanStrategy.DIRECT
        || raw.strategy() == KnowledgeQueryPlanStrategy.REWRITE) result.add(original);
    result.addAll(queries);
    return new KnowledgeQueryPlan(raw.strategy(), result.stream().limit(MAX_QUERIES).toList());
  }

  private static KnowledgeQueryPlan fallback(String question) {
    return KnowledgeQueryPlan.direct(question);
  }

  private static String normalize(String question) {
    if (question == null || question.isBlank()) throw new IllegalArgumentException("问题不能为空");
    return question.replaceAll("\\s+", " ").trim();
  }

  static String normalizeJson(String response) {
    String value = response == null ? "" : response.strip();
    if (value.startsWith("```") && value.endsWith("```")) {
      int newline = value.indexOf('\n');
      value = newline >= 0 ? value.substring(newline + 1, value.length() - 3) : value.substring(3, value.length() - 3);
    }
    return value.strip();
  }
}
