package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
  private SkillDraftFingerprint fingerprint;
  private WebApprovalGate gate;
  private RuntimeContext ctx;

  @BeforeEach
  void setUp() {
    decisionStore = mock(SkillReviewDecisionStore.class);
    fingerprint = mock(SkillDraftFingerprint.class);
    gate = new WebApprovalGate(decisionStore, fingerprint);
    ctx = RuntimeContext.builder().userId("test-user").sessionId("test-session").build();
  }

  @Test
  void reviewDefersWhenNoDecisionExists() {
    SkillCandidate candidate = buildCandidate("my-skill");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.empty());

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  }

  @Test
  void reviewDefersWhenDecisionIsApprovedBecausePromotionIsManual() {
    SkillCandidate candidate = buildCandidate("my-skill");
    Instant now = Instant.now();
    SkillReviewDecision storedDecision =
        new SkillReviewDecision(
            "my-skill", "APPROVED", "reviewer1", null, List.of("prod"), now, "hash-v1");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(storedDecision));
    when(fingerprint.computeDraftHash(ctx, "my-skill")).thenReturn("hash-v1");

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  }

  @Test
  void reviewRejectsWhenDecisionIsRejected() {
    SkillCandidate candidate = buildCandidate("my-skill");
    Instant now = Instant.now();
    SkillReviewDecision storedDecision =
        new SkillReviewDecision(
            "my-skill", "REJECTED", "reviewer1", "Too risky", List.of(), now, "hash-v1");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(storedDecision));
    when(fingerprint.computeDraftHash(ctx, "my-skill")).thenReturn("hash-v1");

    SkillPromotionGate.PromotionDecision decision =
        gate.review(candidate, ctx).block();

    assertThat(decision).isInstanceOf(SkillPromotionGate.PromotionDecision.Reject.class);
    SkillPromotionGate.PromotionDecision.Reject reject =
        (SkillPromotionGate.PromotionDecision.Reject) decision;
    assertThat(reject.reason()).isEqualTo("Too risky");
    assertThat(reject.reviewerId()).isEqualTo("reviewer1");
  }

  @Test
  void reviewDefersWhenApprovedDraftChanged() {
    SkillCandidate candidate = buildCandidate("my-skill");
    SkillReviewDecision stored =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "reviewer1",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
    when(fingerprint.computeDraftHash(ctx, "my-skill")).thenReturn("hash-v2");

    assertThat(gate.review(candidate, ctx).block())
        .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  }

  @Test
  void reviewDefersForLegacyDecisionWithoutHash() {
    SkillCandidate candidate = buildCandidate("my-skill");
    SkillReviewDecision stored =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "reviewer1",
            null,
            List.of("prod"),
            Instant.now(),
            null);
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));

    assertThat(gate.review(candidate, ctx).block())
        .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
    verifyNoInteractions(fingerprint);
  }

  @Test
  void reviewDefersWhenCurrentDraftCannotBeRead() {
    SkillCandidate candidate = buildCandidate("my-skill");
    SkillReviewDecision stored =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "reviewer1",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
    when(fingerprint.computeDraftHash(ctx, "my-skill"))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

    assertThat(gate.review(candidate, ctx).block())
        .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  }

  @Test
  void reviewDefersWhenCurrentDraftIsMissing() {
    SkillCandidate candidate = buildCandidate("my-skill");
    SkillReviewDecision stored =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "reviewer1",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
    when(fingerprint.computeDraftHash(ctx, "my-skill"))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

    assertThat(gate.review(candidate, ctx).block())
        .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
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
