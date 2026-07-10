package com.example.myagent.skillreview;

import com.example.myagent.skill.SkillValidator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SkillReviewService {

  private static final String DRAFTS_DIR = "skills/_drafts";
  private static final int READ_LIMIT = 200_000;

  private final AbstractFilesystem filesystem;
  private final SkillReviewDecisionStore decisionStore;
  private final SkillUsageStore usageStore;

  public SkillReviewService(
      AbstractFilesystem filesystem,
      SkillReviewDecisionStore decisionStore,
      SkillUsageStore usageStore) {
    this.filesystem = filesystem;
    this.decisionStore = decisionStore;
    this.usageStore = usageStore;
  }

  public List<SkillReviewDto> list(String userId) {
    RuntimeContext ctx = userContext(userId);
    if (!filesystem.exists(ctx, DRAFTS_DIR)) {
      return List.of();
    }
    LsResult result = filesystem.ls(ctx, DRAFTS_DIR);
    if (!result.isSuccess()) {
      return List.of();
    }
    return result.entries().stream()
        .filter(FileInfo::isDirectory)
        .map(FileInfo::path)
        .sorted()
        .map(skillName -> buildDto(ctx, skillName, userId))
        .toList();
  }

  public SkillReviewDto approve(String skillName, ApproveSkillReviewRequest request, String userId) {
    validateSkillName(skillName);
    List<String> environments =
        request.environments() != null ? request.environments() : List.of();
    String reviewerId = request.reviewerId() != null ? request.reviewerId() : "unknown";
    SkillReviewDecision decision = decisionStore.approve(skillName, reviewerId, environments, userId);
    return toDto(skillName, decision, userId);
  }

  public SkillReviewDto reject(String skillName, RejectSkillReviewRequest request, String userId) {
    validateSkillName(skillName);
    String reviewerId = request.reviewerId() != null ? request.reviewerId() : "unknown";
    String reason = request.reason() != null ? request.reason() : "";
    SkillReviewDecision decision = decisionStore.reject(skillName, reviewerId, reason, userId);
    return toDto(skillName, decision, userId);
  }

  private SkillReviewDto buildDto(RuntimeContext ctx, String skillName, String userId) {
    String skillMdPath = DRAFTS_DIR + "/" + skillName + "/SKILL.md";
    String description = "";
    if (filesystem.exists(ctx, skillMdPath)) {
      ReadResult readResult = filesystem.read(ctx, skillMdPath, 0, READ_LIMIT);
      if (readResult.isSuccess()) {
        try {
          SkillValidator.SkillMarkdownMetadata meta =
              SkillValidator.validateSkillMarkdown(readResult.fileData().content());
          description = meta.description();
        } catch (Exception ignored) {
          // non-fatal: use empty description if SKILL.md is malformed
        }
      }
    }

    Optional<SkillReviewDecision> maybeDecision = decisionStore.find(skillName, userId);
    String status = maybeDecision.map(SkillReviewDecision::status).orElse("PENDING");

    Optional<SkillUsageRecord> maybeUsage = usageStore.get(skillName);
    long useCount = maybeUsage.map(SkillUsageRecord::useCount).orElse(0L);
    long viewCount = maybeUsage.map(SkillUsageRecord::viewCount).orElse(0L);
    long patchCount = maybeUsage.map(SkillUsageRecord::patchCount).orElse(0L);
    String createdBy = maybeUsage.map(SkillUsageRecord::createdBy).orElse(null);
    String sourceSessionId = maybeUsage.map(SkillUsageRecord::sourceSessionId).orElse(null);
    List<String> environments =
        maybeUsage
            .map(SkillUsageRecord::environments)
            .orElse(List.of());

    return new SkillReviewDto(
        skillName, description, status, createdBy, sourceSessionId, environments,
        useCount, viewCount, patchCount);
  }

  private SkillReviewDto toDto(String skillName, SkillReviewDecision decision, String userId) {
    Optional<SkillUsageRecord> maybeUsage = usageStore.get(skillName);
    long useCount = maybeUsage.map(SkillUsageRecord::useCount).orElse(0L);
    long viewCount = maybeUsage.map(SkillUsageRecord::viewCount).orElse(0L);
    long patchCount = maybeUsage.map(SkillUsageRecord::patchCount).orElse(0L);
    String createdBy = maybeUsage.map(SkillUsageRecord::createdBy).orElse(null);
    String sourceSessionId = maybeUsage.map(SkillUsageRecord::sourceSessionId).orElse(null);
    List<String> environments =
        decision.environments() != null ? decision.environments() : List.of();

    return new SkillReviewDto(
        skillName, null, decision.status(), createdBy, sourceSessionId, environments,
        useCount, viewCount, patchCount);
  }

  private static void validateSkillName(String skillName) {
    if (skillName == null || skillName.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
    }
  }

  private static RuntimeContext userContext(String userId) {
    return RuntimeContext.builder().userId(userId).sessionId("skill-review").build();
  }
}
