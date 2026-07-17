# Skill Visibility Without Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep user Workspace Skills visible to HarnessAgent when the Skill management tool is disabled.

**Architecture:** Restore AgentScope RC4's default read-only user-scoped `WorkspaceSkillRepository` by removing the unconditional opt-out. Keep `manageToolEnabled` responsible only for enabling the writable management, promotion, and curator path.

**Tech Stack:** Java 21, Spring Boot 3.3.5, AgentScope Harness 2.0.0-RC4, JUnit 5, AssertJ, Maven.

## Global Constraints

- Work directly in `codex/fix-skill-visibility-without-management`; do not create a worktree.
- Preserve user-level Skill isolation and cross-session sharing through the fixed-user `AbstractFilesystem`.
- Do not change Redis paths, API routes, frontend behavior, approval records, or AgentScope dependencies.
- `manageToolEnabled=false` must leave management, promotion, and curator disabled.
- `manageToolEnabled=true` must retain the existing management and curator behavior.
- Do not modify or stage the pre-existing `.claude/` directory.

---

### Task 1: Decouple Workspace Skill visibility from management

**Files:**
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`

**Interfaces:**
- Consumes: `AgentScopeConfig.configureHarnessAgentBuilder(...)`, `AgentScopeConfig.applySkillLearning(...)`, `AgentProperties.Skill.manageToolEnabled()`.
- Produces: Builder configuration where `disableDefaultWorkspaceSkills=false` independently of `skillManageToolEnabled`.

- [ ] **Step 1: Add a properties helper that can disable Skill management**

Keep all existing callers unchanged by delegating the current four-argument helper to a new five-argument overload:

```java
private AgentProperties properties(
    boolean fileToolsEnabled,
    boolean shellEnabled,
    boolean httpFetchEnabled,
    boolean mcpEnabled) {
  return properties(
      fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled, true);
}

private AgentProperties properties(
    boolean fileToolsEnabled,
    boolean shellEnabled,
    boolean httpFetchEnabled,
    boolean mcpEnabled,
    boolean manageToolEnabled) {
  return new AgentProperties(
      new AgentProperties.AgentScope(true),
      new AgentProperties.Workspace(tempDir.toString()),
      new AgentProperties.Model(
          "dashscope", "dashscope:qwen-plus", "", "DASHSCOPE_API_KEY"),
      new AgentProperties.StateStore(
          "redis",
          new AgentProperties.StateStore.Redis(
              "redis://localhost:6379", "myagent:")),
      new AgentProperties.Skill(
          "agentscope", "prod", 10, manageToolEnabled, true),
      new AgentProperties.Permission("DEFAULT"),
      new AgentProperties.Tools(
          fileToolsEnabled, shellEnabled, httpFetchEnabled, mcpEnabled));
}
```

- [ ] **Step 2: Write the failing configuration regression test**

Add this test beside the existing Skill learning test:

```java
@Test
void workspaceSkillsRemainVisibleWhenManagementIsDisabled() throws Exception {
  HarnessAgent.Builder builder = HarnessAgent.builder();
  AgentProperties props = properties(false, false, false, false, false);
  io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store =
      new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
  io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem fs =
      new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store);
  SkillUsageStore usageStore = new SkillUsageStore(fs);
  SkillReviewDecisionStore decisionStore = new SkillReviewDecisionStore(fs);
  WebApprovalGate webApprovalGate =
      new WebApprovalGate(decisionStore, mock(SkillDraftFingerprint.class));

  config.configureHarnessAgentBuilder(builder, config.toolPolicy(props), props);
  config.applySkillLearning(builder, props, usageStore, webApprovalGate);

  assertThat(booleanField(builder, "disableDefaultWorkspaceSkills")).isFalse();
  assertThat(booleanField(builder, "skillManageToolEnabled")).isFalse();
  assertThat(booleanField(builder, "skillCuratorEnabled")).isFalse();
}
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```powershell
mvn -q -f backend/pom.xml "-Dtest=AgentScopeConfigTest#workspaceSkillsRemainVisibleWhenManagementIsDisabled" test
```

Expected: FAIL because `disableDefaultWorkspaceSkills` is currently `true`.

- [ ] **Step 4: Implement the minimal production fix**

In `configureHarnessAgentBuilder`, remove only the Workspace Skill opt-out and its now-incorrect comment:

```java
return builder
    .disableSubagents()
    .disableDynamicSubagents();
```

Do not change `applySkillLearning`; its early return continues to disable management, promotion, and curator.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run:

```powershell
mvn -q -f backend/pom.xml "-Dtest=AgentScopeConfigTest#workspaceSkillsRemainVisibleWhenManagementIsDisabled,AgentScopeConfigTest#applySkillLearning_enablesSkillManageTool_whenConfigured" test
```

Expected: both tests pass.

- [ ] **Step 6: Run the complete configuration test class**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=AgentScopeConfigTest test
```

Expected: all `AgentScopeConfigTest` tests pass.

- [ ] **Step 7: Review and commit the implementation**

Run `git diff --check` and confirm only the two target Java files changed, in addition to the committed design and plan documents. Commit the Java changes:

```powershell
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "fix: keep workspace skills visible without management"
```

---

### Task 2: Verify and integrate the branch

**Files:**
- Verify all committed files on `codex/fix-skill-visibility-without-management`.
- Merge into `codex/skill-review-draft-fingerprint`.

**Interfaces:**
- Consumes: Task 1's Builder configuration and regression test.
- Produces: verified merge commit on `codex/skill-review-draft-fingerprint`.

- [ ] **Step 1: Run full branch verification**

Run:

```powershell
git diff --check codex/skill-review-draft-fingerprint...HEAD
mvn -q -f backend/pom.xml clean test
```

Expected: no whitespace errors; Maven exits `0`; Surefire reports zero failures, errors, and skips.

- [ ] **Step 2: Confirm branch cleanliness**

Run `git status --short --branch`. Expected: only the branch header and the pre-existing untracked `.claude/` directory.

- [ ] **Step 3: Merge locally**

```powershell
git switch codex/skill-review-draft-fingerprint
git merge --no-ff codex/fix-skill-visibility-without-management -m "merge: keep workspace skills visible without management"
```

- [ ] **Step 4: Run full merged verification**

Run:

```powershell
mvn -q -f backend/pom.xml clean test
```

Expected: Maven exits `0`; Surefire reports zero failures, errors, and skips.

- [ ] **Step 5: Delete the fully merged temporary branch**

After `git branch --merged HEAD` lists the temporary branch, run:

```powershell
git branch -d codex/fix-skill-visibility-without-management
```

Expected: Git confirms the branch was deleted; `.claude/` remains untouched and untracked.
