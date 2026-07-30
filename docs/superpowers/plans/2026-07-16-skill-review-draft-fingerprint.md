# Skill Review Draft Fingerprint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bind every Skill approval or rejection to the SHA-256 fingerprint of the approving user's complete draft package so stale decisions cannot promote changed content.

**Architecture:** Add one filesystem-backed `SkillDraftFingerprint` component that recursively reads a user-scoped draft and computes a deterministic hash from sorted relative paths and UTF-8 content. Store that hash on `SkillReviewDecision`; `SkillReviewService` creates version-bound decisions and reports only effective statuses, while `WebApprovalGate` recomputes the hash immediately before applying a decision.

**Tech Stack:** Java 21, Spring Boot 3.3.5, AgentScope Java 2.0.0-RC4 `AbstractFilesystem`, Jackson, Reactor, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Skill isolation remains `IsolationScope.USER`; every fingerprint filesystem call must use a `RuntimeContext` containing the current `userId`.
- Skills remain shared across sessions for the same user; `sessionId("skill-review")` is an operation label, not an isolation key.
- The fingerprint covers every regular file below `skills/_drafts/<skillName>/`, including relative path and UTF-8 content.
- Approval and rejection follow identical version-binding rules.
- Legacy decisions with `draftHash == null` remain readable but are never effective.
- Do not change frontend APIs, Redis paths, AgentScope repository wiring, Skill creation, or curator configuration.
- Follow strict TDD: observe each new test fail for the intended reason before writing production code.

---

## File Map

- Create `backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprint.java`: recursively enumerate a user-scoped draft and compute its deterministic SHA-256.
- Create `backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprintException.java`: distinguish missing drafts from filesystem read failures without coupling the component to HTTP.
- Create `backend/src/test/java/com/example/myagent/skillreview/SkillDraftFingerprintTest.java`: fingerprint determinism, content sensitivity, user isolation, and failure behavior.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecision.java`: add `draftHash`.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecisionStore.java`: persist the supplied hash for approvals and rejections.
- Create `backend/src/test/java/com/example/myagent/skillreview/SkillReviewDecisionStoreTest.java`: new JSON and legacy JSON compatibility.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`: require a current hash before writing a decision and derive effective list status.
- Modify `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`: cover missing drafts, hash persistence, stale status, and legacy decisions.
- Modify `backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java`: verify the current hash before returning `Approve` or `Reject`.
- Modify `backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java`: cover matching, stale, legacy, missing, and read-failure decisions.
- Modify `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`: construct `WebApprovalGate` with the new dependency.

---

### Task 1: Deterministic user-scoped draft fingerprint

**Files:**
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprint.java`
- Create: `backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprintException.java`
- Create: `backend/src/test/java/com/example/myagent/skillreview/SkillDraftFingerprintTest.java`

**Interfaces:**
- Consumes: `AbstractFilesystem.exists(RuntimeContext, String)`, `ls(RuntimeContext, String)`, and `read(RuntimeContext, String, int, int)`.
- Produces: `String SkillDraftFingerprint.computeDraftHash(RuntimeContext context, String skillName)`.
- Throws: `SkillDraftFingerprintException` with `Reason.NOT_FOUND` or `Reason.READ_FAILURE`.

- [ ] **Step 1: Write failing fingerprint tests**

Create `SkillDraftFingerprintTest` with a Mockito-backed filesystem. Use this shared setup and helper:

Add these imports in addition to the AgentScope/JUnit types shown in the snippets:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

```java
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

  private void stubDraft(RuntimeContext ctx, String skillMd, String script) {
    when(filesystem.exists(ctx, "skills/_drafts/my-skill")).thenReturn(true);
    when(filesystem.exists(ctx, "skills/_drafts/my-skill/SKILL.md")).thenReturn(true);
    when(filesystem.ls(ctx, "skills/_drafts/my-skill"))
        .thenReturn(LsResult.success(List.of(
            FileInfo.ofDir("scripts", "now"),
            FileInfo.ofFile("SKILL.md", skillMd.length(), "now"))));
    when(filesystem.ls(ctx, "skills/_drafts/my-skill/scripts"))
        .thenReturn(LsResult.success(List.of(FileInfo.ofFile("run.sh", script.length(), "now"))));
    when(filesystem.read(ctx, "skills/_drafts/my-skill/SKILL.md", 0, 0))
        .thenReturn(ReadResult.success(new FileData(skillMd, "utf-8", "now", "now")));
    when(filesystem.read(ctx, "skills/_drafts/my-skill/scripts/run.sh", 0, 0))
        .thenReturn(ReadResult.success(new FileData(script, "utf-8", "now", "now")));
  }
}
```

