# Skill Review List Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse AgentScope `FileInfo.path` values into valid direct-child skill names before building review DTOs or querying decisions and fingerprints.

**Architecture:** Keep the filesystem contract unchanged and normalize only at the `SkillReviewService.list` boundary. A private helper accepts official full paths and compatible bare names, while rejecting paths outside `_drafts`, nested paths, and invalid skill names.

**Tech Stack:** Java 21, Spring Boot, AgentScope Harness 2.0.0-RC4, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Work directly in the existing workspace; do not create a worktree.
- Create branch `codex/fix-skill-review-list-path` from `codex/skill-review-draft-fingerprint` and merge it back after verification.
- Do not change `AbstractFilesystem.ls` or AgentScope classes.
- Do not modify approval status, fingerprint, promotion, TOCTOU, or filesystem exception behavior.
- Do not stage or modify the user's untracked `.claude/` directory.

## File Structure

- Modify `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`: use official full paths and cover compatibility and rejection cases.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`: normalize directory entries before downstream use.

---

### Task 1: Normalize review-list directory entries

**Files:**
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`

**Interfaces:**
- Consumes: `FileInfo.path()` values from `filesystem.ls(ctx, "skills/_drafts")`.
- Produces: `private static Optional<String> draftSkillName(String path)`.

- [ ] **Step 1: Create the issue branch**

```powershell
git switch -c codex/fix-skill-review-list-path
```

Expected: current branch is `codex/fix-skill-review-list-path`; `.claude/` remains untracked.

- [ ] **Step 2: Make existing tests use the official full path**

Change the default stub entry to:

```java
FileInfo.ofDir("/skills/_drafts/my-skill", "2026-07-08T09:00:00")
```

Refactor the helper so compatibility tests can select the entry path:

```java
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
```

- [ ] **Step 3: Add compatibility and invalid-entry tests**

Add:

```java
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
```

- [ ] **Step 4: Run tests and verify RED**

```powershell
mvn -q -f backend/pom.xml -Dtest=SkillReviewServiceTest test
```

Expected: failures show `/skills/_drafts/my-skill` being used as the DTO name and invalid entries being returned.

- [ ] **Step 5: Implement the boundary parser**

Add the import:

```java
import com.example.myagent.skill.SkillPathValidator;
```

Change the list stream to:

```java
    return result.entries().stream()
        .filter(FileInfo::isDirectory)
        .map(FileInfo::path)
        .map(SkillReviewService::draftSkillName)
        .flatMap(Optional::stream)
        .sorted()
        .map(skillName -> buildDto(ctx, skillName, userId, usageStore))
        .toList();
```

Add:

```java
  private static Optional<String> draftSkillName(String path) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    String normalized = path.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/") && !normalized.isEmpty()) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }

    String prefix = DRAFTS_DIR + "/";
    String candidate;
    if (normalized.startsWith(prefix)) {
      candidate = normalized.substring(prefix.length());
      if (candidate.contains("/")) {
        return Optional.empty();
      }
    } else if (!normalized.contains("/")) {
      candidate = normalized;
    } else {
      return Optional.empty();
    }

    try {
      return Optional.of(SkillPathValidator.validateSkillName(candidate));
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }
```

- [ ] **Step 6: Run focused tests and verify GREEN**

```powershell
mvn -q -f backend/pom.xml -Dtest=SkillReviewServiceTest test
```

Expected: all `SkillReviewServiceTest` tests pass; full path and bare name both resolve to `my-skill`, invalid entries are ignored.

- [ ] **Step 7: Commit the issue fix**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java
git commit -m "fix: parse skill review list paths"
```

### Task 2: Verify and merge

**Files:**
- Verify the two Java files from Task 1.
- Merge into `codex/skill-review-draft-fingerprint`.

**Interfaces:**
- Consumes: completed issue branch.
- Produces: verified merge on the integration branch.

- [ ] **Step 1: Run a clean full backend suite**

```powershell
mvn -q -f backend/pom.xml clean test
```

Expected: all tests pass with zero failures and zero errors.

- [ ] **Step 2: Review scope**

```powershell
git diff --check codex/skill-review-draft-fingerprint...HEAD
git diff --stat codex/skill-review-draft-fingerprint...HEAD
git status --short --branch
```

Expected: only the planned service and test differ; `.claude/` remains untracked.

- [ ] **Step 3: Merge and re-verify**

```powershell
git switch codex/skill-review-draft-fingerprint
git merge --no-ff codex/fix-skill-review-list-path -m "merge: fix skill review list paths"
mvn -q -f backend/pom.xml clean test
```

Expected: merge succeeds and the merged integration branch has zero test failures and errors.

- [ ] **Step 4: Delete the fully merged temporary branch**

```powershell
git branch -d codex/fix-skill-review-list-path
```

Expected: Git confirms the branch was deleted without force.
