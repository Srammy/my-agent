package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
  private SkillReviewService service;

  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    decisionStore = mock(SkillReviewDecisionStore.class);
    fingerprint = mock(SkillDraftFingerprint.class);
    filesystemFactory =
        new UserScopedFilesystemFactory(
            new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
    service = new SkillReviewService(filesystem, decisionStore, filesystemFactory, fingerprint);
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
  void listReadsUsageFromTheRequestedUserOnly() {
    stubListedDraft();
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.empty());
    when(decisionStore.find("my-skill", "2")).thenReturn(Optional.empty());
    SkillUsageStore aliceUsage =
        new SkillUsageStore(filesystemFactory.create("1"));
    SkillUsageStore bobUsage =
        new SkillUsageStore(filesystemFactory.create("2"));
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
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill", "APPROVED", "admin", null, List.of("prod"), now, "hash-v1");
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    when(decisionStore.approve("my-skill", "admin", List.of("prod"), "hash-v1", "1"))
        .thenReturn(decision);
    SkillReviewDto result =
        service.approve("my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")), "1");

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.skillName()).isEqualTo("my-skill");
    assertThat(result.environments()).containsExactly("prod");
  }

  @Test
  void rejectStoresDecisionAndReturnsRejectedStatus() {
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision(
            "my-skill", "REJECTED", "admin", "Too risky", List.of(), now, "hash-v1");
    when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
        .thenReturn("hash-v1");
    when(decisionStore.reject("my-skill", "admin", "Too risky", "hash-v1", "1"))
        .thenReturn(decision);
    SkillReviewDto result =
        service.reject("my-skill", new RejectSkillReviewRequest("admin", "Too risky"), "1");

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.skillName()).isEqualTo("my-skill");
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
                    new ApproveSkillReviewRequest("admin", List.of("prod")),
                    "1"))
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
                    "my-skill", new RejectSkillReviewRequest("admin", "risk"), "1"))
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
                    new ApproveSkillReviewRequest("admin", List.of("prod")),
                    "1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
    verifyNoInteractions(decisionStore);
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
            "my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")), "1");

    assertThat(result.status()).isEqualTo("APPROVED");
    ArgumentCaptor<RuntimeContext> context =
        ArgumentCaptor.forClass(RuntimeContext.class);
    verify(fingerprint).computeDraftHash(context.capture(), eq("my-skill"));
    assertThat(context.getValue().getUserId()).isEqualTo("1");
    assertThat(context.getValue().getSessionId()).isEqualTo("skill-review");
    verify(decisionStore)
        .approve("my-skill", "admin", List.of("prod"), "hash-v1", "1");
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

    service.reject("my-skill", new RejectSkillReviewRequest("admin", "risk"), "1");

    verify(decisionStore).reject("my-skill", "admin", "risk", "hash-v2", "1");
  }

  private void stubListedDraft() {
    when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(true);
    when(filesystem.ls(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(
            LsResult.success(
                List.of(FileInfo.ofDir("my-skill", "2026-07-08T09:00:00"))));

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