Add these tests:

```java
@Test
void hashIsStableWhenDirectoryEnumerationOrderChanges() {
  String md = "---\nname: my-skill\ndescription: test\n---\n";
  stubDraft(alice, md, "echo one");
  when(filesystem.ls(alice, "skills/_drafts/my-skill"))
      .thenReturn(
          LsResult.success(List.of(
              FileInfo.ofDir("scripts", "now"),
              FileInfo.ofFile("SKILL.md", md.length(), "now"))),
          LsResult.success(List.of(
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
  RuntimeContext bob = RuntimeContext.builder().userId("2").sessionId("skill-review").build();
  String md = "---\nname: my-skill\ndescription: test\n---\n";
  stubDraft(alice, md, "echo alice");
  stubDraft(bob, md, "echo bob");

  assertThat(fingerprint.computeDraftHash(alice, "my-skill"))
      .isNotEqualTo(fingerprint.computeDraftHash(bob, "my-skill"));
  verify(filesystem, atLeastOnce()).read(alice, "skills/_drafts/my-skill/SKILL.md", 0, 0);
  verify(filesystem, atLeastOnce()).read(bob, "skills/_drafts/my-skill/SKILL.md", 0, 0);
}

@Test
void missingSkillMarkdownIsReportedAsNotFound() {
  when(filesystem.exists(alice, "skills/_drafts/my-skill")).thenReturn(true);
  when(filesystem.exists(alice, "skills/_drafts/my-skill/SKILL.md")).thenReturn(false);

  assertThatThrownBy(() -> fingerprint.computeDraftHash(alice, "my-skill"))
      .isInstanceOf(SkillDraftFingerprintException.class)
      .satisfies(error -> assertThat(((SkillDraftFingerprintException) error).reason())
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
      .satisfies(error -> assertThat(((SkillDraftFingerprintException) error).reason())
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
      .satisfies(error -> assertThat(((SkillDraftFingerprintException) error).reason())
          .isEqualTo(SkillDraftFingerprintException.Reason.READ_FAILURE));
}
```

- [ ] **Step 2: Run the tests and verify the red state**

Run:

```powershell
mvn -q -Dtest=SkillDraftFingerprintTest test
```

Expected: test compilation fails because `SkillDraftFingerprint` and `SkillDraftFingerprintException` do not exist.

- [ ] **Step 3: Implement the domain exception**

Create:

```java
package com.example.myagent.skillreview;

public final class SkillDraftFingerprintException extends RuntimeException {
  public enum Reason { NOT_FOUND, READ_FAILURE }

  private final Reason reason;

  public SkillDraftFingerprintException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public SkillDraftFingerprintException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
```

- [ ] **Step 4: Implement deterministic recursive hashing**

Create `SkillDraftFingerprint` with:

