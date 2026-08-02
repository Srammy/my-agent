package com.example.myagent.config;

import com.example.myagent.skillreview.SkillApprovalGuardedFilesystem;
import com.example.myagent.skillreview.SkillDraftLock;
import com.example.myagent.skillreview.SkillPromotionGuard;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class UserScopedFilesystemFactory {

  private final BaseStore store;
  private final SkillDraftLock draftLock;
  private final SkillPromotionGuard promotionGuard;
  private final ConcurrentMap<String, AbstractFilesystem> filesystems = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, AbstractFilesystem> workspaceApiFilesystems = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, SkillUsageStore> usageStores = new ConcurrentHashMap<>();

  public UserScopedFilesystemFactory(
      BaseStore store,
      SkillDraftLock draftLock,
      SkillPromotionGuard promotionGuard) {
    this.store = store;
    this.draftLock = draftLock;
    this.promotionGuard = promotionGuard;
  }

  public AbstractFilesystem create(String userId) {
    validateUserId(userId);
    return filesystems.computeIfAbsent(
        userId,
        id ->
            new SkillApprovalGuardedFilesystem(
                new RemoteFilesystem(store, List.of(id)),
                id,
                draftLock,
                promotionGuard));
  }

  public AbstractFilesystem createWorkspaceApiFilesystem(String userId) {
    validateUserId(userId);
    return workspaceApiFilesystems.computeIfAbsent(
        userId,
        id -> new BinarySafeRemoteFilesystem(store, ignored -> List.of(id)));
  }

  public SkillUsageStore usageStore(String userId) {
    validateUserId(userId);
    return usageStores.computeIfAbsent(
        userId, id -> new SkillUsageStore(new RemoteFilesystem(store, List.of(id))));
  }

  private static void validateUserId(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
  }
}
