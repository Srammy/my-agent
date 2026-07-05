package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.myagent.config.AgentProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillMaterializerTest {

  @TempDir Path tempDir;

  @Mock private SkillService skillService;

  @Test
  void materializeForUserWritesMergedSkillsAndCleansStaleDirectories() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            java.util.List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/checklist.md", "user checklist"),
                    file("scripts/run.sh", "echo user")),
                snapshot(
                    12L,
                    "ops-helper",
                    SkillService.OWNER_TYPE_SYSTEM,
                    LocalDateTime.of(2026, 7, 4, 9, 0),
                    file("SKILL.md", "---\nname: ops-helper\ndescription: system\n---\n"),
                    file("assets/icon.txt", "icon"))));

    Path staleDir = cacheDir.resolve("7").resolve("stale-skill");
    Files.createDirectories(staleDir);
    Files.writeString(staleDir.resolve("SKILL.md"), "stale", StandardCharsets.UTF_8);

    Path materializedRoot = materializer.materializeForUser(7L);

    assertThat(materializedRoot).isEqualTo(cacheDir.resolve("7").toAbsolutePath().normalize());
    assertThat(Files.readString(materializedRoot.resolve("mysql-helper/SKILL.md")))
        .contains("description: user");
    assertThat(Files.readString(materializedRoot.resolve("mysql-helper/references/checklist.md")))
        .isEqualTo("user checklist");
    assertThat(Files.readString(materializedRoot.resolve("mysql-helper/scripts/run.sh")))
        .isEqualTo("echo user");
    assertThat(Files.readString(materializedRoot.resolve("ops-helper/assets/icon.txt")))
        .isEqualTo("icon");
    assertThat(materializedRoot.resolve("stale-skill")).doesNotExist();
  }

  @Test
  void materializeForUserRefreshesWhenSkillFilesChangeWithoutSkillTimestamp() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/foo.md", "alpha"))),
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/foo.md", "beta"))));

    Path materializedRoot = materializer.materializeForUser(7L);
    assertThat(Files.readString(materializedRoot.resolve("mysql-helper/references/foo.md")))
        .isEqualTo("alpha");

    materializer.materializeForUser(7L);
    assertThat(Files.readString(materializedRoot.resolve("mysql-helper/references/foo.md")))
        .isEqualTo("beta");
  }

  @Test
  void materializeForUserRemovesDeletedSkillFilesWithoutSkillTimestampChange() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("scripts/run.sh", "echo one"))),
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"))));

    Path materializedRoot = materializer.materializeForUser(7L);
    assertThat(materializedRoot.resolve("mysql-helper/scripts/run.sh")).exists();

    materializer.materializeForUser(7L);
    assertThat(materializedRoot.resolve("mysql-helper/scripts/run.sh")).doesNotExist();
  }

  @Test
  void materializeForUserRejectsMaliciousFilePathFromDatabase() {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            java.util.List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/../../escape.md", "boom"))));

    assertThatThrownBy(() -> materializer.materializeForUser(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("references/../../escape.md");
    assertThat(cacheDir.resolve("escape.md")).doesNotExist();
  }

  @Test
  void materializeForUserKeepsExistingCacheWhenUpdatedSkillIsMissingSkillMarkdown() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/foo.md", "alpha"))),
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 11, 0),
                    file("references/foo.md", "beta"))));

    Path materializedRoot = materializer.materializeForUser(7L);
    Path skillRoot = materializedRoot.resolve("mysql-helper");
    assertThat(Files.readString(skillRoot.resolve("references/foo.md"))).isEqualTo("alpha");

    assertThatThrownBy(() -> materializer.materializeForUser(7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing SKILL.md");
    assertThat(Files.readString(skillRoot.resolve("references/foo.md"))).isEqualTo("alpha");
    assertThat(skillRoot.resolve("SKILL.md")).exists();
  }

  @Test
  void materializeForUserKeepsExistingCacheWhenWritingUpdatedSkillFails() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/foo.md", "alpha"))),
            List.of(
                snapshot(
                    11L,
                    "mysql-helper",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 11, 0),
                    file("SKILL.md", "---\nname: mysql-helper\ndescription: user\n---\n"),
                    file("references/conflict", "conflict"),
                    file("references/conflict/run.md", "echo broken"))));

    Path materializedRoot = materializer.materializeForUser(7L);
    Path skillRoot = materializedRoot.resolve("mysql-helper");
    assertThat(Files.readString(skillRoot.resolve("references/foo.md"))).isEqualTo("alpha");

    assertThatThrownBy(() -> materializer.materializeForUser(7L))
        .isInstanceOf(UncheckedIOException.class);
    assertThat(Files.readString(skillRoot.resolve("references/foo.md"))).isEqualTo("alpha");
    assertThat(skillRoot.resolve("references/conflict")).doesNotExist();
  }

  @Test
  void materializeForUserSanitizesWindowsUnsafeDirectoryNamesIntoCacheRoot() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    11L,
                    "CON?. ",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: con\ndescription: user\n---\n")),
                snapshot(
                    12L,
                    "LPT1",
                    SkillService.OWNER_TYPE_SYSTEM,
                    LocalDateTime.of(2026, 7, 4, 10, 5),
                    file("SKILL.md", "---\nname: lpt\ndescription: system\n---\n"))));

    Path materializedRoot = materializer.materializeForUser(7L);
    List<Path> skillDirectories;
    try (var children = Files.list(materializedRoot)) {
      skillDirectories = children.filter(Files::isDirectory).toList();
    }

    assertThat(skillDirectories).hasSize(2);
    assertThat(skillDirectories)
        .allSatisfy(
            path -> {
              assertThat(path.normalize()).startsWith(materializedRoot.normalize());
              assertThat(path.getFileName().toString()).doesNotContainAnyWhitespaces();
              assertThat(path.getFileName().toString())
                  .doesNotContain("?")
                  .doesNotContain("*")
                  .doesNotContain("<")
                  .doesNotContain(">")
                  .doesNotContain("|");
              assertThat(path.getFileName().toString()).doesNotEndWith(".");
              assertThat(path.getFileName().toString()).doesNotEndWith(" ");
            });
    assertThat(skillDirectories)
        .extracting(path -> path.getFileName().toString())
        .contains("CON_--11", "system-12");
  }

  @Test
  void materializeForUserFallsBackForWindowsReservedBaseNamesWithExtensions() throws IOException {
    Path cacheDir = tempDir.resolve("skill-cache");
    SkillMaterializer materializer = new SkillMaterializer(skillService, properties(cacheDir));

    when(skillService.listEnabledSkillsForUser(7L))
        .thenReturn(
            List.of(
                snapshot(
                    13L,
                    "CON.txt",
                    SkillService.OWNER_TYPE_USER,
                    LocalDateTime.of(2026, 7, 4, 10, 0),
                    file("SKILL.md", "---\nname: con\ndescription: user\n---\n")),
                snapshot(
                    14L,
                    "LPT1.foo",
                    SkillService.OWNER_TYPE_SYSTEM,
                    LocalDateTime.of(2026, 7, 4, 10, 5),
                    file("SKILL.md", "---\nname: lpt\ndescription: system\n---\n"))));

    Path materializedRoot = materializer.materializeForUser(7L);

    assertThat(materializedRoot.resolve("user-13/SKILL.md")).exists();
    assertThat(materializedRoot.resolve("system-14/SKILL.md")).exists();
    assertThat(materializedRoot.resolve("CON.txt--13")).doesNotExist();
    assertThat(materializedRoot.resolve("LPT1.foo--14")).doesNotExist();
  }

  private static SkillService.MaterializedSkill snapshot(
      Long skillId,
      String name,
      String ownerType,
      LocalDateTime updatedAt,
      SkillFileEntity... files) {
    return new SkillService.MaterializedSkill(
        skillId, name, ownerType, updatedAt, java.util.List.of(files));
  }

  private static SkillFileEntity file(String path, String content) {
    SkillFileEntity entity = new SkillFileEntity();
    entity.setPath(path);
    entity.setContent(content);
    return entity;
  }

  private static AgentProperties properties(Path cacheDir) {
    return new AgentProperties(
        new AgentProperties.Deployment("local"),
        new AgentProperties.AgentScope(false),
        new AgentProperties.Model("dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
        new AgentProperties.StateStore(
            "redis", new AgentProperties.StateStore.Redis("redis://localhost:6379", "myagent:")),
        new AgentProperties.Skill("mysql", cacheDir.toString()),
        new AgentProperties.Permission("DEFAULT"),
        new AgentProperties.Tools(false, false, false, false));
  }
}
