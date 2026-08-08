# Manual Skill Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure only the human approval endpoint can promote an Agent-created Skill draft into a formal Skill.

**Architecture:** Keep Agent file access behind `SkillApprovalGuardedFilesystem`, but make draft-to-formal moves fail there unconditionally. Let `SkillReviewService.approve` perform the already-approved, fingerprint-checked move through the workspace API filesystem while holding the draft lock. Make the curator gate defer approved candidates so it cannot become a second promotion path.

**Tech Stack:** Java 21, Spring Boot, AgentScope Harness filesystem, JUnit 5, AssertJ, Mockito, Maven, Docker Compose.

---

### Task 1: Lock the desired behavior with failing tests

**Files:**
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillApprovalGuardedFilesystemTest.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java`

- [ ] **Step 1: Change the Agent filesystem test**

Rename the test that currently expects an approved draft move to succeed so it expects `WriteResult.isSuccess()` to be false and verifies the draft remains in place.

- [ ] **Step 2: Add the read-only review regression**

Change the existing `listRepairsAnAlreadyApprovedDraftForTheRequestedUser` scenario to assert that listing leaves `skills/_drafts/tv-show-recommender/SKILL.md` present and does not create `skills/tv-show-recommender/SKILL.md`.

- [ ] **Step 3: Change the curator gate expectation**

Change `reviewApprovesWhenDecisionIsApproved` to expect `PromotionDecision.Defer` with the existing fingerprint validation still exercised.

- [ ] **Step 4: Run the focused tests and verify RED**

Run from `D:\ideaccproj\myagent\backend`:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn -q -Dtest=SkillApprovalGuardedFilesystemTest,SkillReviewServiceTest,WebApprovalGateTest test
```

Expected: failures show that the current Agent filesystem still promotes and the current list/gate still auto-promote.

### Task 2: Separate Agent writes from manual promotion

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillApprovalGuardedFilesystem.java`
- Modify: `backend/src/main/java/com/example/myagent/config/UserScopedFilesystemFactory.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/config/UserScopedFilesystemFactoryTest.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

- [ ] **Step 1: Make exact promotion moves fail in the Agent filesystem**

Return `WriteResult.fail("Skill promotion requires human approval action")` when `move` matches a complete draft-to-formal Skill move. Remove the now-unneeded `SkillPromotionGuard` dependency from this Agent-only filesystem and update its factory construction.

- [ ] **Step 2: Keep manual promotion on the workspace API filesystem**

Preserve `createWorkspaceApiFilesystem(userId)` as the unguarded, user-scoped filesystem used only by review service code; do not expose it to the Agent request filesystem.

- [ ] **Step 3: Update constructor wiring and focused factory tests**

Update all production/test constructors to match the removed Agent filesystem guard dependency, while retaining `SkillPromotionGuard` for the review service’s explicit manual operation.

- [ ] **Step 4: Run the focused filesystem tests**

Run the same Maven command from Task 1 and confirm the filesystem regression is green while service/gate tests still identify remaining behavior changes.

### Task 3: Make approval the sole promotion action

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`
- Modify: `backend/src/main/java/com/example/myagent/skillreview/WebApprovalGate.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/WebApprovalGateTest.java`

- [ ] **Step 1: Inject the promotion guard into the review service**

Add `SkillPromotionGuard` to `SkillReviewService` and its test setup.

- [ ] **Step 2: Promote inside the manual approval lock**

Keep the draft hash calculation, decision persistence, lock renewal, and promotion in the same `try (SkillDraftLock.Handle ...)` block. Call `SkillPromotionGuard.moveApprovedDraft` with `createWorkspaceApiFilesystem(userId)` and move `skills/_drafts/<skillName>` to `skills/<skillName>` only after the approval decision is saved.

- [ ] **Step 3: Remove list-triggered promotion**

Delete the `promoteApprovedDraft` call from `buildDto`; listing and status calculation must not mutate the workspace.

- [ ] **Step 4: Stop curator promotion**

Keep pending and rejected decisions safe, but return `PromotionDecision.Defer` for a valid approved candidate with a message that explicit human approval already owns promotion. This prevents curator from becoming an alternate promotion path.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn -q -Dtest=SkillApprovalGuardedFilesystemTest,SkillReviewServiceTest,WebApprovalGateTest test
```

Expected: all focused tests pass, including Agent rejection, manual approval promotion, read-only listing, and curator deferral.

### Task 4: Run the backend regression suite

**Files:**
- No additional files expected.

- [ ] **Step 1: Run the full backend test suite**

Run from `D:\ideaccproj\myagent\backend`:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; mvn -q test
```

Expected: Maven exits 0 with no test failures.

- [ ] **Step 2: Inspect the diff**

Run `git diff --check` and `git status --short`; confirm only the manual-promotion implementation, its tests, and the approved design/plan documents changed.

### Task 5: Rebuild and verify the running service

**Files:**
- No additional files expected.

- [ ] **Step 1: Rebuild and restart Compose services**

Run from `D:\ideaccproj\myagent`:

```powershell
docker compose up -d --build backend frontend
```

- [ ] **Step 2: Verify containers and HTTP endpoints**

Run `docker compose ps`, request `http://localhost:5173/`, and request an unauthenticated protected API such as `http://localhost:8080/api/auth/me`. Expected: backend/frontend are `Up`, MySQL/Redis remain healthy, frontend returns 200, protected API returns 401.
