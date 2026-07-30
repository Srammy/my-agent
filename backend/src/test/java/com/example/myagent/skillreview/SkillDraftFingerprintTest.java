package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
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
    when(filesystem.ls(alice, "skills/_drafts/my-skill"))
        .thenReturn(LsResult.success(List.of()));

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

  @Test
  void wrapsExistsExceptionAsReadFailure() {
    IllegalStateException filesystemFailure = new IllegalStateException("redis unavailable");
    when(filesystem.exists(alice, "skills/_drafts/my-skill"))
        .thenThrow(filesystemFailure);

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasCause(filesystemFailure)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
  }

  @Test
  void wrapsListExceptionAsReadFailure() {
    IllegalStateException filesystemFailure = new IllegalStateException("redis unavailable");
    when(filesystem.exists(alice, "skills/_drafts/my-skill")).thenReturn(true);
    when(filesystem.exists(alice, "skills/_drafts/my-skill/SKILL.md")).thenReturn(true);
    when(filesystem.ls(alice, "/skills/_drafts/my-skill"))
        .thenThrow(filesystemFailure);

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasCause(filesystemFailure)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
  }

  @Test
  void wrapsReadExceptionAsReadFailure() {
    IllegalStateException filesystemFailure = new IllegalStateException("redis unavailable");
    String md = "---\nname: my-skill\ndescription: test\n---\n";
    stubDraft(alice, md, "echo one");
    when(filesystem.read(alice, "skills/_drafts/my-skill/SKILL.md", 0, 0))
        .thenThrow(filesystemFailure);

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasCause(filesystemFailure)
        .satisfies(
            error ->
                assertThat(((SkillDraftFingerprintException) error).reason())
                    .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
  }

  @Test
  void readsFullPathsReturnedByRemoteFilesystem() {
    InMemoryStore store = new InMemoryStore();
    AbstractFilesystem fixedUserFilesystem = new RemoteFilesystem(store, List.of("1"));
    AbstractFilesystem userContextFilesystem =
        new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    String skillMd = "---\nname: my-skill\ndescription: test\n---\n";
    fixedUserFilesystem.uploadFiles(
        RuntimeContext.empty(),
        List.of(
            new AbstractMap.SimpleImmutableEntry<>(
                "skills/_drafts/my-skill/SKILL.md",
                skillMd.getBytes(StandardCharsets.UTF_8)),
            new AbstractMap.SimpleImmutableEntry<>(
                "skills/_drafts/my-skill/scripts/run.sh",
                "echo one".getBytes(StandardCharsets.UTF_8))));

    assertThat(new SkillDraftFingerprint(userContextFilesystem).computeDraftHash(alice, "my-skill"))
        .isNotBlank();
  }

  @Test
  void rejectsDraftsDeeperThanTheMaximumDepth() {
    String root = "skills/_drafts/my-skill";
    when(filesystem.exists(alice, root)).thenReturn(true);
    when(filesystem.exists(alice, root + "/SKILL.md")).thenReturn(true);
    when(filesystem.ls(alice, root)).thenReturn(
        LsResult.success(List.of(
            FileInfo.ofFile("SKILL.md", 10, "now"),
            FileInfo.ofDir("d1", "now"))));

    String directory = root + "/d1";
    for (int depth = 1; depth <= 16; depth++) {
      when(filesystem.ls(alice, directory)).thenReturn(
          LsResult.success(List.of(FileInfo.ofDir("d" + (depth + 1), "now"))));
      directory += "/d" + (depth + 1);
    }

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasMessageContaining("maximum depth");
  }

  @Test
  void rejectsDraftsWithMoreThanTheMaximumFileCount() {
    String root = "skills/_drafts/my-skill";
    when(filesystem.exists(alice, root)).thenReturn(true);
    when(filesystem.exists(alice, root + "/SKILL.md")).thenReturn(true);
    List<FileInfo> entries = new java.util.ArrayList<>();
    entries.add(FileInfo.ofFile("SKILL.md", 10, "now"));
    for (int index = 1; index <= 100; index++) {
      entries.add(FileInfo.ofFile("references/" + index + ".md", 10, "now"));
    }
    when(filesystem.ls(alice, root)).thenReturn(LsResult.success(entries));

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasMessageContaining("maximum file count");
  }

  @Test
  void rejectsDraftsWhoseDeclaredContentExceedsTheMaximumTotalBytes() {
    String root = "skills/_drafts/my-skill";
    when(filesystem.exists(alice, root)).thenReturn(true);
    when(filesystem.exists(alice, root + "/SKILL.md")).thenReturn(true);
    when(filesystem.ls(alice, root)).thenReturn(LsResult.success(List.of(
        FileInfo.ofFile("SKILL.md", 10, "now"),
        FileInfo.ofFile("references/large.md", 1_048_576, "now"))));

    assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
        .isInstanceOf(SkillDraftFingerprintException.class)
        .hasMessageContaining("maximum total bytes");
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
