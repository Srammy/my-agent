package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.skillreview.BaseStoreSkillDraftLock;
import com.example.myagent.skillreview.SkillDraftFingerprint;
import com.example.myagent.skillreview.SkillPromotionGuard;
import com.example.myagent.skillreview.SkillReviewDecisionStore;
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
    AbstractFilesystem sharedFilesystem =
        new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    SkillReviewDecisionStore decisionStore = new SkillReviewDecisionStore(sharedFilesystem);
    UserScopedFilesystemFactory factory = factory(store, decisionStore);
    AbstractFilesystem aliceFilesystem = factory.create("101");
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
    String draftHash =
        new SkillDraftFingerprint(sharedFilesystem)
            .computeDraftHash(aliceSessionOne, "reviewer");
    decisionStore.approve("reviewer", "admin", java.util.List.of("prod"), draftHash, "101");
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
    InMemoryStore store = new InMemoryStore();
    UserScopedFilesystemFactory factory =
        factory(
            store,
            new SkillReviewDecisionStore(
                new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory())));

    assertThatThrownBy(() -> factory.create(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId");
  }

  @Test
  void reusesFilesystemAndUsageStoreWithinTheSameUser() {
    InMemoryStore store = new InMemoryStore();
    UserScopedFilesystemFactory factory =
        factory(
            store,
            new SkillReviewDecisionStore(
                new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory())));

    assertThat(factory.create("101")).isSameAs(factory.create("101"));
    assertThat(factory.usageStore("101")).isSameAs(factory.usageStore("101"));
    assertThat(factory.usageStore("101")).isNotSameAs(factory.usageStore("102"));
  }

  private static RuntimeContext context(String userId, String sessionId) {
    return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
  }

  private static UserScopedFilesystemFactory factory(
      InMemoryStore store, SkillReviewDecisionStore decisionStore) {
    return new UserScopedFilesystemFactory(
        store,
        new BaseStoreSkillDraftLock(store),
        new SkillPromotionGuard(decisionStore));
  }
}