```java
package com.example.myagent.skillreview;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SkillDraftFingerprint {
  private static final String DRAFTS_DIR = "skills/_drafts";
  private final AbstractFilesystem filesystem;

  public SkillDraftFingerprint(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public String computeDraftHash(RuntimeContext context, String skillName) {
    String root = DRAFTS_DIR + "/" + skillName;
    if (!filesystem.exists(context, root)
        || !filesystem.exists(context, root + "/SKILL.md")) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND,
          "Skill draft not found: " + skillName);
    }

    List<String> paths = new ArrayList<>();
    collectFilePaths(context, root, root, paths);
    paths.sort(Comparator.naturalOrder());
    if (!paths.contains("SKILL.md")) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE,
          "Skill draft changed while fingerprinting: " + skillName);
    }

    MessageDigest digest = sha256();
    for (String relativePath : paths) {
      String absolutePath = root + "/" + relativePath;
      ReadResult result = filesystem.read(context, absolutePath, 0, 0);
      if (!result.isSuccess() || result.fileData() == null || result.fileData().content() == null) {
        throw new SkillDraftFingerprintException(
            SkillDraftFingerprintException.Reason.READ_FAILURE,
            "Failed to read skill draft file: " + absolutePath);
      }
      updateLengthPrefixed(digest, relativePath.getBytes(StandardCharsets.UTF_8));
      updateLengthPrefixed(digest, result.fileData().content().getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void collectFilePaths(
      RuntimeContext context, String root, String directory, List<String> result) {
    LsResult lsResult = filesystem.ls(context, directory);
    if (!lsResult.isSuccess() || lsResult.entries() == null) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE,
          "Failed to list skill draft directory: " + directory);
    }
    for (FileInfo entry : lsResult.entries()) {
      String path = childPath(directory, entry.path());
      if (entry.isDirectory()) {
        collectFilePaths(context, root, path, result);
      } else {
        result.add(path.substring(root.length() + 1).replace('\\', '/'));
      }
    }
  }

  private static String childPath(String directory, String entryPath) {
    String normalized = entryPath.replace('\\', '/');
    return normalized.startsWith(directory + "/") ? normalized : directory + "/" + normalized;
  }

  private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
```

- [ ] **Step 5: Run fingerprint tests and verify green**

Run:

```powershell
mvn -q -Dtest=SkillDraftFingerprintTest test
```

Expected: all `SkillDraftFingerprintTest` tests pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprint.java backend/src/main/java/com/example/myagent/skillreview/SkillDraftFingerprintException.java backend/src/test/java/com/example/myagent/skillreview/SkillDraftFingerprintTest.java
git commit -m "feat: fingerprint user skill drafts"
```

---

### Task 2: Persist the reviewed draft hash at the approval boundary

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecision.java`
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecisionStore.java`
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`
- Create: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewDecisionStoreTest.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`

**Interfaces:**
- Consumes: caller-supplied lowercase SHA-256 `draftHash`.
- Consumes: `SkillDraftFingerprint.computeDraftHash(RuntimeContext, String)` from Task 1.
- Produces: `SkillReviewDecision(..., Instant decidedAt, String draftHash)`.
- Produces: `approve(skillName, reviewerId, environments, draftHash, userId)` and `reject(skillName, reviewerId, reason, draftHash, userId)`.
- Produces: HTTP 404 for missing drafts and HTTP 500 for unreadable drafts without persisting a decision.

- [ ] **Step 1: Write failing store compatibility tests**

Create tests using a mocked `AbstractFilesystem`:

```java
@Test
void approvePersistsAndReadsDraftHash() {
  when(filesystem.write(any(RuntimeContext.class), eq("skill-reviews/my-skill.json"), anyString()))
      .thenReturn(WriteResult.ok("skill-reviews/my-skill.json"));

  SkillReviewDecision decision =
      store.approve("my-skill", "admin", List.of("prod"), "abc123", "1");

  assertThat(decision.draftHash()).isEqualTo("abc123");
  ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
  ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
  verify(filesystem).write(context.capture(), eq("skill-reviews/my-skill.json"), json.capture());
  assertThat(context.getValue().getUserId()).isEqualTo("1");
  assertThat(context.getValue().getSessionId()).isEqualTo("skill-review");
  assertThat(json.getValue()).contains("\"draftHash\":\"abc123\"");
}

