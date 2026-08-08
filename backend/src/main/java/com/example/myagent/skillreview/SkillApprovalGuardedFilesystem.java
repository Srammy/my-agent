package com.example.myagent.skillreview;

import com.example.myagent.skill.SkillPathValidator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public final class SkillApprovalGuardedFilesystem implements AbstractFilesystem {

  private static final String DRAFTS_DIR = "skills/_drafts";
  private static final String SKILLS_DIR = "skills";
  private static final String DRAFT_SKILL_NAME_CONTEXT_KEY =
      SkillApprovalGuardedFilesystem.class.getName() + ".draftSkillName";
  private static final String FORMAL_SKILL_WRITE_ERROR =
      "Agent cannot modify formal skills directly; use the draft approval flow";
  private static final String MULTIPLE_DRAFT_SKILLS_ERROR =
      "Only one skill draft can be created per agent request";

  private final AbstractFilesystem delegate;
  private final String userId;
  private final SkillDraftLock draftLock;
  private final SkillPromotionGuard promotionGuard;

  public SkillApprovalGuardedFilesystem(
      AbstractFilesystem delegate,
      String userId,
      SkillDraftLock draftLock,
      SkillPromotionGuard promotionGuard) {
    if (delegate == null) {
      throw new IllegalArgumentException("delegate is required");
    }
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
    if (draftLock == null) {
      throw new IllegalArgumentException("draftLock is required");
    }
    if (promotionGuard == null) {
      throw new IllegalArgumentException("promotionGuard is required");
    }
    this.delegate = delegate;
    this.userId = userId;
    this.draftLock = draftLock;
    this.promotionGuard = promotionGuard;
  }

  @Override
  public LsResult ls(RuntimeContext context, String path) {
    return delegate.ls(context, path);
  }

  @Override
  public ReadResult read(RuntimeContext context, String path, int lineStart, int lineEnd) {
    return delegate.read(context, path, lineStart, lineEnd);
  }

  @Override
  public WriteResult write(RuntimeContext context, String path, String content) {
    if (affectsFormalSkills(path)) {
      return WriteResult.fail(FORMAL_SKILL_WRITE_ERROR);
    }
    Optional<String> draftSkillName = draftSkillName(path);
    if (draftSkillName.isPresent()) {
      return withDraftLock(
          () -> writeDraft(context, path, content, draftSkillName.get()));
    }
    return withDraftLockIfNeeded(path, () -> delegate.write(context, path, content));
  }

  @Override
  public EditResult edit(
      RuntimeContext context,
      String path,
      String oldText,
      String newText,
      boolean replaceAll) {
    if (affectsFormalSkills(path)) {
      return EditResult.fail(FORMAL_SKILL_WRITE_ERROR);
    }
    return withDraftLockIfNeeded(
        path, () -> delegate.edit(context, path, oldText, newText, replaceAll));
  }

  @Override
  public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) {
    return delegate.grep(context, pattern, path, glob);
  }

  @Override
  public GlobResult glob(RuntimeContext context, String pattern, String path) {
    return delegate.glob(context, pattern, path);
  }

  @Override
  public List<FileUploadResponse> uploadFiles(
      RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
    if (files != null && files.stream().anyMatch(entry -> affectsFormalSkills(entry.getKey()))) {
      return files.stream()
          .map(entry -> FileUploadResponse.fail(entry.getKey(), FORMAL_SKILL_WRITE_ERROR))
          .toList();
    }
    boolean affectsDraft =
        files != null && files.stream().anyMatch(entry -> affectsDraft(entry.getKey()));
    if (!affectsDraft) {
      return delegate.uploadFiles(context, files);
    }
    return withDraftLock(() -> delegate.uploadFiles(context, files));
  }

  @Override
  public List<FileDownloadResponse> downloadFiles(
      RuntimeContext context, List<String> paths) {
    return delegate.downloadFiles(context, paths);
  }

  @Override
  public WriteResult delete(RuntimeContext context, String path) {
    if (affectsFormalSkills(path)) {
      return WriteResult.fail(FORMAL_SKILL_WRITE_ERROR);
    }
    return withDraftLockIfNeeded(path, () -> delegate.delete(context, path));
  }

  @Override
  public WriteResult move(RuntimeContext context, String source, String target) {
    Optional<String> promotedSkill = promotedSkillName(source, target);
    if (promotedSkill.isPresent()) {
      return withDraftLock(
          handle ->
              promotionGuard.moveApprovedDraft(
                  userId,
                  promotedSkill.get(),
                  delegate,
                  context,
                  handle,
                  () -> delegate.move(context, source, target)));
    }
    if (affectsFormalSkills(source) || affectsFormalSkills(target)) {
      return WriteResult.fail(FORMAL_SKILL_WRITE_ERROR);
    }
    if (!affectsDraft(source) && !affectsDraft(target)) {
      return delegate.move(context, source, target);
    }
    return withDraftLock(() -> delegate.move(context, source, target));
  }

  @Override
  public boolean exists(RuntimeContext context, String path) {
    return delegate.exists(context, path);
  }

  private <T> T withDraftLockIfNeeded(String path, Supplier<T> action) {
    return affectsDraft(path) ? withDraftLock(action) : action.get();
  }

  private <T> T withDraftLock(Supplier<T> action) {
    return withDraftLock(ignored -> action.get());
  }

  private <T> T withDraftLock(java.util.function.Function<SkillDraftLock.Handle, T> action) {
    try (SkillDraftLock.Handle handle = draftLock.acquire(userId)) {
      return action.apply(handle);
    }
  }

  private WriteResult writeDraft(
      RuntimeContext context, String path, String content, String draftSkillName) {
    String existingDraftSkillName = context.get(DRAFT_SKILL_NAME_CONTEXT_KEY);
    if (existingDraftSkillName != null && !existingDraftSkillName.equals(draftSkillName)) {
      return WriteResult.fail(MULTIPLE_DRAFT_SKILLS_ERROR);
    }
    WriteResult result = delegate.write(context, path, content);
    if (result.isSuccess() && existingDraftSkillName == null) {
      context.put(DRAFT_SKILL_NAME_CONTEXT_KEY, draftSkillName);
    }
    return result;
  }

  private static boolean affectsDraft(String path) {
    String normalized = normalize(path);
    return normalized.equals(DRAFTS_DIR) || normalized.startsWith(DRAFTS_DIR + "/");
  }

  private static Optional<String> draftSkillName(String path) {
    String normalized = normalize(path);
    String prefix = DRAFTS_DIR + "/";
    if (!normalized.startsWith(prefix)) {
      return Optional.empty();
    }
    String remainingPath = normalized.substring(prefix.length());
    int separator = remainingPath.indexOf('/');
    if (separator <= 0) {
      return Optional.empty();
    }
    try {
      return Optional.of(SkillPathValidator.validateSkillName(remainingPath.substring(0, separator)));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private static boolean affectsFormalSkills(String path) {
    String normalized = normalize(path);
    return normalized.equals(SKILLS_DIR)
        || (normalized.startsWith(SKILLS_DIR + "/") && !affectsDraft(normalized));
  }

  private static Optional<String> promotedSkillName(String source, String target) {
    String normalizedSource = normalize(source);
    String normalizedTarget = normalize(target);
    String prefix = DRAFTS_DIR + "/";
    if (!normalizedSource.startsWith(prefix)) {
      return Optional.empty();
    }
    String candidate = normalizedSource.substring(prefix.length());
    if (candidate.isEmpty()
        || candidate.contains("/")
        || !normalizedTarget.equals(SKILLS_DIR + "/" + candidate)) {
      return Optional.empty();
    }
    try {
      return Optional.of(SkillPathValidator.validateSkillName(candidate));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private static String normalize(String path) {
    if (path == null) {
      return "";
    }
    String normalized = path.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (!normalized.isEmpty() && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
