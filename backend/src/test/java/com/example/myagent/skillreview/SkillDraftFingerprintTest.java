package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillDraftFingerprintTest {

  private AbstractFilesystem filesystem;
  private SkillDraftFingerprint fingerprint;
  private RuntimeContext alice;

  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    fingerprint = new SkillDraftFingerprint(filesystem);
    alice = RuntimeContext.builder().userId("1").sessionId("skill-review").build();
  }

  @Test
  void hashIsStableWhenDirectoryEnumerationOrderChanges() {
    String md = "---\nname: my-skill\ndescription: test\n---\n";
    stubDraft(alice, md, "echo one");
    when(filesystem.ls(alice, "skills/_drafts/my-skill"))
        .thenReturn(
            LsResult.success(
                List.of(
                    FileInfo.ofDir("scripts", "now"),
                    FileInfo.ofFile("SKILL.md", md.length(), "now"))),
            LsResult.success(
                List.of(
                    FileInfo.ofFile("SKILL.md", md.length(), "now"),
                    FileInfo.ofDir("scripts", "now"))));

    assertThat(fingerprint.computeDraftHash(alice, "my-skill"))
        .isEqualTo(fingerprint.computeDraftHash(alice, "my-skill"));
  }

  @Test
  void hashChangesWhenSkillMarkdownChanges() {
    String first = "---\nname: my-skill\ndescription: first\n---\n";
    String second = "---\nname: my-skill\ndescription: second\n---\n";
    stubDraft(alice, first, "echo one");
    when(filesystem.read(alice, "skills/_drafts/my-skill/SKILL.md", 0, 0))
        .thenReturn(
            ReadResult.success(new FileData(first, "utf-8", "now", "now")),
            ReadResult.success(new FileData(second, "utf-8", "now", "now")));

    assertThat(fingerprint.computeDraftHash(alice, "my-skill"))
        .isNotEqualTo(fingerprint.computeDraftHash(alice, "my-skill"));
  }

  @Test
  void hashChangesWhenSupportFileChanges() {
    String md = "---\nname: my-skill\ndescription: test\n---\n";
    stubDraft(alice, md, "echo one");
    when(filesystem.read(alice, "skills/_drafts/my-skill/scripts/run.sh", 0, 0))
        .thenReturn(
            ReadResult.success(new FileData("echo one", "utf-8", "now", "now")),
            ReadResult.success(new FileData("echo two", "utf-8", "now", "now")));

    assertThat(fingerprint.computeDraftHash(alice, "my-skill"))
        .isNotEqualTo(fingerprint.computeDraftHash(alice, "my-skill"));
  }

  @Test
  void sameNamedDraftsUseTheProvidedUserContext() {
    RuntimeContext bob =
        RuntimeContext.builder().userId("2").sessionId("skill-review").build();
    String md = "---\nname: my-skill\ndescription: test\n---\n";
    stubDraft(alice, md, "echo alice");
    stubDraft(bob, md, "echo bob");

    assertThat(fingerprint.computeDraftHash(alice, "my-skill"))
        .isNotEqualTo(fingerprint.computeDraftHash(bob, "my-skill"));
    verify(filesystem, atLeastOnce())
        .read(alice, "skills/_drafts/my-skill/SKILL.md", 0, 0);
    verify(filesystem, atLeastOnce())
        .read(bob, "skills/_drafts/my-skill/SKILL.md", 0, 0);
  }

  @Test
  void missingSkillMarkdownIsReportedAsNotFound() {
    when(filesystem.exists(alice, "skills/_drafts/my-skill")).thenReturn(true);
    when(filesystem.exists(alice, "skills/_drafts/my-skill/SKILL.md")).thenReturn(false);

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.NOT_FOUND));
  }

  @Test
  void failedFileReadDoesNotProducePartialHash() {
    String md = "---\nname: my-skill\ndescription: test\n---\n";
    stubDraft(alice, md, "echo one");
    when(filesystem.read(alice, "skills/_drafts/my-skill/scripts/run.sh", 0, 0))
        .thenReturn(ReadResult.fail("redis unavailable"));

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
  }

  @Test
  void listingThatOmitsSkillMarkdownIsReportedAsReadFailure() {
    when(filesystem.exists(alice, "skills/_drafts/my-skill")).thenReturn(true);
    when(filesystem.exists(alice, "skills/_drafts/my-skill/SKILL.md")).thenReturn(true);
    when(filesystem.ls(alice, "skills/_drafts/my-skill"))
        .thenReturn(LsResult.success(List.of()));

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
  }

  private void stubDraft(RuntimeContext ctx, String skillMd, String script) {
    when(filesystem.exists(ctx, "skills/_drafts/my-skill")).thenReturn(true);
    when(filesystem.exists(ctx, "skills/_drafts/my-skill/SKILL.md")).thenReturn(true);
    when(filesystem.ls(ctx, "skills/_drafts/my-skill"))
        .thenReturn(
            LsResult.success(
                List.of(
                    FileInfo.ofDir("scripts", "now"),
                    FileInfo.ofFile("SKILL.md", skillMd.length(), "now"))));
    when(filesystem.ls(ctx, "skills/_drafts/my-skill/scripts"))
        .thenReturn(
            LsResult.success(
                List.of(FileInfo.ofFile("run.sh", script.length(), "now"))));
    when(filesystem.read(ctx, "skills/_drafts/my-skill/SKILL.md", 0, 0))
        .thenReturn(
            ReadResult.success(new FileData(skillMd, "utf-8", "now", "now")));
    when(filesystem.read(ctx, "skills/_drafts/my-skill/scripts/run.sh", 0, 0))
        .thenReturn(ReadResult.success(new FileData(script, "utf-8", "now", "now")));
  }
}
