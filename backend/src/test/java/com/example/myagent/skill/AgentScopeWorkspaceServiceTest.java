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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentScopeWorkspaceServiceTest {

  private static final CurrentUser ALICE = new CurrentUser(1L, "alice", "USER");
  private static final CurrentUser BOB   = new CurrentUser(2L, "bob",   "USER");

  private AgentScopeWorkspaceService service;

  @BeforeEach
  void setUp() {
    service = new AgentScopeWorkspaceService(new IsolatedFakeFilesystem());
  }

  @Test
  void listSkillsReturnsEmptyForNewUser() {
    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void createSkillAppearsInList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    List<SkillDto> skills = service.listSkills(ALICE);
    assertThat(skills).hasSize(1);
    assertThat(skills.get(0).name()).isEqualTo("java-helper");
    assertThat(skills.get(0).description()).isEqualTo("Java helper");
  }

  @Test
  void usersAreIsolated() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThat(service.listSkills(BOB)).isEmpty();
  }

  @Test
  void deleteSkillRemovesFromList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));
    service.deleteSkill(ALICE, "java-helper");

    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void deleteNonExistentSkillThrows404() {
    assertThatThrownBy(() -> service.deleteSkill(ALICE, "missing"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createSkillRejectsMissingSkillMd() {
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createSkillRejectsInvalidSkillMd() {
    FilePart badPart = fakeFilePart("SKILL.md", "---\ndescription: no name here\n---\n");
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of(badPart)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createDuplicateSkillThrows409() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThatThrownBy(
            () -> service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }

  private static FilePart skillMdPart(String name, String description) {
    String content = "---\nname: " + name + "\ndescription: " + description + "\n---\n";
    return fakeFilePart("SKILL.md", content);
  }

  private static FilePart fakeFilePart(String filename, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new FilePart() {
      @Override public String name() { return filename; }
      @Override public HttpHeaders headers() { return new HttpHeaders(); }
      @Override public Flux<DataBuffer> content() {
        DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
        return Flux.just(buf);
      }
      @Override public String filename() { return filename; }
      @Override public Mono<Void> transferTo(Path dest) {
        return Mono.error(new UnsupportedOperationException());
      }
    };
  }

  /**
   * In-memory filesystem that isolates data by RuntimeContext.userId so that
   * user-isolation tests work correctly.
   */
  private static final class IsolatedFakeFilesystem implements AbstractFilesystem {

    // key = "userId::path"
    private final Map<String, String> store = new LinkedHashMap<>();

    private String key(RuntimeContext ctx, String path) {
      return ctx.getUserId() + "::" + normalize(path);
    }

    private String normalize(String path) {
      if (path == null) return "";
      String p = path.replace('\\', '/');
      // strip leading slash added by RemoteFilesystem normalizer
      while (p.startsWith("/")) p = p.substring(1);
      return p;
    }

    private String prefix(RuntimeContext ctx, String path) {
      return ctx.getUserId() + "::" + normalize(path) + "/";
    }

    @Override
    public WriteResult write(RuntimeContext ctx, String path, String content) {
      store.put(key(ctx, path), content);
      return WriteResult.ok(path);
    }

    @Override
    public ReadResult read(RuntimeContext ctx, String path, int offset, int limit) {
      String val = store.get(key(ctx, path));
      if (val == null) return ReadResult.fail("Not found: " + path);
      String sliced = offset < val.length()
          ? val.substring(offset, Math.min(val.length(), offset + limit))
          : "";
      return ReadResult.success(new FileData(sliced, "utf-8",
          LocalDateTime.now().toString(), LocalDateTime.now().toString()));
    }

    @Override
    public boolean exists(RuntimeContext ctx, String path) {
      String k = key(ctx, path);
      if (store.containsKey(k)) return true;
      String p = prefix(ctx, path);
      return store.keySet().stream().anyMatch(x -> x.startsWith(p));
    }

    @Override
    public LsResult ls(RuntimeContext ctx, String path) {
      String pref = prefix(ctx, path);
      Map<String, FileInfo> entries = new LinkedHashMap<>();
      for (Entry<String, String> entry : store.entrySet()) {
        if (!entry.getKey().startsWith(pref)) continue;
        String remainder = entry.getKey().substring(pref.length());
        if (remainder.isEmpty()) continue;
        int sep = remainder.indexOf('/');
        if (sep < 0) {
          entries.putIfAbsent(remainder,
              FileInfo.ofFile(remainder, entry.getValue().length(), LocalDateTime.now().toString()));
        } else {
          String dir = remainder.substring(0, sep);
          entries.putIfAbsent(dir,
              FileInfo.ofDir(dir, LocalDateTime.now().toString()));
        }
      }
      List<FileInfo> list = new ArrayList<>(entries.values());
      list.sort(Comparator.comparing(FileInfo::path));
      return LsResult.success(list);
    }

    @Override
    public WriteResult delete(RuntimeContext ctx, String path) {
      String k = key(ctx, path);
      if (store.remove(k) != null) return WriteResult.ok(path);
      String pref = prefix(ctx, path);
      List<String> toRemove = store.keySet().stream()
          .filter(x -> x.startsWith(pref)).toList();
      toRemove.forEach(store::remove);
      if (!toRemove.isEmpty()) return WriteResult.ok(path);
      return WriteResult.fail("Not found: " + path);
    }

    @Override
    public WriteResult move(RuntimeContext ctx, String src, String dst) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EditResult edit(RuntimeContext ctx, String path, String o, String n, boolean all) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GrepResult grep(RuntimeContext ctx, String query, String include, String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GlobResult glob(RuntimeContext ctx, String pattern, String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext ctx, List<Entry<String, byte[]>> files) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext ctx, List<String> paths) {
      throw new UnsupportedOperationException();
    }
  }
}
