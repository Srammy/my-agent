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
    return withDraftLockIfNeeded(path, () -> delegate.write(context, path, content));
  }

  @Override
  public EditResult edit(
      RuntimeContext context,
      String path,
      String oldText,
      String newText,
      boolean replaceAll) {
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
    return withDraftLockIfNeeded(path, () -> delegate.delete(context, path));
  }

  @Override
  public WriteResult move(RuntimeContext context, String source, String target) {
    if (!affectsDraft(source) && !affectsDraft(target)) {
      return delegate.move(context, source, target);
    }
    return withDraftLock(
        handle -> {
          Optional<String> promotedSkill = promotedSkillName(source, target);
          if (promotedSkill.isEmpty()) {
            return delegate.move(context, source, target);
          }
          return promotionGuard.moveApprovedDraft(
              userId,
              promotedSkill.get(),
              delegate,
              context,
              handle,
              () -> delegate.move(context, source, target));
        });
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

  private static boolean affectsDraft(String path) {
    String normalized = normalize(path);
    return normalized.equals(DRAFTS_DIR) || normalized.startsWith(DRAFTS_DIR + "/");
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
