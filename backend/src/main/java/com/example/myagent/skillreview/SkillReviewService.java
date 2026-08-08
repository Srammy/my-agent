package com.example.myagent.skillreview;

import com.example.myagent.config.UserScopedFilesystemFactory;
import com.example.myagent.skill.SkillPathValidator;
import com.example.myagent.skill.SkillValidator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
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
  private final UserScopedFilesystemFactory filesystemFactory;
  private final SkillPromotionGuard promotionGuard;
  private final SkillDraftFingerprint fingerprint;
  private final SkillDraftLock draftLock;

  public SkillReviewService(
      AbstractFilesystem filesystem,
      SkillReviewDecisionStore decisionStore,
      UserScopedFilesystemFactory filesystemFactory,
      SkillPromotionGuard promotionGuard,
      SkillDraftFingerprint fingerprint,
      SkillDraftLock draftLock) {
    this.filesystem = filesystem;
    this.decisionStore = decisionStore;
    this.filesystemFactory = filesystemFactory;
    this.promotionGuard = promotionGuard;
    this.fingerprint = fingerprint;
    this.draftLock = draftLock;
  }

  public List<SkillReviewDto> list(String userId) {
    RuntimeContext ctx = userContext(userId);
    SkillUsageStore usageStore = usageStore(userId);
    return draftEntries(ctx).stream()
        .map(FileInfo::path)
        .map(SkillReviewService::draftEntryPath)
        .map(SkillReviewService::draftSkillName)
        .flatMap(Optional::stream)
        .sorted()
        .map(skillName -> buildDto(ctx, skillName, userId, usageStore))
        .toList();
  }

  private List<FileInfo> draftEntries(RuntimeContext ctx) {
    GlobResult globResult = filesystem.glob(ctx, "SKILL.md", DRAFTS_DIR);
    if (globResult != null
        && globResult.isSuccess()
        && globResult.matches() != null
        && !globResult.matches().isEmpty()) {
      return globResult.matches();
    }

    LsResult result = filesystem.ls(ctx, DRAFTS_DIR);
    return result.isSuccess() && result.entries() != null ? result.entries() : List.of();
  }

  public SkillReviewDto approve(
      String skillName, ApproveSkillReviewRequest request, String userId, String reviewerId) {
    validateSkillName(skillName);
    RuntimeContext ctx = userContext(userId);
    List<String> environments =
        request.environments() != null ? request.environments() : List.of();
    SkillReviewDecision decision;
    try (SkillDraftLock.Handle handle = draftLock.acquire(userId)) {
      String draftHash = requireDraftHash(ctx, skillName);
      requireRenewed(handle, userId);
      decision =
          decisionStore.approve(skillName, reviewerId, environments, draftHash, userId);
      promoteApprovedDraft(ctx, skillName, userId, handle);
    }
    return toDto(ctx, skillName, decision, usageStore(userId));
  }

  public SkillReviewDto reject(
      String skillName, RejectSkillReviewRequest request, String userId, String reviewerId) {
    validateSkillName(skillName);
    RuntimeContext ctx = userContext(userId);
    String reason = request.reason() != null ? request.reason() : "";
    SkillReviewDecision decision;
    try (SkillDraftLock.Handle handle = draftLock.acquire(userId)) {
      String draftHash = requireDraftHash(ctx, skillName);
      requireRenewed(handle, userId);
      decision = decisionStore.reject(skillName, reviewerId, reason, draftHash, userId);
    }
    return toDto(ctx, skillName, decision, usageStore(userId));
  }

  private String requireDraftHash(RuntimeContext ctx, String skillName) {
    try {
      return fingerprint.computeDraftHash(ctx, skillName);
    } catch (SkillDraftFingerprintException exception) {
      HttpStatus status =
          exception.reason() == SkillDraftFingerprintException.Reason.NOT_FOUND
              ? HttpStatus.NOT_FOUND
              : HttpStatus.INTERNAL_SERVER_ERROR;
      throw new ResponseStatusException(status, exception.getMessage(), exception);
    }
  }

  private SkillReviewDto buildDto(
      RuntimeContext ctx,
      String skillName,
      String userId,
      SkillUsageStore usageStore) {
    String description = draftDescription(ctx, skillName);

    Optional<SkillReviewDecision> maybeDecision = decisionStore.find(skillName, userId);
    String status = effectiveStatus(ctx, skillName, maybeDecision);

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

  private String draftDescription(RuntimeContext ctx, String skillName) {
    String skillMdPath = DRAFTS_DIR + "/" + skillName + "/SKILL.md";
    String description = "";
    ReadResult readResult = filesystem.read(ctx, skillMdPath, 0, READ_LIMIT);
    if (readResult != null
        && readResult.isSuccess()
        && readResult.fileData() != null
        && readResult.fileData().content() != null) {
      try {
        SkillValidator.SkillMarkdownMetadata meta =
            SkillValidator.validateSkillMarkdown(readResult.fileData().content());
        description = meta.description();
      } catch (Exception ignored) {
        // non-fatal: use empty description if SKILL.md is malformed
      }
    }
    return description;
  }

  private String effectiveStatus(
      RuntimeContext ctx,
      String skillName,
      Optional<SkillReviewDecision> maybeDecision) {
    if (maybeDecision.isEmpty() || maybeDecision.get().draftHash() == null) {
      return "PENDING";
    }
    try {
      String currentHash = fingerprint.computeDraftHash(ctx, skillName);
      return currentHash.equals(maybeDecision.get().draftHash())
          ? maybeDecision.get().status()
          : "PENDING";
    } catch (SkillDraftFingerprintException exception) {
      return "PENDING";
    }
  }

  private SkillReviewDto toDto(
      RuntimeContext ctx,
      String skillName,
      SkillReviewDecision decision,
      SkillUsageStore usageStore) {
    Optional<SkillUsageRecord> maybeUsage = usageStore.get(skillName);
    long useCount = maybeUsage.map(SkillUsageRecord::useCount).orElse(0L);
    long viewCount = maybeUsage.map(SkillUsageRecord::viewCount).orElse(0L);
    long patchCount = maybeUsage.map(SkillUsageRecord::patchCount).orElse(0L);
    String createdBy = maybeUsage.map(SkillUsageRecord::createdBy).orElse(null);
    String sourceSessionId = maybeUsage.map(SkillUsageRecord::sourceSessionId).orElse(null);
    List<String> environments =
        decision.environments() != null ? decision.environments() : List.of();

    return new SkillReviewDto(
        skillName,
        draftDescription(ctx, skillName),
        decision.status(),
        createdBy,
        sourceSessionId,
        environments,
        useCount, viewCount, patchCount);
  }

  private SkillUsageStore usageStore(String userId) {
    return filesystemFactory.usageStore(userId);
  }

  private static void requireRenewed(SkillDraftLock.Handle handle, String userId) {
    if (!handle.renew()) {
      throw new SkillDraftLockException(
          "Skill draft lock expired before saving review for user " + userId);
    }
  }

  private static Optional<String> draftSkillName(String path) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    String normalized = path.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/") && !normalized.isEmpty()) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }

    String prefix = DRAFTS_DIR + "/";
    String candidate;
    if (normalized.startsWith(prefix)) {
      candidate = normalized.substring(prefix.length());
      if (candidate.contains("/")) {
        return Optional.empty();
      }
    } else if (!normalized.contains("/")) {
      candidate = normalized;
    } else {
      return Optional.empty();
    }

    try {
      return Optional.of(SkillPathValidator.validateSkillName(candidate));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private void promoteApprovedDraft(
      RuntimeContext ctx, String skillName, String userId, SkillDraftLock.Handle lockHandle) {
    AbstractFilesystem promotionFilesystem = filesystemFactory.createWorkspaceApiFilesystem(userId);
    WriteResult result =
        promotionGuard.moveApprovedDraft(
            userId,
            skillName,
            promotionFilesystem,
            ctx,
            lockHandle,
            () ->
                promotionFilesystem.move(
                    ctx, DRAFTS_DIR + "/" + skillName, "skills/" + skillName));
    if (result == null || !result.isSuccess()) {
      String reason = result != null ? result.error() : "empty promotion result";
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "Failed to promote approved Skill: " + skillName + " (" + reason + ")");
    }
  }

  private static String draftEntryPath(String path) {
    if (path == null) {
      return null;
    }
    String normalized = path.trim().replace('\\', '/');
    String marker = "/SKILL.md";
    return normalized.endsWith(marker)
        ? normalized.substring(0, normalized.length() - marker.length())
        : normalized;
  }

  private static void validateSkillName(String skillName) {
    try {
      SkillPathValidator.validateSkillName(skillName);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }

  private static RuntimeContext userContext(String userId) {
    return RuntimeContext.builder().userId(userId).sessionId("skill-review").build();
  }
}
