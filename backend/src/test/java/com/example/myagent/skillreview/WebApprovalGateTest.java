package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebApprovalGateTest {

  private SkillReviewDecisionStore decisionStore;
  private WebApprovalGate gate;
  private RuntimeContext ctx;

  @BeforeEach
  void setUp() {
    decisionStore = mock(SkillReviewDecisionStore.class);
    gate = new WebApprovalGate(decisionStore);
    ctx = RuntimeContext.builder().userId("test-user").sessionId("test-session").build();
  }

  @Test
  void reviewDefersWhenNoDecisionExists() {
    SkillCandidate candidate = buildCandidate("my-skill");
    when(decisionStore.find("my-skill")).thenReturn(Optional.empty());

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  }

  @Test
  void reviewApprovesWhenDecisionIsApproved() {
    SkillCandidate candidate = buildCandidate("my-skill");
    Instant now = Instant.now();
    SkillReviewDecision storedDecision =
        new SkillReviewDecision("my-skill", "APPROVED", "reviewer1", null, List.of("prod"), now);
    when(decisionStore.find("my-skill")).thenReturn(Optional.of(storedDecision));

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);
    SkillPromotionGate.PromotionDecision.Approve approve =
        (SkillPromotionGate.PromotionDecision.Approve) decision;
    assertThat(approve.reviewerId()).isEqualTo("reviewer1");
    assertThat(approve.targetEnvironments()).containsExactly("prod");
    assertThat(approve.decidedAt()).isEqualTo(now);
  }

  @Test
  void reviewRejectsWhenDecisionIsRejected() {
    SkillCandidate candidate = buildCandidate("my-skill");
    Instant now = Instant.now();
    SkillReviewDecision storedDecision =
        new SkillReviewDecision("my-skill", "REJECTED", "reviewer1", "Too risky", List.of(), now);
    when(decisionStore.find("my-skill")).thenReturn(Optional.of(storedDecision));

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Reject.class);
    SkillPromotionGate.PromotionDecision.Reject reject =
        (SkillPromotionGate.PromotionDecision.Reject) decision;
    assertThat(reject.reason()).isEqualTo("Too risky");
    assertThat(reject.reviewerId()).isEqualTo("reviewer1");
  }

  private static SkillCandidate buildCandidate(String name) {
    return new SkillCandidate(
        name,
        "Test description",
        "---\nname: " + name + "\ndescription: Test description\n---\n",
        List.of(),
        SkillUsageRecord.defaults(),
        null,
        List.of());
  }
}