@Test
void findReadsLegacyJsonWithoutDraftHash() {
  String legacy = """
      {"skillName":"my-skill","status":"APPROVED","reviewerId":"admin",
       "reason":null,"environments":["prod"],"decidedAt":"2026-07-16T00:00:00Z"}
      """;
  when(filesystem.exists(any(RuntimeContext.class), eq("skill-reviews/my-skill.json")))
      .thenReturn(true);
  when(filesystem.read(any(RuntimeContext.class), eq("skill-reviews/my-skill.json"), eq(0), anyInt()))
      .thenReturn(ReadResult.success(new FileData(legacy, "utf-8", "now", "now")));

  SkillReviewDecision decision = store.find("my-skill", "1").orElseThrow();

  assertThat(decision.draftHash()).isNull();
}
```

Use a `@BeforeEach` that mocks `AbstractFilesystem` and constructs `new SkillReviewDecisionStore(filesystem)`.

Add these test imports:

```java
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import org.mockito.ArgumentCaptor;
```

- [ ] **Step 2: Write failing service tests for decision creation**

Add `SkillDraftFingerprint fingerprint` to `SkillReviewServiceTest`, mock it in `setUp`, and construct the service with four arguments. Add:

Extend the test imports with:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
```

```java
@Test
void approveRejectsMissingDraftWithoutSavingDecision() {
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

  assertThatThrownBy(() ->
      service.approve("my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")), "1"))
      .isInstanceOf(ResponseStatusException.class)
      .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND));
  verifyNoInteractions(decisionStore);
}

@Test
void rejectRejectsMissingDraftWithoutSavingDecision() {
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

  assertThatThrownBy(() ->
      service.reject("my-skill", new RejectSkillReviewRequest("admin", "risk"), "1"))
      .isInstanceOf(ResponseStatusException.class)
      .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND));
  verifyNoInteractions(decisionStore);
}

@Test
void approveReportsUnreadableDraftWithoutSavingDecision() {
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

  assertThatThrownBy(() ->
      service.approve("my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")), "1"))
      .isInstanceOf(ResponseStatusException.class)
      .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
          .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
  verifyNoInteractions(decisionStore);
}

@Test
void approveStoresTheCurrentDraftHash() {
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenReturn("hash-v1");
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "APPROVED", "admin", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.approve("my-skill", "admin", List.of("prod"), "hash-v1", "1"))
      .thenReturn(decision);

  SkillReviewDto result = service.approve(
      "my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")), "1");

  assertThat(result.status()).isEqualTo("APPROVED");
  ArgumentCaptor<RuntimeContext> context = ArgumentCaptor.forClass(RuntimeContext.class);
  verify(fingerprint).computeDraftHash(context.capture(), eq("my-skill"));
  assertThat(context.getValue().getUserId()).isEqualTo("1");
  assertThat(context.getValue().getSessionId()).isEqualTo("skill-review");
  verify(decisionStore).approve("my-skill", "admin", List.of("prod"), "hash-v1", "1");
}

@Test
void rejectStoresTheCurrentDraftHash() {
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenReturn("hash-v2");
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "REJECTED", "admin", "risk", List.of(), Instant.now(), "hash-v2");
  when(decisionStore.reject("my-skill", "admin", "risk", "hash-v2", "1"))
      .thenReturn(decision);

  service.reject("my-skill", new RejectSkillReviewRequest("admin", "risk"), "1");

  verify(decisionStore).reject("my-skill", "admin", "risk", "hash-v2", "1");
}
```

- [ ] **Step 3: Run tests and verify red**

Run:

```powershell
mvn -q '-Dtest=SkillReviewDecisionStoreTest,SkillReviewServiceTest' test
```

Expected: compilation fails because `draftHash()`, the new store signatures, and the four-argument service constructor do not exist.

- [ ] **Step 4: Extend the decision record and store signatures**

Change the record to:

```java
public record SkillReviewDecision(
    String skillName,
    String status,
    String reviewerId,
    String reason,
    List<String> environments,
    Instant decidedAt,
    String draftHash) {}
```

Change store construction to include the supplied hash:

```java
public SkillReviewDecision approve(
    String skillName,
    String reviewerId,
    List<String> environments,
    String draftHash,
    String userId) {
  SkillReviewDecision decision =
      new SkillReviewDecision(
          skillName, "APPROVED", reviewerId, null, environments, Instant.now(), draftHash);
  persist(decision, userId);
  return decision;
}

public SkillReviewDecision reject(
    String skillName, String reviewerId, String reason, String draftHash, String userId) {
  SkillReviewDecision decision =
      new SkillReviewDecision(
          skillName, "REJECTED", reviewerId, reason, List.of(), Instant.now(), draftHash);
  persist(decision, userId);
  return decision;
}
```

