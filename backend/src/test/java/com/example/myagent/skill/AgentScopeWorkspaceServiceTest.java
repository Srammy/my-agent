package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentScopeWorkspaceServiceTest {

  private static final CurrentUser USER = new CurrentUser(7L, "alice", "USER");

  private final Map<String, StoredFile> files = new LinkedHashMap<>();

  private AgentScopeWorkspaceService service;

  @BeforeEach
  void setUp() {
    service = new AgentScopeWorkspaceService(new FakeFilesystem(files));
  }

  @Test
  void createSkillWritesSkillMarkdownToWorkspace() {
    service.createSkill(USER, new SkillCreateRequest("java-helper", "Java helper"));

    assertThat(files.get("skills/java-helper/SKILL.md").content())
        .contains("name: \"java-helper\"")
        .contains("description: \"Java helper\"");
  }

  @Test
  void listSkillsReadsWorkspaceSkillMarkdown() {
    files.put(
        "skills/java-helper/SKILL.md",
        new StoredFile("---\nname: java-helper\ndescription: Java helper\n---\n", "2026-07-08T09:30:00"));

    assertThat(service.listSkills(USER))
        .extracting(SkillDto::name)
        .containsExactly("java-helper");
  }

  @Test
  void updateSkillRenamesWorkspaceDirectoryWhenNameChanges() {
    files.put(
        "skills/java-helper/SKILL.md",
        new StoredFile("---\nname: java-helper\ndescription: Java helper\n---\n", "2026-07-08T09:30:00"));
    files.put(
        "skills/java-helper/references/guide.md",
        new StoredFile("guide", "2026-07-08T09:31:00"));

    SkillDto updated =
        service.updateSkill(USER, "java-helper", new SkillCreateRequest("java-pro", "Java pro helper"));

    assertThat(updated.name()).isEqualTo("java-pro");
    assertThat(files).doesNotContainKey("skills/java-helper/SKILL.md");
    assertThat(files).containsKey("skills/java-pro/SKILL.md");
    assertThat(files).containsKey("skills/java-pro/references/guide.md");
  }

  @Test
  void listFilesReturnsNestedWorkspaceFiles() {
    files.put(
        "skills/java-helper/SKILL.md",
        new StoredFile("---\nname: java-helper\ndescription: Java helper\n---\n", "2026-07-08T09:30:00"));
    files.put(
        "skills/java-helper/references/checklist.md",
        new StoredFile("check", "2026-07-08T09:31:00"));

    assertThat(service.listFiles(USER, "java-helper"))
        .extracting(SkillFileDto::path)
        .containsExactly("SKILL.md", "references/checklist.md");
  }

  @Test
  void deleteFileRejectsSkillMarkdown() {
    assertThatThrownBy(() -> service.deleteFile(USER, "java-helper", "SKILL.md"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  private record StoredFile(String content, String modifiedAt) {}

  private static final class FakeFilesystem implements AbstractFilesystem {

    private final Map<String, StoredFile> files;

    private FakeFilesystem(Map<String, StoredFile> files) {
      this.files = files;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
      String prefix = normalizeDirectory(path);
      if (!prefix.isEmpty() && !hasDirectory(prefix) && !files.containsKey(stripTrailingSlash(prefix))) {
        return LsResult.fail("Path not found: " + path);
      }

      Map<String, FileInfo> entries = new LinkedHashMap<>();
      for (Entry<String, StoredFile> entry : files.entrySet()) {
        if (!entry.getKey().startsWith(prefix)) {
          continue;
        }
        String remainder = entry.getKey().substring(prefix.length());
        if (remainder.isEmpty()) {
          continue;
        }
        int separator = remainder.indexOf('/');
        if (separator < 0) {
          entries.put(
              remainder,
              FileInfo.ofFile(remainder, entry.getValue().content().length(), entry.getValue().modifiedAt()));
        } else {
          String directory = remainder.substring(0, separator);
          entries.putIfAbsent(directory, FileInfo.ofDir(directory, entry.getValue().modifiedAt()));
        }
      }
      return LsResult.success(
          entries.values().stream().sorted(Comparator.comparing(FileInfo::path)).toList());
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String path, int offset, int limit) {
      StoredFile file = files.get(path);
      if (file == null) {
        return ReadResult.fail("Path not found: " + path);
      }
      return ReadResult.success(new FileData(file.content(), "utf-8", file.modifiedAt(), file.modifiedAt()));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String path, String content) {
      files.put(path, new StoredFile(content, LocalDateTime.now().toString()));
      return WriteResult.ok(path);
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
      String filePath = stripTrailingSlash(path);
      if (files.remove(filePath) != null) {
        return WriteResult.ok(filePath);
      }
      String prefix = normalizeDirectory(path);
      List<String> deleted = new ArrayList<>();
      for (String existing : List.copyOf(files.keySet())) {
        if (existing.startsWith(prefix)) {
          deleted.add(existing);
          files.remove(existing);
        }
      }
      return deleted.isEmpty() ? WriteResult.fail("Path not found: " + path) : WriteResult.ok(filePath);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String source, String destination) {
      String sourceFile = stripTrailingSlash(source);
      if (files.containsKey(sourceFile)) {
        StoredFile file = files.remove(sourceFile);
        files.put(stripTrailingSlash(destination), file);
        return WriteResult.ok(destination);
      }
      String sourcePrefix = normalizeDirectory(source);
      String destinationPrefix = normalizeDirectory(destination);
      List<Entry<String, StoredFile>> moved = new ArrayList<>();
      for (Entry<String, StoredFile> entry : List.copyOf(files.entrySet())) {
        if (entry.getKey().startsWith(sourcePrefix)) {
          moved.add(entry);
        }
      }
      if (moved.isEmpty()) {
        return WriteResult.fail("Path not found: " + source);
      }
      for (Entry<String, StoredFile> entry : moved) {
        files.remove(entry.getKey());
        files.put(destinationPrefix + entry.getKey().substring(sourcePrefix.length()), entry.getValue());
      }
      return WriteResult.ok(destination);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
      String filePath = stripTrailingSlash(path);
      return files.containsKey(filePath) || hasDirectory(normalizeDirectory(path));
    }

    @Override
    public EditResult edit(RuntimeContext runtimeContext, String path, String oldText, String newText, boolean replaceAll) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String query, String include, String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext, List<Entry<String, byte[]>> files) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
      throw new UnsupportedOperationException();
    }

    private boolean hasDirectory(String prefix) {
      return files.keySet().stream().anyMatch(path -> path.startsWith(prefix));
    }

    private static String normalizeDirectory(String path) {
      String normalized = stripTrailingSlash(path);
      return normalized.isEmpty() ? "" : normalized + "/";
    }

    private static String stripTrailingSlash(String path) {
      if (path == null || path.isBlank()) {
        return "";
      }
      return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
  }
}
