package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.myagent.config.UserScopedFilesystemFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SkillReviewServiceTest {

  private AbstractFilesystem filesystem;
  private SkillReviewDecisionStore decisionStore;
  private UserScopedFilesystemFactory filesystemFactory;
  private SkillDraftFingerprint fingerprint;
  private SkillDraftLock draftLock;
  private SkillDraftLock.Handle lockHandle;
  private SkillReviewService service;

  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    decisionStore = mock(SkillReviewDecisionStore.class);
    fingerprint = mock(SkillDraftFingerprint.class);
    draftLock = mock(SkillDraftLock.class);
    lockHandle = mock(SkillDraftLock.Handle.class);
    when(draftLock.acquire(anyString())).thenReturn(lockHandle);
    when(lockHandle.renew()).thenReturn(true);
    filesystemFactory =
        new UserScopedFilesystemFactory(
            new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore(),
            draftLock,
            mock(SkillPromotionGuard.class));
    service =
        new SkillReviewService(
            filesystem, decisionStore, filesystemFactory, fingerprint, draftLock);
  }

  @Test
  void listReturnsPendingSkillsFromDraftsDirectory() {
    stubListedDraft();
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.empty());

    List<SkillReviewDto> result = service.list("1");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).skillName()).isEqualTo("my-skill");
    assertThat(result.get(0).description()).isEqualTo("My skill description");
    assertThat(result.get(0).status()).isEqualTo("PENDING");
  }

  @Test
  void listStillAcceptsABareDirectoryName() {
    stubListedDraft("my-skill");
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.empty());

    assertThat(service.list("1")).extracting(SkillReviewDto::skillName)
        .containsExactly("my-skill");
  }

  @Test
  void listIgnoresPathsThatAreNotValidDirectDraftChildren() {
    when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(true);
    when(filesystem.ls(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(
            LsResult.success(
                List.of(
                    FileInfo.ofDir("/skills/other/foreign", "2026-07-08T09:00:00"),
                    FileInfo.ofDir(
                        "/skills/_drafts/nested/child", "2026-07-08T09:00:00"),
                    FileInfo.ofDir("/skills/_drafts/..", "2026-07-08T09:00:00"))));

    assertThat(service.list("1")).isEmpty();
  }

  @Test
  void listReadsUsageFromTheRequestedUserOnly() {
    stubListedDraft();
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.empty());
    when(decisionStore.find("my-skill", "2")).thenReturn(Optional.empty());
    SkillUsageStore aliceUsage = filesystemFactory.usageStore("1");
    SkillUsageStore bobUsage = filesystemFactory.usageStore("2");
    aliceUsage.markAgentDraft("my-skill", "alice-session");
    bobUsage.markAgentDraft("my-skill", "bob-session");
    aliceUsage.bumpUse("my-skill");
    bobUsage.bumpUse("my-skill");
    bobUsage.bumpUse("my-skill");

    assertThat(service.list("1").getFirst().useCount()).isEqualTo(1);
    assertThat(service.list("2").getFirst().useCount()).isEqualTo(2);
  }

  @Test
  void listKeepsApprovedStatusWhenDraftHashMatches() {
    stubListedDraft();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "admin",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");

    assertThat(service.list("1").get(0).status()).isEqualTo("APPROVED");
  }

  @Test
  void listShowsPendingWhenDraftChangedAfterApproval() {
    stubListedDraft();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "admin",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v2");

    assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
  }

  @Test
  void listShowsPendingForLegacyDecisionWithoutHash() {
    stubListedDraft();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "admin",
            null,
            List.of("prod"),
            Instant.now(),
            null);
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));

    assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
    verify(fingerprint, never())
        .computeDraftHash(any(RuntimeContext.class), anyString());
  }

  @Test
  void listShowsPendingWhenCurrentDraftCannotBeRead() {
    stubListedDraft();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "admin",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

    assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
  }

  @Test
  void approveStoresDecisionAndReturnsApprovedStatus() {
    stubListedDraft();
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill", "APPROVED", "admin", null, List.of("prod"), now, "hash-v1");
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    when(decisionStore.approve("my-skill", "admin", List.of("prod"), "hash-v1", "1"))
        .thenReturn(decision);
    SkillReviewDto result =
        service.approve("my-skill", new ApproveSkillReviewRequest(List.of("prod")), "1", "admin");

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.skillName()).isEqualTo("my-skill");
    assertThat(result.description()).isEqualTo("My skill description");
    assertThat(result.environments()).containsExactly("prod");
  }

  @Test
  void rejectStoresDecisionAndReturnsRejectedStatus() {
    stubListedDraft();
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill", "REJECTED", "admin", "Too risky", List.of(), now, "hash-v1");
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    when(decisionStore.reject("my-skill", "admin", "Too risky", "hash-v1", "1"))
        .thenReturn(decision);
    SkillReviewDto result =
        service.reject("my-skill", new RejectSkillReviewRequest("Too risky"), "1", "admin");

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.skillName()).isEqualTo("my-skill");
    assertThat(result.description()).isEqualTo("My skill description");
  }

  @Test
  void approveRejectsMissingDraftWithoutSavingDecision() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

    assertThatThrownBy(
            () ->
                service.approve(
                    "my-skill",
                    new ApproveSkillReviewRequest(List.of("prod")),
                    "1",
                    "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(decisionStore);
  }

  @Test
  void rejectRejectsMissingDraftWithoutSavingDecision() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

    assertThatThrownBy(
            () ->
                service.reject(
                    "my-skill", new RejectSkillReviewRequest("risk"), "1", "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(decisionStore);
  }

  @Test
  void approveReportsUnreadableDraftWithoutSavingDecision() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenThrow(
            new SkillDraftFingerprintException(
                SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

    assertThatThrownBy(
            () ->
                service.approve(
                    "my-skill",
                    new ApproveSkillReviewRequest(List.of("prod")),
                    "1",
                    "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    verifyNoInteractions(decisionStore);
  }

  @Test
  void approveRejectsInvalidSkillNameBeforeReadingDraft() {
    assertThatThrownBy(
            () ->
                service.approve(
                    "../evil",
                    new ApproveSkillReviewRequest(List.of("prod")),
                    "1",
                    "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    verifyNoInteractions(fingerprint, decisionStore);
  }

  @Test
  void rejectRejectsInvalidSkillNameBeforeReadingDraft() {
    assertThatThrownBy(
            () ->
                service.reject(
                    "nested/evil",
                    new RejectSkillReviewRequest("risk"),
                    "1",
                    "admin"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    verifyNoInteractions(fingerprint, decisionStore);
  }

  @Test
  void approveStoresTheCurrentDraftHash() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "APPROVED",
            "admin",
            null,
            List.of("prod"),
            Instant.now(),
            "hash-v1");
    when(decisionStore.approve("my-skill", "admin", List.of("prod"), "hash-v1", "1"))
        .thenReturn(decision);

    SkillReviewDto result =
        service.approve(
            "my-skill", new ApproveSkillReviewRequest(List.of("prod")), "1", "admin");

    assertThat(result.status()).isEqualTo("APPROVED");
    ArgumentCaptor<RuntimeContext> context =
        ArgumentCaptor.forClass(RuntimeContext.class);
    verify(fingerprint).computeDraftHash(context.capture(), eq("my-skill"));
    assertThat(context.getValue().getUserId()).isEqualTo("1");
    assertThat(context.getValue().getSessionId()).isEqualTo("skill-review");
    verify(decisionStore)
        .approve("my-skill", "admin", List.of("prod"), "hash-v1", "1");
    org.mockito.InOrder order = inOrder(draftLock, fingerprint, decisionStore, lockHandle);
    order.verify(draftLock).acquire("1");
    order.verify(fingerprint).computeDraftHash(any(RuntimeContext.class), eq("my-skill"));
    order.verify(lockHandle).renew();
    order.verify(decisionStore)
        .approve("my-skill", "admin", List.of("prod"), "hash-v1", "1");
    order.verify(lockHandle).close();
  }

  @Test
  void approveFailsClosedWhenTheDraftLockCannotBeRenewed() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    when(lockHandle.renew()).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.approve(
                    "my-skill",
                    new ApproveSkillReviewRequest(List.of("prod")),
                    "1",
                    "admin"))
        .isInstanceOf(SkillDraftLockException.class)
        .hasMessageContaining("expired");

    verify(decisionStore, never())
        .approve(anyString(), anyString(), any(), anyString(), anyString());
    verify(lockHandle).close();
  }

  @Test
  void rejectStoresTheCurrentDraftHash() {
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v2");
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill",
            "REJECTED",
            "admin",
            "risk",
            List.of(),
            Instant.now(),
            "hash-v2");
    when(decisionStore.reject("my-skill", "admin", "risk", "hash-v2", "1"))
        .thenReturn(decision);

    service.reject("my-skill", new RejectSkillReviewRequest("risk"), "1", "admin");

    verify(decisionStore).reject("my-skill", "admin", "risk", "hash-v2", "1");
    org.mockito.InOrder order = inOrder(draftLock, fingerprint, decisionStore, lockHandle);
    order.verify(draftLock).acquire("1");
    order.verify(fingerprint).computeDraftHash(any(RuntimeContext.class), eq("my-skill"));
    order.verify(lockHandle).renew();
    order.verify(decisionStore).reject("my-skill", "admin", "risk", "hash-v2", "1");
    order.verify(lockHandle).close();
  }

  private void stubListedDraft() {
    stubListedDraft("/skills/_drafts/my-skill");
  }

  private void stubListedDraft(String entryPath) {
    when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(true);
    when(filesystem.ls(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(
            LsResult.success(
                List.of(FileInfo.ofDir(entryPath, "2026-07-08T09:00:00"))));

    String skillMd =
        "---\nname: \"my-skill\"\ndescription: \"My skill description\"\n---\n";
    when(filesystem.exists(
            any(RuntimeContext.class), eq("skills/_drafts/my-skill/SKILL.md")))
        .thenReturn(true);
    when(filesystem.read(
            any(RuntimeContext.class),
            eq("skills/_drafts/my-skill/SKILL.md"),
            eq(0),
            anyInt()))
        .thenReturn(
            ReadResult.success(
                new FileData(
                    skillMd,
                    "utf-8",
                    "2026-07-08T09:00:00",
                    "2026-07-08T09:00:00")));
  }
}