- [ ] **Step 5: Inject the fingerprint and bind both service decisions to it**

Add the field and constructor parameter:

```java
private final SkillDraftFingerprint fingerprint;

public SkillReviewService(
    AbstractFilesystem filesystem,
    SkillReviewDecisionStore decisionStore,
    SkillUsageStore usageStore,
    SkillDraftFingerprint fingerprint) {
  this.filesystem = filesystem;
  this.decisionStore = decisionStore;
  this.usageStore = usageStore;
  this.fingerprint = fingerprint;
}
```

At the start of both `approve` and `reject`, after name validation, add:

```java
RuntimeContext ctx = userContext(userId);
String draftHash = requireDraftHash(ctx, skillName);
```

Pass `draftHash` to the new store signatures. Add:

```java
private String requireDraftHash(RuntimeContext ctx, String skillName) {
  try {
    return fingerprint.computeDraftHash(ctx, skillName);
  } catch (SkillDraftFingerprintException exception) {
    HttpStatus status =
        exception.reason() == SkillDraftFingerprintException.Reason.NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.INTERNAL_SERVER_ERROR;
    throw new ResponseStatusException(status, exception.getMessage(), exception);
  }
}
```

- [ ] **Step 6: Update all existing decision fixtures and mocks to compile**

In `SkillReviewServiceTest` and `WebApprovalGateTest`, change every six-component constructor to the seven-component form by appending `"hash-v1"`. Change the existing service approval mock to:

```java
when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
    .thenReturn("hash-v1");
when(decisionStore.approve("my-skill", "admin", List.of("prod"), "hash-v1", "1"))
    .thenReturn(decision);
```

Change the existing service rejection mock to:

```java
when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
    .thenReturn("hash-v1");
when(decisionStore.reject("my-skill", "admin", "Too risky", "hash-v1", "1"))
    .thenReturn(decision);
```

- [ ] **Step 7: Run decision and service tests**

Run:

```powershell
mvn -q '-Dtest=SkillReviewDecisionStoreTest,SkillReviewServiceTest' test
```

Expected: all selected tests pass, including 404 behavior and hash persistence for approval and rejection.

- [ ] **Step 8: Commit Task 2**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecision.java backend/src/main/java/com/example/myagent/skillreview/SkillReviewDecisionStore.java backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java backend/src/test/java/com/example/myagent/skillreview/SkillReviewDecisionStoreTest.java backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java
git commit -m "fix: bind skill decisions to current drafts"
```

---

### Task 3: Derive effective review status in the list API

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`

**Interfaces:**
- Consumes: `SkillDraftFingerprint.computeDraftHash(RuntimeContext, String)` from Task 1.
- Consumes: hash-aware store methods from Task 2.
- Produces: `PENDING` for stale, unreadable, or legacy list decisions.

- [ ] **Step 1: Extract the exact list fixture and add failing effective-status tests**

Replace the filesystem stubbing in `listReturnsPendingSkillsFromDraftsDirectory` with a call to this helper, while keeping that test's `decisionStore.find(...)` and assertions:

Add these static imports for the new assertions:

```java
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
```

```java
private void stubListedDraft() {
  when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts"))).thenReturn(true);
  when(filesystem.ls(any(RuntimeContext.class), eq("skills/_drafts")))
      .thenReturn(LsResult.success(
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
      .thenReturn(ReadResult.success(new FileData(
          skillMd, "utf-8", "2026-07-08T09:00:00", "2026-07-08T09:00:00")));
  when(usageStore.get("my-skill")).thenReturn(Optional.empty());
}
```

Add:

