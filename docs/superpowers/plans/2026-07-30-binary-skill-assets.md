# Binary-Safe Skill Assets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve every uploaded Skill resource byte exactly while keeping `SKILL.md` strict UTF-8 text and retaining bounded multipart uploads.

**Architecture:** Keep multipart collection bounded at the WebFlux and service layers. Validate Skill metadata through AgentScope without putting resources into its text-only `AgentSkill.resources`, then write raw resource bytes through `AbstractFilesystem`, publishing `SKILL.md` only after resource writes succeed.

**Tech Stack:** Java 21, Spring Boot 3.3.5 WebFlux, AgentScope 2.0.0-RC4, JUnit 5, AssertJ, Maven.

## Global Constraints

- Allow at most 32 uploaded files.
- Allow at most 1 MiB per file and 5 MiB across one Skill upload.
- Return HTTP 413 for multipart count or size violations.
- Allow resources only below `references/`, `scripts/`, and `assets/`.
- Store all resource files as their original `byte[]`; do not infer MIME type.
- Decode only `SKILL.md` as strict UTF-8 and return HTTP 400 for malformed input.
- Preserve user isolation, duplicate detection, listing, and deletion behavior.
- Do not modify or fork AgentScope SDK classes.
- Leave the unrelated untracked `.claude/` directory untouched.

---

### Task 1: Commit the Bounded Upload Baseline

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java`
- Modify: `backend/src/main/java/com/example/myagent/skill/SkillController.java`
- Create: `backend/src/main/java/com/example/myagent/skill/SkillUploadWebConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/skill/AgentScopeWorkspaceServiceTest.java`
- Modify: `backend/src/test/java/com/example/myagent/skill/SkillControllerTest.java`

**Interfaces:**
- Produces: `AgentScopeWorkspaceService.MAX_FILE_COUNT`
- Produces: `AgentScopeWorkspaceService.MAX_FILE_SIZE`
- Produces: service-level HTTP 413 enforcement for file count, per-file size, and total size.
- Produces: WebFlux multipart reader limits and `DataBufferLimitException` to HTTP 413 mapping.

- [ ] **Step 1: Verify the existing focused regression tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -f backend/pom.xml -Dtest=AgentScopeWorkspaceServiceTest,SkillControllerTest test
```

Expected: PASS, including file count, single-file size, total size, and real multipart parser tests.

- [ ] **Step 2: Verify that only the five bounded-upload files are included**

Run:

```powershell
git diff --check
git status --short
```

Expected: the five files listed above are modified or untracked; the design and plan documents may be committed separately; `.claude/` remains untracked.

- [ ] **Step 3: Commit the bounded-upload baseline**

```powershell
git add -- `
  backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java `
  backend/src/main/java/com/example/myagent/skill/SkillController.java `
  backend/src/main/java/com/example/myagent/skill/SkillUploadWebConfig.java `
  backend/src/test/java/com/example/myagent/skill/AgentScopeWorkspaceServiceTest.java `
  backend/src/test/java/com/example/myagent/skill/SkillControllerTest.java
git commit -m "limit skill upload resources"
```

Expected: one commit containing only the bounded-upload implementation and tests.

---

### Task 2: Preserve Resource Bytes and Publish SKILL.md Last

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java:46-82`
- Modify: `backend/src/test/java/com/example/myagent/skill/AgentScopeWorkspaceServiceTest.java:40-330`

**Interfaces:**
- Consumes: `AbstractFilesystem.uploadFiles(RuntimeContext, List<Map.Entry<String, byte[]>>)`
- Consumes: `FileUploadResponse.isSuccess()`
- Consumes: `WorkspaceSkillRepository.skillExists(String)`
- Produces: `decodeSkillMarkdown(byte[]) -> String`
- Produces: `uploadFiles(RuntimeContext, List<Map.Entry<String, byte[]>>) -> void`
- Produces: raw resource paths below `skills/<skillName>/`.

- [ ] **Step 1: Make the fake filesystem byte-preserving**

Change the fake store to retain bytes:

