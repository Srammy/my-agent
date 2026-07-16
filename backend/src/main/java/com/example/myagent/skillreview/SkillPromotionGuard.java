package com.example.myagent.skillreview;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.Optional;
import java.util.function.Supplier;

public final class SkillPromotionGuard {

  private final SkillReviewDecisionStore decisionStore;

  public SkillPromotionGuard(SkillReviewDecisionStore decisionStore) {
    if (decisionStore == null) {
      throw new IllegalArgumentException("decisionStore is required");
    }
    this.decisionStore = decisionStore;
  }

  public WriteResult moveApprovedDraft(
      String userId,
      String skillName,
      AbstractFilesystem filesystem,
      RuntimeContext context,
      SkillDraftLock.Handle lockHandle,
      Supplier<WriteResult> moveAction) {
    Optional<SkillReviewDecision> maybeDecision = decisionStore.find(skillName, userId);
    if (maybeDecision.isEmpty()) {
      return WriteResult.fail("Skill draft has no review decision");
    }
    SkillReviewDecision decision = maybeDecision.get();
    if (!"APPROVED".equals(decision.status()) || decision.draftHash() == null) {
      return WriteResult.fail("Skill draft is not approved");
    }

    String currentHash;
    try {
      currentHash = new SkillDraftFingerprint(filesystem).computeDraftHash(context, skillName);
    } catch (SkillDraftFingerprintException exception) {
      return WriteResult.fail("Skill draft is unavailable for promotion validation");
    }
    if (!currentHash.equals(decision.draftHash())) {
      return WriteResult.fail("Skill draft changed after review");
    }
    if (!lockHandle.renew()) {
      return WriteResult.fail("Skill draft lock expired before promotion");
    }
    return moveAction.get();
  }
}
