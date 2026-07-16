package com.example.myagent.config;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class UserScopedFilesystemFactory {

  private final BaseStore store;
  private final ConcurrentMap<String, AbstractFilesystem> filesystems = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, SkillUsageStore> usageStores = new ConcurrentHashMap<>();

  public UserScopedFilesystemFactory(BaseStore store) {
    this.store = store;
  }

  public AbstractFilesystem create(String userId) {
    validateUserId(userId);
    return filesystems.computeIfAbsent(
        userId, id -> new RemoteFilesystem(store, List.of(id)));
  }

  public SkillUsageStore usageStore(String userId) {
    validateUserId(userId);
    return usageStores.computeIfAbsent(userId, id -> new SkillUsageStore(create(id)));
  }

  private static void validateUserId(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
  }
}
