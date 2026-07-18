package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillApprovalGuardedFilesystemTest {

  private static final String SKILL_NAME = "reviewer";
  private static final String DRAFT_PATH = "skills/_drafts/" + SKILL_NAME;
  private static final String SKILL_PATH = "skills/" + SKILL_NAME;
  private static final String ORIGINAL_SKILL_MD =
      "---\nname: reviewer\ndescription: Review code\n---\noriginal\n";

  private InMemoryStore store;
  private AbstractFilesystem sharedFilesystem;
  private SkillReviewDecisionStore decisionStore;
  private SkillDraftFingerprint fingerprint;
  private WebApprovalGate gate;
  private AbstractFilesystem aliceFilesystem;
  private RuntimeContext aliceContext;

  @BeforeEach
  void setUp() {
    store = new InMemoryStore();
    sharedFilesystem =
        new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    decisionStore = new SkillReviewDecisionStore(sharedFilesystem);
    fingerprint = new SkillDraftFingerprint(sharedFilesystem);
    gate = new WebApprovalGate(decisionStore, fingerprint);
    aliceFilesystem = guardedFilesystem("101");
    aliceContext = context("101");
  }

  @Test
  void refusesContentChangedAfterTheGateApprovedIt() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    approveCurrentDraft("101");
    assertThat(gate.review(candidate(), aliceContext).block())
        .isInstanceOf(SkillPromotionGate.PromotionDecision.Approve.class);

    assertThat(
            aliceFilesystem
                .edit(
                    RuntimeContext.empty(),
                    DRAFT_PATH + "/SKILL.md",
                    "original",
                    "changed",
                    false)
                .isSuccess())
        .isTrue();
    WriteResult result =
        aliceFilesystem.move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH);

    assertThat(result.isSuccess()).isFalse();
    assertThat(sharedFilesystem.exists(aliceContext, DRAFT_PATH)).isTrue();
    assertThat(sharedFilesystem.exists(aliceContext, SKILL_PATH + "/SKILL.md")).isFalse();
  }

  @Test
  void movesTheExactApprovedDraft() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    approveCurrentDraft("101");

    WriteResult result =
        aliceFilesystem.move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH);

    assertThat(result.isSuccess()).isTrue();
    assertThat(sharedFilesystem.exists(aliceContext, DRAFT_PATH + "/SKILL.md")).isFalse();
    assertThat(sharedFilesystem.exists(aliceContext, SKILL_PATH + "/SKILL.md")).isTrue();
  }

  @Test
  void refusesDirectWritesToFormalSkills() {
    WriteResult result =
        aliceFilesystem.write(
            RuntimeContext.empty(), SKILL_PATH + "/SKILL.md", ORIGINAL_SKILL_MD);

    assertThat(result.isSuccess()).isFalse();
    assertThat(sharedFilesystem.exists(aliceContext, SKILL_PATH + "/SKILL.md")).isFalse();
  }

  @Test
  void refusesMovingAnIndividualDraftFileToFormalSkills() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);

    WriteResult result =
        aliceFilesystem.move(
            RuntimeContext.empty(), DRAFT_PATH + "/SKILL.md", SKILL_PATH + "/SKILL.md");

    assertThat(result.isSuccess()).isFalse();
    assertThat(aliceFilesystem.exists(RuntimeContext.empty(), SKILL_PATH + "/SKILL.md")).isFalse();
  }

  @Test
  void refusesEditingAndDeletingFormalSkills() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    approveCurrentDraft("101");
    assertThat(aliceFilesystem.move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH).isSuccess())
        .isTrue();

    assertThat(
            aliceFilesystem
                .edit(
                    RuntimeContext.empty(),
                    SKILL_PATH + "/SKILL.md",
                    "original",
                    "changed",
                    false)
                .isSuccess())
        .isFalse();
    assertThat(
            aliceFilesystem
                .delete(RuntimeContext.empty(), SKILL_PATH)
                .isSuccess())
        .isFalse();
    assertThat(aliceFilesystem.exists(RuntimeContext.empty(), SKILL_PATH + "/SKILL.md")).isTrue();
  }

  @Test
  void refusesUploadingFilesToFormalSkills() {
    List<FileUploadResponse> result =
        aliceFilesystem.uploadFiles(
            RuntimeContext.empty(),
            List.of(Map.entry(SKILL_PATH + "/SKILL.md", ORIGINAL_SKILL_MD.getBytes())));

    assertThat(result).allMatch(response -> !response.isSuccess());
    assertThat(aliceFilesystem.exists(RuntimeContext.empty(), SKILL_PATH + "/SKILL.md")).isFalse();
  }

  @Test
  void refusesMovingOrdinaryFilesIntoFormalSkills() {
    assertThat(
            aliceFilesystem
                .write(RuntimeContext.empty(), "notes/SKILL.md", ORIGINAL_SKILL_MD)
                .isSuccess())
        .isTrue();

    WriteResult result =
        aliceFilesystem.move(
            RuntimeContext.empty(), "notes/SKILL.md", SKILL_PATH + "/SKILL.md");

    assertThat(result.isSuccess()).isFalse();
    assertThat(aliceFilesystem.exists(RuntimeContext.empty(), SKILL_PATH + "/SKILL.md")).isFalse();
  }

  @Test
  void refusesARejectedDecision() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    String hash = fingerprint.computeDraftHash(aliceContext, SKILL_NAME);
    decisionStore.reject(SKILL_NAME, "admin", "unsafe", hash, "101");

    assertThat(
            aliceFilesystem
                .move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH)
                .isSuccess())
        .isFalse();
  }

  @Test
  void refusesALegacyApprovalWithoutAFingerprint() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    String legacyDecision =
        """
        {"skillName":"reviewer","status":"APPROVED","reviewerId":"admin",
         "reason":null,"environments":["prod"],"decidedAt":"2026-07-16T00:00:00Z"}
        """;
    assertThat(
            sharedFilesystem
                .write(aliceContext, "skill-reviews/reviewer.json", legacyDecision)
                .isSuccess())
        .isTrue();

    assertThat(
            aliceFilesystem
                .move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH)
                .isSuccess())
        .isFalse();
  }

  @Test
  void refusesAnApprovalBelongingToAnotherUser() {
    writeDraft(aliceFilesystem, ORIGINAL_SKILL_MD);
    approveCurrentDraft("101");
    AbstractFilesystem bobFilesystem = guardedFilesystem("102");
    writeDraft(bobFilesystem, ORIGINAL_SKILL_MD);

    assertThat(
            bobFilesystem
                .move(RuntimeContext.empty(), DRAFT_PATH, SKILL_PATH)
                .isSuccess())
        .isFalse();
    assertThat(sharedFilesystem.exists(context("102"), DRAFT_PATH)).isTrue();
  }

  @Test
  void ordinaryWritesDoNotAcquireTheDraftLock() {
    AbstractFilesystem delegate = mock(AbstractFilesystem.class);
    SkillDraftLock lock = mock(SkillDraftLock.class);
    SkillPromotionGuard promotionGuard = mock(SkillPromotionGuard.class);
    AbstractFilesystem filesystem =
        new SkillApprovalGuardedFilesystem(delegate, "101", lock, promotionGuard);
    RuntimeContext context = RuntimeContext.empty();
    when(delegate.write(context, "notes/readme.md", "content"))
        .thenReturn(WriteResult.ok("notes/readme.md"));

    assertThat(filesystem.write(context, "notes/readme.md", "content").isSuccess()).isTrue();

    verifyNoInteractions(lock);
    verify(delegate).write(context, "notes/readme.md", "content");
  }

  @Test
  void draftWritesAcquireAndReleaseTheDraftLock() {
    AbstractFilesystem delegate = mock(AbstractFilesystem.class);
    SkillDraftLock lock = mock(SkillDraftLock.class);
    SkillDraftLock.Handle handle = mock(SkillDraftLock.Handle.class);
    SkillPromotionGuard promotionGuard = mock(SkillPromotionGuard.class);
    AbstractFilesystem filesystem =
        new SkillApprovalGuardedFilesystem(delegate, "101", lock, promotionGuard);
    RuntimeContext context = RuntimeContext.empty();
    when(lock.acquire("101")).thenReturn(handle);
    when(delegate.write(context, DRAFT_PATH + "/SKILL.md", ORIGINAL_SKILL_MD))
        .thenReturn(WriteResult.ok(DRAFT_PATH + "/SKILL.md"));

    assertThat(
            filesystem
                .write(context, DRAFT_PATH + "/SKILL.md", ORIGINAL_SKILL_MD)
                .isSuccess())
        .isTrue();

    verify(lock).acquire("101");
    verify(handle).close();
  }

  private AbstractFilesystem guardedFilesystem(String userId) {
    AbstractFilesystem delegate = new RemoteFilesystem(store, List.of(userId));
    return new SkillApprovalGuardedFilesystem(
        delegate,
        userId,
        new BaseStoreSkillDraftLock(store),
        new SkillPromotionGuard(decisionStore));
  }

  private void writeDraft(AbstractFilesystem filesystem, String content) {
    WriteResult result =
        filesystem.write(
            RuntimeContext.empty(), DRAFT_PATH + "/SKILL.md", content);
    assertThat(result.isSuccess()).isTrue();
  }

  private void approveCurrentDraft(String userId) {
    RuntimeContext context = context(userId);
    assertThat(sharedFilesystem.exists(context, DRAFT_PATH)).isTrue();
    String hash = fingerprint.computeDraftHash(context, SKILL_NAME);
    decisionStore.approve(SKILL_NAME, "admin", List.of("prod"), hash, userId);
  }

  private static RuntimeContext context(String userId) {
    return RuntimeContext.builder().userId(userId).sessionId("test-session").build();
  }

  private static SkillCandidate candidate() {
    return new SkillCandidate(
        SKILL_NAME,
        "Review code",
        ORIGINAL_SKILL_MD,
        List.of(),
        SkillUsageRecord.defaults(),
        null,
        List.of());
  }
}
