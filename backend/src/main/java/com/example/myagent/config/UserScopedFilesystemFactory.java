package com.example.myagent.config;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import java.util.List;

public final class UserScopedFilesystemFactory {

  private final BaseStore store;

  public UserScopedFilesystemFactory(BaseStore store) {
    this.store = store;
  }

  public AbstractFilesystem create(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
    return new RemoteFilesystem(store, List.of(userId));
  }
}