```java
@Test
void listKeepsApprovedStatusWhenDraftHashMatches() {
  stubListedDraft();
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "APPROVED", "admin", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenReturn("hash-v1");

  assertThat(service.list("1").get(0).status()).isEqualTo("APPROVED");
}

@Test
void listShowsPendingWhenDraftChangedAfterApproval() {
  stubListedDraft();
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "APPROVED", "admin", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenReturn("hash-v2");

  assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
}

@Test
void listShowsPendingForLegacyDecisionWithoutHash() {
  stubListedDraft();
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "APPROVED", "admin", null, List.of("prod"), Instant.now(), null);
  when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));

  assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
  verify(fingerprint, never()).computeDraftHash(any(RuntimeContext.class), anyString());
}

@Test
void listShowsPendingWhenCurrentDraftCannotBeRead() {
  stubListedDraft();
  SkillReviewDecision decision = new SkillReviewDecision(
      "my-skill", "APPROVED", "admin", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "1")).thenReturn(Optional.of(decision));
  when(fingerprint.computeDraftHash(any(RuntimeContext.class), eq("my-skill")))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

  assertThat(service.list("1").get(0).status()).isEqualTo("PENDING");
}
```

- [ ] **Step 2: Run service tests and verify red**

Run:

```powershell
mvn -q -Dtest=SkillReviewServiceTest test
```

Expected: tests fail because the service neither computes hashes nor resolves effective status.

- [ ] **Step 3: Derive list status from the current hash**

Replace direct status extraction with:

```java
private String effectiveStatus(
    RuntimeContext ctx, String skillName, Optional<SkillReviewDecision> maybeDecision) {
  if (maybeDecision.isEmpty() || maybeDecision.get().draftHash() == null) {
    return "PENDING";
  }
  try {
    String currentHash = fingerprint.computeDraftHash(ctx, skillName);
    return currentHash.equals(maybeDecision.get().draftHash())
        ? maybeDecision.get().status()
        : "PENDING";
  } catch (SkillDraftFingerprintException exception) {
    return "PENDING";
  }
}
```

Call it from `buildDto`:

```java
String status = effectiveStatus(ctx, skillName, maybeDecision);
```

- [ ] **Step 4: Run service tests and verify green**

Run:

```powershell
mvn -q -Dtest=SkillReviewServiceTest test
```

Expected: all `SkillReviewServiceTest` tests pass.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java
git commit -m "fix: hide stale skill review status"
```

---

### Task 4: Revalidate the draft in PromotionGate and run regression tests

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

**Interfaces:**
- Consumes: `SkillDraftFingerprint.computeDraftHash(RuntimeContext, String)`.
- Produces: `Approve`/`Reject` only when the current hash equals `decision.draftHash()`; otherwise produces `Defer`.

- [ ] **Step 1: Inject the fingerprint mock and add failing gate tests**

Change the fixture to:

Add this static import for the legacy-decision assertion:

```java
import static org.mockito.Mockito.verifyNoInteractions;
```

```java
private SkillReviewDecisionStore decisionStore;
private SkillDraftFingerprint fingerprint;
private WebApprovalGate gate;
private RuntimeContext ctx;

@BeforeEach
void setUp() {
  decisionStore = mock(SkillReviewDecisionStore.class);
  fingerprint = mock(SkillDraftFingerprint.class);
  gate = new WebApprovalGate(decisionStore, fingerprint);
  ctx = RuntimeContext.builder().userId("test-user").sessionId("test-session").build();
}
```

Then add:

```java
@Test
void reviewDefersWhenApprovedDraftChanged() {
  SkillCandidate candidate = buildCandidate("my-skill");
  SkillReviewDecision stored = new SkillReviewDecision(
      "my-skill", "APPROVED", "reviewer1", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
  when(fingerprint.computeDraftHash(ctx, "my-skill")).thenReturn("hash-v2");

  assertThat(gate.review(candidate, ctx).block())
      .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
}

@Test
void reviewDefersForLegacyDecisionWithoutHash() {
  SkillCandidate candidate = buildCandidate("my-skill");
  SkillReviewDecision stored = new SkillReviewDecision(
      "my-skill", "APPROVED", "reviewer1", null, List.of("prod"), Instant.now(), null);
  when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));

  assertThat(gate.review(candidate, ctx).block())
      .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
  verifyNoInteractions(fingerprint);
}

