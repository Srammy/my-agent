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
  private static final int MEBIBYTE = 1024 * 1024;

  private AgentScopeWorkspaceService service;
  private IsolatedFakeFilesystem filesystem;

  @BeforeEach
  void setUp() {
    filesystem = new IsolatedFakeFilesystem();
    service = new AgentScopeWorkspaceService(filesystem);
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

  @Test
  void createSkillPreservesBinaryResourceBytes() {
    byte[] icon = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xc3, 0x28};

    service.createSkill(
        ALICE,
        List.of(skillMdPart("image-helper", "Image helper"), fakeFilePart("assets/icon.png", icon)));

    assertThat(filesystem.bytes(ALICE, "skills/image-helper/assets/icon.png")).isEqualTo(icon);
  }

  @Test
  void createSkillRejectsMalformedUtf8SkillMdWithoutWritingFiles() {
    assertThatThrownBy(
            () ->
                service.createSkill(
                    ALICE,
                    List.of(
                        fakeFilePart("SKILL.md", new byte[] {(byte) 0xc3, 0x28}),
                        fakeFilePart("assets/icon.png", new byte[] {1}))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(filesystem.isEmpty()).isTrue();
  }

  @Test
  void createSkillPreservesCrLfResourceBytes() {
    byte[] reference = "line one\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);
    byte[] script = "echo one\r\necho two\r\n".getBytes(StandardCharsets.UTF_8);

    service.createSkill(
        ALICE,
        List.of(
            skillMdPart("line-helper", "Line helper"),
            fakeFilePart("references/guide.txt", reference),
            fakeFilePart("scripts/run.sh", script)));

    assertThat(filesystem.bytes(ALICE, "skills/line-helper/references/guide.txt"))
        .isEqualTo(reference);
    assertThat(filesystem.bytes(ALICE, "skills/line-helper/scripts/run.sh")).isEqualTo(script);
  }

  @Test
  void createSkillRejectsTraversalPathWithoutWritingFiles() {
    assertThatThrownBy(
            () ->
                service.createSkill(
                    ALICE,
                    List.of(
                        skillMdPart("image-helper", "Image helper"),
                        fakeFilePart("assets/../../escape.bin", new byte[] {1}))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(filesystem.isEmpty()).isTrue();
  }

  @Test
  void createSkillRejectsTrimmedSkillMdResourcePathWithoutWritingFiles() {
    assertThatThrownBy(
            () ->
                service.createSkill(
                    ALICE,
                    List.of(
                        skillMdPart("image-helper", "Image helper"),
                        fakeFilePart(" SKILL.md ", "unexpected resource"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(filesystem.isEmpty()).isTrue();
  }

  @Test
  void createSkillRejectsFailedResourceUploadWithoutSkillMarker() {
    filesystem.failUploadsFor("skills/image-helper/assets/icon.png");

    assertThatThrownBy(
            () ->
                service.createSkill(
                    ALICE,
                    List.of(
                        skillMdPart("image-helper", "Image helper"),
                        fakeFilePart("assets/icon.png", new byte[] {1}))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(filesystem.exists(runtimeContext(ALICE), "skills/image-helper/SKILL.md")).isFalse();
  }

  @Test
  void createSkillRejectsNullUploadResponseListWithoutSkillMarker() {
    filesystem.respondToUploadsWith(UploadResponseMode.NULL_LIST);

    assertResourceUploadResponseFailure();
  }

  @Test
  void nullUploadResponseModeOnlyFailsFirstUpload() {
    String path = "skills/image-helper/assets/icon.png";
    List<Entry<String, byte[]>> files = List.of(Map.entry(path, new byte[] {1}));
    filesystem.respondToUploadsWith(UploadResponseMode.NULL_LIST);

    assertThat(filesystem.uploadFiles(runtimeContext(ALICE), files)).isNull();
    assertThat(filesystem.uploadFiles(runtimeContext(ALICE), files))
        .containsExactly(FileUploadResponse.success(path));
  }

  @Test
  void createSkillRejectsIncompleteUploadResponsesWithoutSkillMarker() {
    filesystem.respondToUploadsWith(UploadResponseMode.INCOMPLETE);

    assertResourceUploadResponseFailure();
  }

  @Test
  void createSkillRejectsNullUploadResponseWithoutSkillMarker() {
    filesystem.respondToUploadsWith(UploadResponseMode.NULL_ELEMENT);

    assertResourceUploadResponseFailure();
  }

  @Test
  void createSkillRejectsMismatchedUploadResponsePathWithoutSkillMarker() {
    filesystem.respondToUploadsWith(UploadResponseMode.MISMATCHED_PATH);

    assertResourceUploadResponseFailure();
  }

  @Test
  void createSkillRejectsMoreThan32Files() {
    List<Part> parts = new ArrayList<>();
    for (int index = 0; index < 33; index++) {
      parts.add(fakeFilePart("assets/file-" + index + ".txt", new byte[0]));
    }

    assertPayloadTooLarge(() -> service.createSkill(ALICE, parts));
  }

  @Test
  void createSkillRejectsFileLargerThanOneMebibyte() {
    assertPayloadTooLarge(() -> service.createSkill(
        ALICE,
        List.of(fakeFilePart("assets/large.txt", new byte[MEBIBYTE + 1]))));
  }

  @Test
  void createSkillRejectsMoreThanFiveMebibytesInTotal() {
    List<Part> parts = new ArrayList<>();
    for (int index = 0; index < 6; index++) {
      parts.add(fakeFilePart("assets/file-" + index + ".txt", new byte[900 * 1024]));
    }

    assertPayloadTooLarge(() -> service.createSkill(ALICE, parts));
  }

  private static void assertPayloadTooLarge(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
  }

  private void assertResourceUploadResponseFailure() {
    assertThatThrownBy(
            () ->
                service.createSkill(
                    ALICE,
                    List.of(
                        skillMdPart("image-helper", "Image helper"),
                        fakeFilePart("assets/icon.png", new byte[] {1}))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

    assertThat(filesystem.exists(runtimeContext(ALICE), "skills/image-helper/SKILL.md")).isFalse();
  }

  private static FilePart skillMdPart(String name, String description) {
    // SkillUtil.createFrom() requires non-empty body content after the YAML front matter
    String content = "---\nname: " + name + "\ndescription: " + description + "\n---\n\nSkill instructions.\n";
    return fakeFilePart("SKILL.md", content);
  }

  private static FilePart fakeFilePart(String filename, String content) {
    return fakeFilePart(filename, content.getBytes(StandardCharsets.UTF_8));
  }

  private static FilePart fakeFilePart(String filename, byte[] bytes) {
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

  private static RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder().userId(user.id().toString()).sessionId("workspace-api").build();
  }

  /**
   * In-memory filesystem that isolates data by RuntimeContext.userId.
   * Implements the operations used by AgentScopeWorkspaceService and WorkspaceSkillRepository:
   *   - uploadFiles()  → skill creation
   *   - glob()         → getAllSkills() / skillExists()
   *   - read()         → reading SKILL.md content (limit=0 means read all)
   *   - move()         → delete() archives the skill directory
   */
  private static final class IsolatedFakeFilesystem implements AbstractFilesystem {

    // key = "userId::normalizedPath"
    private final Map<String, byte[]> store = new LinkedHashMap<>();
    private String failingUploadPath;
    private UploadResponseMode uploadResponseMode = UploadResponseMode.SUCCESS;
    private int uploadCalls;

    void failUploadsFor(String path) {
      failingUploadPath = normalize(path);
    }

    void respondToUploadsWith(UploadResponseMode mode) {
      uploadResponseMode = mode;
      uploadCalls = 0;
    }

    byte[] bytes(CurrentUser user, String path) {
      byte[] value = store.get(key(runtimeContext(user), path));
      return value == null ? null : value.clone();
    }

    boolean isEmpty() {
      return store.isEmpty();
    }

    // ---- path helpers ----

    private String key(RuntimeContext ctx, String path) {
      return ctx.getUserId() + "::" + normalize(path);
    }

    private String prefix(RuntimeContext ctx, String path) {
      return ctx.getUserId() + "::" + normalize(path) + "/";
    }

    private String normalize(String path) {
      if (path == null) return "";
      String p = path.replace('\\', '/');
      while (p.startsWith("/")) p = p.substring(1);
      while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
      return p;
    }

    // ---- core operations used by AgentScopeWorkspaceService or WorkspaceSkillRepository ----

    /**
     * AgentScopeWorkspaceService writes resources and the SKILL.md marker through uploadFiles().
     */
    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext ctx, List<Entry<String, byte[]>> files) {
      if (uploadResponseMode == UploadResponseMode.NULL_LIST && uploadCalls++ == 0) {
        return null;
      }
      List<FileUploadResponse> responses = new ArrayList<>();
      for (Entry<String, byte[]> entry : files) {
        String path = entry.getKey();
        if (normalize(path).equals(failingUploadPath)) {
          responses.add(FileUploadResponse.fail(path, "Configured upload failure"));
          continue;
        }
        store.put(key(ctx, path), entry.getValue().clone());
        responses.add(FileUploadResponse.success(path));
      }
      if (uploadResponseMode == UploadResponseMode.INCOMPLETE) {
        return List.of();
      }
      if (uploadResponseMode == UploadResponseMode.NULL_ELEMENT) {
        return java.util.Collections.nCopies(files.size(), null);
      }
      if (uploadResponseMode == UploadResponseMode.MISMATCHED_PATH) {
        return files.stream()
            .map(entry -> FileUploadResponse.success(entry.getKey() + ".unexpected"))
            .toList();
      }
      return responses;
    }

    /**
     * WorkspaceSkillRepository.getAllSkills() calls glob(ctx, "SKILL.md", skillsRelativeDir).
     * Returns FileInfo for every stored path whose filename matches the pattern and whose
     * parent is under the given base directory.
     */
    @Override
    public GlobResult glob(RuntimeContext ctx, String pattern, String base) {
      String normalizedBase = normalize(base);
      String userPrefix = ctx.getUserId() + "::";
      // Only support the filename-match pattern used by WorkspaceSkillRepository ("SKILL.md")
      String filePattern = pattern != null ? normalize(pattern) : "";

      List<FileInfo> matches = new ArrayList<>();
      for (String storeKey : store.keySet()) {
        if (!storeKey.startsWith(userPrefix)) continue;
        String filePath = storeKey.substring(userPrefix.length()); // e.g. "skills/java-helper/SKILL.md"
        if (!normalizedBase.isEmpty() && !filePath.startsWith(normalizedBase + "/")) continue;
        String fileName = filePath.contains("/")
            ? filePath.substring(filePath.lastIndexOf('/') + 1)
            : filePath;
        if (!filePattern.isEmpty() && !fileName.equals(filePattern)) continue;
        matches.add(FileInfo.ofFile(filePath, store.get(storeKey).length, LocalDateTime.now().toString()));
      }
      return GlobResult.success(matches);
    }

    /**
     * WorkspaceSkillRepository.getAllSkills() reads each SKILL.md with limit=0.
     * limit <= 0 means "read all content from offset".
     */
    @Override
    public ReadResult read(RuntimeContext ctx, String path, int offset, int limit) {
      byte[] value = store.get(key(ctx, path));
      if (value == null) return ReadResult.fail("Not found: " + path);
      String val = new String(value, StandardCharsets.UTF_8);
      String content = offset > 0 && offset < val.length() ? val.substring(offset) : val;
      if (limit > 0 && content.length() > limit) {
        content = content.substring(0, limit);
      }
      String now = LocalDateTime.now().toString();
      return ReadResult.success(new FileData(content, "utf-8", now, now));
    }

    /**
     * WorkspaceSkillRepository.delete() archives by calling move(ctx, skillDir, archiveDir).
     * We implement this as: copy all keys under src prefix to dst prefix, then remove originals.
     */
    @Override
    public WriteResult move(RuntimeContext ctx, String src, String dst) {
      String srcPrefix = prefix(ctx, src);
      String dstPrefix = prefix(ctx, dst);
      String srcExact = key(ctx, src);

      // Move single file
      if (store.containsKey(srcExact)) {
        byte[] content = store.remove(srcExact);
        store.put(key(ctx, dst), content);
        return WriteResult.ok(dst);
      }

      // Move directory (all keys under src prefix)
      List<Entry<String, byte[]>> toMove = store.entrySet().stream()
          .filter(e -> e.getKey().startsWith(srcPrefix))
          .map(e -> Map.entry(e.getKey(), e.getValue()))
          .toList();
      if (toMove.isEmpty()) return WriteResult.fail("Not found: " + src);
      for (Entry<String, byte[]> entry : toMove) {
        String newKey = dstPrefix + entry.getKey().substring(srcPrefix.length());
        store.remove(entry.getKey());
        store.put(newKey, entry.getValue());
      }
      return WriteResult.ok(dst);
    }

    // ---- secondary operations (used by service directly or not at all) ----

    @Override
    public WriteResult write(RuntimeContext ctx, String path, String content) {
      store.put(key(ctx, path), content.getBytes(StandardCharsets.UTF_8));
      return WriteResult.ok(path);
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
      for (Entry<String, byte[]> entry : store.entrySet()) {
        if (!entry.getKey().startsWith(pref)) continue;
        String remainder = entry.getKey().substring(pref.length());
        if (remainder.isEmpty()) continue;
        int sep = remainder.indexOf('/');
        if (sep < 0) {
          entries.putIfAbsent(remainder,
              FileInfo.ofFile(remainder, entry.getValue().length, LocalDateTime.now().toString()));
        } else {
          String dir = remainder.substring(0, sep);
          entries.putIfAbsent(dir, FileInfo.ofDir(dir, LocalDateTime.now().toString()));
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
      List<String> toRemove = store.keySet().stream().filter(x -> x.startsWith(pref)).toList();
      toRemove.forEach(store::remove);
      return toRemove.isEmpty() ? WriteResult.fail("Not found: " + path) : WriteResult.ok(path);
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
    public List<FileDownloadResponse> downloadFiles(RuntimeContext ctx, List<String> paths) {
      throw new UnsupportedOperationException();
    }
  }

  private enum UploadResponseMode {
    SUCCESS,
    NULL_LIST,
    INCOMPLETE,
    NULL_ELEMENT,
    MISMATCHED_PATH
  }
}