```java
private final Map<String, byte[]> store = new LinkedHashMap<>();
private String failedUploadPath;

byte[] content(CurrentUser user, String path) {
  byte[] content = store.get(user.id() + "::" + normalize(path));
  return content == null ? null : content.clone();
}

void failUploadFor(String path) {
  failedUploadPath = path;
}

boolean isEmpty() {
  return store.isEmpty();
}
```

In `uploadFiles`, store a defensive copy:

```java
if (entry.getKey().equals(failedUploadPath)) {
  responses.add(FileUploadResponse.fail(entry.getKey(), "simulated failure"));
} else {
  store.put(key(ctx, entry.getKey()), entry.getValue().clone());
  responses.add(FileUploadResponse.success(entry.getKey()));
}
```

In text-oriented fake methods, decode only when a `String` is required:

```java
byte[] bytes = store.get(key(ctx, path));
String content = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
```

Use `byte[].length` for `FileInfo` sizes, copy `byte[]` during moves, and encode strings in `write`.

- [ ] **Step 2: Write failing binary and strict UTF-8 tests**

Retain the fake filesystem in the fixture:

```java
private IsolatedFakeFilesystem filesystem;

@BeforeEach
void setUp() {
  filesystem = new IsolatedFakeFilesystem();
  service = new AgentScopeWorkspaceService(filesystem);
}
```

Add the binary regression test:

```java
@Test
void createSkillPreservesBinaryResourceBytes() {
  byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, (byte) 0xff};

  service.createSkill(ALICE, List.of(
      skillMdPart("image-helper", "Image helper"),
      fakeFilePart("assets/icon.png", png)));

  assertThat(filesystem.content(ALICE, "skills/image-helper/assets/icon.png"))
      .containsExactly(png);
}
```

Add malformed `SKILL.md` coverage:

```java
@Test
void createSkillRejectsMalformedUtf8SkillMarkdownWithoutWritingFiles() {
  byte[] malformed = {(byte) 0xc3, 0x28};

  assertThatThrownBy(() ->
          service.createSkill(ALICE, List.of(fakeFilePart("SKILL.md", malformed))))
      .isInstanceOfSatisfying(
          ResponseStatusException.class,
          error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

  assertThat(filesystem.isEmpty()).isTrue();
}
```

Verify that text-oriented roots also bypass resource re-encoding:

```java
@Test
void createSkillPreservesReferenceAndScriptBytes() {
  byte[] reference = "line one\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);
  byte[] script = "#!/bin/sh\r\nexit 0\r\n".getBytes(StandardCharsets.UTF_8);

  service.createSkill(ALICE, List.of(
      skillMdPart("text-helper", "Text helper"),
      fakeFilePart("references/guide.md", reference),
      fakeFilePart("scripts/run.sh", script)));

  assertThat(filesystem.content(ALICE, "skills/text-helper/references/guide.md"))
      .containsExactly(reference);
  assertThat(filesystem.content(ALICE, "skills/text-helper/scripts/run.sh"))
      .containsExactly(script);
}
```

Verify that all paths are validated before the first filesystem write:

```java
@Test
void createSkillRejectsInvalidResourcePathBeforeWritingFiles() {
  assertThatThrownBy(() -> service.createSkill(ALICE, List.of(
          skillMdPart("unsafe-helper", "Unsafe helper"),
          fakeFilePart("assets/../../escape.bin", new byte[] {1}))))
      .isInstanceOfSatisfying(
          ResponseStatusException.class,
          error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

  assertThat(filesystem.isEmpty()).isTrue();
}
```

Add a failing-resource filesystem test:

```java
@Test
void createSkillDoesNotPublishSkillMarkdownWhenResourceUploadFails() {
  filesystem.failUploadFor("skills/image-helper/assets/icon.png");

  assertThatThrownBy(() -> service.createSkill(ALICE, List.of(
          skillMdPart("image-helper", "Image helper"),
          fakeFilePart("assets/icon.png", new byte[] {1, 2, 3}))))
      .isInstanceOfSatisfying(
          ResponseStatusException.class,
          error -> assertThat(error.getStatusCode())
              .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));

  assertThat(filesystem.content(ALICE, "skills/image-helper/SKILL.md")).isNull();
}
```