@Test
void reviewDefersWhenCurrentDraftCannotBeRead() {
  SkillCandidate candidate = buildCandidate("my-skill");
  SkillReviewDecision stored = new SkillReviewDecision(
      "my-skill", "APPROVED", "reviewer1", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
  when(fingerprint.computeDraftHash(ctx, "my-skill"))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE, "unavailable"));

  assertThat(gate.review(candidate, ctx).block())
      .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
}

@Test
void reviewDefersWhenCurrentDraftIsMissing() {
  SkillCandidate candidate = buildCandidate("my-skill");
  SkillReviewDecision stored = new SkillReviewDecision(
      "my-skill", "APPROVED", "reviewer1", null, List.of("prod"), Instant.now(), "hash-v1");
  when(decisionStore.find("my-skill", "test-user")).thenReturn(Optional.of(stored));
  when(fingerprint.computeDraftHash(ctx, "my-skill"))
      .thenThrow(new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND, "missing"));

  assertThat(gate.review(candidate, ctx).block())
      .isInstanceOf(SkillPromotionGate.PromotionDecision.Defer.class);
}
```

Update both existing approve and reject tests so the stored decision appends `"hash-v1"`, then add this stub immediately after `decisionStore.find(...)`:

```java
when(fingerprint.computeDraftHash(ctx, "my-skill")).thenReturn("hash-v1");
```

- [ ] **Step 2: Run gate tests and verify red**

Run:

```powershell
mvn -q -Dtest=WebApprovalGateTest test
```

Expected: stale and unreadable decisions still produce the old result, or constructor compilation fails because the gate lacks the new dependency.

- [ ] **Step 3: Inject and enforce the fingerprint**

Change construction to:

```java
private final SkillReviewDecisionStore decisionStore;
private final SkillDraftFingerprint fingerprint;

public WebApprovalGate(
    SkillReviewDecisionStore decisionStore, SkillDraftFingerprint fingerprint) {
  this.decisionStore = decisionStore;
  this.fingerprint = fingerprint;
}
```

Before the status switch, validate:

```java
if (decision.draftHash() == null) {
  return new PromotionDecision.Defer(RETRY_AFTER, "Draft version requires review");
}
try {
  String currentHash = fingerprint.computeDraftHash(ctx, candidate.name());
  if (!currentHash.equals(decision.draftHash())) {
    return new PromotionDecision.Defer(RETRY_AFTER, "Draft changed after review");
  }
} catch (SkillDraftFingerprintException exception) {
  return new PromotionDecision.Defer(RETRY_AFTER, "Draft is unavailable for review validation");
}
```

Leave the existing APPROVED/REJECTED/unknown status switch after this guard.

- [ ] **Step 4: Update direct gate construction in configuration tests**

In `AgentScopeConfigTest`, replace:

```java
new WebApprovalGate(decisionStore)
```

with:

```java
new WebApprovalGate(decisionStore, mock(SkillDraftFingerprint.class))
```

Add:

```java
import com.example.myagent.skillreview.SkillDraftFingerprint;
```

- [ ] **Step 5: Run all Skill-focused tests**

Run:

```powershell
mvn -q '-Dtest=SkillDraftFingerprintTest,SkillReviewDecisionStoreTest,SkillReviewServiceTest,WebApprovalGateTest,AgentScopeWorkspaceServiceTest,SkillControllerTest,AgentScopeConfigTest' test
```

Expected: all selected tests pass.

- [ ] **Step 6: Run the complete backend test suite**

Run:

```powershell
mvn -q test
```

Expected: Maven exits with code 0 and reports no test failures or errors.

- [ ] **Step 7: Check the final diff**

Run:

```powershell
git diff --check
git status --short
```

Expected: `git diff --check` prints nothing; status lists only the intended Task 4 files, plus any pre-existing untracked `.claude/` directory which must remain untouched.

- [ ] **Step 8: Commit Task 4**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "fix: reject stale skill review decisions"
```

---

## Final Verification

After all four task commits, run:

```powershell
git log -5 --oneline
git status --short --branch
```

Expected:

- Four implementation commits appear after design/plan documentation commits.
- The branch is `master` unless execution deliberately creates an isolated `codex/` worktree branch.
- No implementation file is modified or staged.
- The pre-existing untracked `.claude/` directory is unchanged.
