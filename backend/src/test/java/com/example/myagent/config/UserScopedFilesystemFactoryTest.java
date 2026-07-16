package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;

class UserScopedFilesystemFactoryTest {

  @Test
  void emptyContextMovesOnlyTheBoundUsersDraft() {
    InMemoryStore store = new InMemoryStore();
    UserScopedFilesystemFactory factory = new UserScopedFilesystemFactory(store);
    AbstractFilesystem aliceFilesystem = factory.create("101");
    AbstractFilesystem sharedFilesystem =
        new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    RuntimeContext empty = RuntimeContext.empty();
    RuntimeContext aliceSessionOne = context("101", "s-1");
    RuntimeContext aliceSessionTwo = context("101", "s-2");
    RuntimeContext bob = context("102", "s-1");

    assertThat(
            aliceFilesystem
                .write(
                    empty,
                    "skills/_drafts/reviewer/SKILL.md",
                    "---\nname: reviewer\ndescription: Review code\n---\n")
                .isSuccess())
        .isTrue();
    assertThat(
            aliceFilesystem
                .move(empty, "skills/_drafts/reviewer", "skills/reviewer")
                .isSuccess())
        .isTrue();

    assertThat(sharedFilesystem.exists(aliceSessionOne, "skills/reviewer/SKILL.md")).isTrue();
    assertThat(sharedFilesystem.exists(aliceSessionTwo, "skills/reviewer/SKILL.md")).isTrue();
    assertThat(sharedFilesystem.exists(bob, "skills/reviewer/SKILL.md")).isFalse();
  }

  @Test
  void rejectsBlankUserId() {
    UserScopedFilesystemFactory factory = new UserScopedFilesystemFactory(new InMemoryStore());

    assertThatThrownBy(() -> factory.create(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId");
  }

  private static RuntimeContext context(String userId, String sessionId) {
    return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
  }
}