- [ ] **Step 3: Run the tests and verify RED**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -f backend/pom.xml -Dtest=AgentScopeWorkspaceServiceTest test
```

Expected: FAIL because the current service converts resource bytes through UTF-8 and writes `SKILL.md` together with resources.

- [ ] **Step 4: Decode SKILL.md strictly and validate all paths before writing**

Add imports:

```java
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
```

Decode only `SKILL.md`:

```java
private static String decodeSkillMarkdown(byte[] content) {
  try {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(content))
        .toString();
  } catch (CharacterCodingException e) {
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "SKILL.md must be valid UTF-8", e);
  }
}
```

Before any upload, validate every non-root path and retain its original bytes:

```java
Map<String, byte[]> resources = new LinkedHashMap<>();
for (Map.Entry<String, byte[]> entry : files.entrySet()) {
  if (!"SKILL.md".equals(entry.getKey())) {
    resources.put(validateFilePath(entry.getKey()), entry.getValue());
  }
}
```

- [ ] **Step 5: Validate through AgentScope without eager text resources**

Replace the text resource map passed to AgentScope:

```java
AgentSkill skill;
try {
  skill = SkillUtil.createFrom(skillMdContent, Map.of(), WORKSPACE_SOURCE);
} catch (IllegalArgumentException e) {
  throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
}
```

Keep `repo.skillExists(name)` before filesystem writes.

- [ ] **Step 6: Upload raw resources and publish SKILL.md last**

Add a checked upload helper:

```java
private void uploadFiles(
    RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
  if (files.isEmpty()) {
    return;
  }
  List<FileUploadResponse> responses = filesystem.uploadFiles(context, files);
  if (responses == null
      || responses.size() != files.size()
      || responses.stream().anyMatch(response -> response == null || !response.isSuccess())) {
    throw new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store Skill files");
  }
}
```

Build resource entries with original bytes:

```java
RuntimeContext context = runtimeContext(user);
String skillDirectory = SKILLS_DIR + "/" + name;
List<Map.Entry<String, byte[]>> resourceUploads = resources.entrySet().stream()
    .map(entry -> Map.entry(
        skillDirectory + "/" + entry.getKey(), entry.getValue()))
    .toList();

uploadFiles(context, resourceUploads);
uploadFiles(
    context,
    List.of(Map.entry(skillDirectory + "/SKILL.md", skillMdBytes)));
```

Return `new SkillDto(skill.getName(), skill.getDescription())`.

- [ ] **Step 7: Run focused tests and verify GREEN**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -f backend/pom.xml -Dtest=AgentScopeWorkspaceServiceTest,SkillControllerTest test
```

Expected: PASS. The PNG assertion must compare the original and stored bytes exactly.

- [ ] **Step 8: Commit the binary-safe storage change**

```powershell
git add -- `
  backend/src/main/java/com/example/myagent/skill/AgentScopeWorkspaceService.java `
  backend/src/test/java/com/example/myagent/skill/AgentScopeWorkspaceServiceTest.java
git commit -m "preserve binary skill resources"
```

Expected: one commit containing binary-safe persistence and its regression tests.

---

### Task 3: Full Verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: all behavior produced by Tasks 1 and 2.
- Produces: merge-ready verification evidence.

- [ ] **Step 1: Run the complete backend test suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q -f backend/pom.xml test
```

Expected: PASS with zero failures and zero errors.

- [ ] **Step 2: Inspect final repository state**

```powershell
git diff --check
git status --short
git log --oneline -4
```

Expected: no tracked source changes remain; `.claude/` may remain untracked;
the design, bounded-upload, and binary-safe commits are visible in history.

- [ ] **Step 3: Review requirements against evidence**

Confirm:

- PNG bytes containing invalid UTF-8 round-trip exactly.
- Malformed UTF-8 is rejected only for `SKILL.md`.
- Invalid paths are rejected before writes.
- Resource failures do not publish `SKILL.md`.
- Existing count and size limits still return HTTP 413.
- List, duplicate, deletion, and user-isolation tests still pass.
