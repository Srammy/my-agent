package com.example.myagent.skillreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SkillReviewDecisionStore {

  private static final String STORE_DIR = "skill-reviews";
  private static final int READ_LIMIT = 65_536;
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final AbstractFilesystem filesystem;

  public SkillReviewDecisionStore(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public SkillReviewDecision approve(
      String skillName, String reviewerId, List<String> environments, String userId) {
    SkillReviewDecision decision =
        new SkillReviewDecision(
            skillName, "APPROVED", reviewerId, null, environments, Instant.now());
    persist(decision, userId);
    return decision;
  }

  public SkillReviewDecision reject(String skillName, String reviewerId, String reason, String userId) {
    SkillReviewDecision decision =
        new SkillReviewDecision(skillName, "REJECTED", reviewerId, reason, List.of(), Instant.now());
    persist(decision, userId);
    return decision;
  }

  public Optional<SkillReviewDecision> find(String skillName, String userId) {
    RuntimeContext ctx = userContext(userId);
    String path = entryPath(skillName);
    if (!filesystem.exists(ctx, path)) {
      return Optional.empty();
    }
    ReadResult result = filesystem.read(ctx, path, 0, READ_LIMIT);
    if (!result.isSuccess()) {
      return Optional.empty();
    }
    try {
      return Optional.of(JSON.readValue(result.fileData().content(), SkillReviewDecision.class));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Optional<Instant> decidedAt(String skillName, String userId) {
    return find(skillName, userId).map(SkillReviewDecision::decidedAt);
  }

  private void persist(SkillReviewDecision decision, String userId) {
    RuntimeContext ctx = userContext(userId);
    try {
      String json = JSON.writeValueAsString(decision);
      WriteResult result = filesystem.write(ctx, entryPath(decision.skillName()), json);
      if (!result.isSuccess()) {
        throw new IllegalStateException(
            "Failed to persist skill review decision: " + result.error());
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize skill review decision", e);
    }
  }

  private static String entryPath(String skillName) {
    return STORE_DIR + "/" + skillName + ".json";
  }

  private static RuntimeContext userContext(String userId) {
    return RuntimeContext.builder().userId(userId).sessionId("skill-review").build();
  }
}
