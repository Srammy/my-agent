# Skill Promotion User Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make AgentScope skill promotion, usage metadata, and review usage queries operate in the authenticated user's namespace even when AgentScope passes `RuntimeContext.empty()`.

**Architecture:** A shared Redis `BaseStore` backs two filesystem views. Web/API code keeps the context-resolved `workspaceFilesystem`; each HarnessAgent request and each review usage query receives a `RemoteFilesystem` whose namespace is fixed to one user by `UserScopedFilesystemFactory`.

**Tech Stack:** Java 21, Spring Boot, AgentScope Harness 2.0.0-RC4, Reactor, Redis, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- Keep `io.agentscope:agentscope-harness` at `2.0.0-RC4`; upgrading does not fix this defect.
- Preserve user-level sharing across sessions: namespace by `userId`, never by `sessionId`.
- Do not copy, shadow, or patch AgentScope internal classes.
- Do not solve the approval-to-move TOCTOU race in this branch.
- Work directly in the existing workspace; do not create a worktree.
- Create branch `codex/fix-skill-promotion-user-scope` from `codex/skill-review-draft-fingerprint` and merge it back after verification.
- Do not stage or modify the user's untracked `.claude/` directory.

## File Structure

- Create `backend/src/main/java/com/example/myagent/config/UserScopedFilesystemFactory.java`: construct and reuse fixed-user `RemoteFilesystem` and `SkillUsageStore` views over the shared `BaseStore`.
- Create `backend/src/test/java/com/example/myagent/config/UserScopedFilesystemFactoryTest.java`: prove empty-context operations stay inside the bound user namespace.
- Modify `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`: expose the shared `BaseStore`, inject the bound filesystem into each HarnessAgent, and construct its `SkillUsageStore` from the same view.
- Modify `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`: verify the builder receives a filesystem bound to the request user.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`: create `SkillUsageStore` from the requested user's filesystem instead of using a global singleton.
- Modify `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`: verify review usage counts are user-specific.

---

### Task 1: Fixed-user filesystem factory

**Files:**
- Create: `backend/src/test/java/com/example/myagent/config/UserScopedFilesystemFactoryTest.java`
- Create: `backend/src/main/java/com/example/myagent/config/UserScopedFilesystemFactory.java`

**Interfaces:**
- Consumes: AgentScope `BaseStore` and `RemoteFilesystem(BaseStore, List<String>)`.
- Produces: `public AbstractFilesystem create(String userId)`.

- [ ] **Step 1: Create the issue-specific branch**

Run:

```powershell
git switch -c codex/fix-skill-promotion-user-scope
```

Expected: current branch becomes `codex/fix-skill-promotion-user-scope` and `.claude/` remains untracked.

- [ ] **Step 2: Write the failing factory tests**

Create `UserScopedFilesystemFactoryTest.java`:

```java
package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;

class UserScopedFilesystemFactoryTest {

  @Test
  void emptyContextMovesOnlyTheBoundUsersDraft() {
    InMemoryStore store = new InMemoryStore();
    UserScopedFilesystemFactory factory = new UserScopedFilesystemFactory(store);
    AbstractFilesystem aliceFilesystem = factory.create("101");
    AbstractFilesystem sharedFilesystem =
        new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    RuntimeContext empty = RuntimeContext.empty();
    RuntimeContext aliceSessionOne = context("101", "s-1");
    RuntimeContext aliceSessionTwo = context("101", "s-2");
    RuntimeContext bob = context("102", "s-1");

    assertThat(
            aliceFilesystem
                .write(
                    empty,
                    "skills/_drafts/reviewer/SKILL.md",
                    "---\nname: reviewer\ndescription: Review code\n---\n")
                .isSuccess())
        .isTrue();
    assertThat(
            aliceFilesystem
                .move(empty, "skills/_drafts/reviewer", "skills/reviewer")
                .isSuccess())
        .isTrue();

    assertThat(sharedFilesystem.exists(aliceSessionOne, "skills/reviewer/SKILL.md")).isTrue();
    assertThat(sharedFilesystem.exists(aliceSessionTwo, "skills/reviewer/SKILL.md")).isTrue();
    assertThat(sharedFilesystem.exists(bob, "skills/reviewer/SKILL.md")).isFalse();
  }

  @Test
  void rejectsBlankUserId() {
    UserScopedFilesystemFactory factory = new UserScopedFilesystemFactory(new InMemoryStore());

    assertThatThrownBy(() -> factory.create(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId");
  }

  @Test
  void reusesFilesystemAndUsageStoreWithinTheSameUser() {
    UserScopedFilesystemFactory factory = new UserScopedFilesystemFactory(new InMemoryStore());

    assertThat(factory.create("101")).isSameAs(factory.create("101"));
    assertThat(factory.usageStore("101")).isSameAs(factory.usageStore("101"));
    assertThat(factory.usageStore("101")).isNotSameAs(factory.usageStore("102"));
  }

  private static RuntimeContext context(String userId, String sessionId) {
    return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
  }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=UserScopedFilesystemFactoryTest test
```

Expected: compilation fails because `UserScopedFilesystemFactory` does not exist.

- [ ] **Step 4: Implement the minimal factory**

Create `UserScopedFilesystemFactory.java`:

```java
package com.example.myagent.config;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class UserScopedFilesystemFactory {

  private final BaseStore store;
  private final ConcurrentMap<String, AbstractFilesystem> filesystems = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, SkillUsageStore> usageStores = new ConcurrentHashMap<>();

  public UserScopedFilesystemFactory(BaseStore store) {
    this.store = store;
  }

  public AbstractFilesystem create(String userId) {
    validateUserId(userId);
    return filesystems.computeIfAbsent(
        userId, id -> new RemoteFilesystem(store, List.of(id)));
  }

  public SkillUsageStore usageStore(String userId) {
    validateUserId(userId);
    return usageStores.computeIfAbsent(userId, id -> new SkillUsageStore(create(id)));
  }

  private static void validateUserId(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
  }
}
```

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=UserScopedFilesystemFactoryTest test
```

Expected: 3 tests pass, including same-user instance reuse and different-user separation.

- [ ] **Step 6: Commit the factory**

```powershell
git add backend/src/main/java/com/example/myagent/config/UserScopedFilesystemFactory.java backend/src/test/java/com/example/myagent/config/UserScopedFilesystemFactoryTest.java
git commit -m "feat: add user-scoped workspace filesystem"
```

### Task 2: Bind each HarnessAgent request to its user

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java`

**Interfaces:**
- Consumes: `UserScopedFilesystemFactory.create(String userId)` from Task 1.
- Produces: a HarnessAgent whose `abstractFilesystem` and `SkillUsageStore` use the request user's fixed namespace.

- [ ] **Step 1: Write the failing configuration assertion**

In `confirmationExecutorResumesGroupedDecisionsWithOneMessageAndRequestScope`, replace the mocked
`SkillUsageStore` argument with an actual factory over an `InMemoryStore`, capture the injected filesystem,
and add these assertions after the executor completes:

```java
    io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore workspaceStore =
        new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
    UserScopedFilesystemFactory filesystemFactory =
        new UserScopedFilesystemFactory(workspaceStore);
```

Pass `filesystemFactory` to `agentScopeStreamExecutor`, then verify:

```java
    ArgumentCaptor<io.agentscope.harness.agent.filesystem.AbstractFilesystem> filesystemCaptor =
        ArgumentCaptor.forClass(io.agentscope.harness.agent.filesystem.AbstractFilesystem.class);
    verify(builder).abstractFilesystem(filesystemCaptor.capture());
    assertThat(
            filesystemCaptor
                .getValue()
                .write(RuntimeContext.empty(), "skills/request-scope.txt", "bound")
                .isSuccess())
        .isTrue();

    io.agentscope.harness.agent.filesystem.AbstractFilesystem sharedFilesystem =
        new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            workspaceStore,
            io.agentscope.harness.agent.IsolationScope.USER.toNamespaceFactory());
    assertThat(
            sharedFilesystem.exists(
                RuntimeContext.builder().userId("7").sessionId("another-session").build(),
                "skills/request-scope.txt"))
        .isTrue();
    assertThat(
            sharedFilesystem.exists(
                RuntimeContext.builder().userId("8").sessionId("s_123").build(),
                "skills/request-scope.txt"))
        .isFalse();
```

- [ ] **Step 2: Run the configuration test and verify RED**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=AgentScopeConfigTest test
```

Expected: compilation fails because `agentScopeStreamExecutor` still accepts a singleton
`SkillUsageStore`, and the builder is still configured through `RemoteFilesystemSpec`.

- [ ] **Step 3: Expose the shared store and factory Beans**

Replace the existing workspace and usage-store Beans with:

```java
  @Bean
  BaseStore workspaceBaseStore(
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider) {
    return buildBaseStore(agentProperties, redisTemplateProvider);
  }

  @Bean
  AbstractFilesystem workspaceFilesystem(BaseStore workspaceBaseStore) {
    return new RemoteFilesystem(
        workspaceBaseStore, IsolationScope.USER.toNamespaceFactory());
  }

  @Bean
  UserScopedFilesystemFactory userScopedFilesystemFactory(BaseStore workspaceBaseStore) {
    return new UserScopedFilesystemFactory(workspaceBaseStore);
  }
```

Delete the singleton `SkillUsageStore skillUsageStore(...)` Bean.

- [ ] **Step 4: Inject the request-bound filesystem into HarnessAgent**

Replace `agentScopeStreamExecutor` with:

```java
  @Bean
  @ConditionalOnProperty(prefix = "agent.agent-scope", name = "enabled", havingValue = "true")
  AgentScopeStreamExecutor agentScopeStreamExecutor(
      Model agentScopeModel,
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider,
      UserScopedFilesystemFactory filesystemFactory,
      WebApprovalGate webApprovalGate) {
    return new AgentScopeStreamExecutor() {
      @Override
      public reactor.core.publisher.Flux<Object> stream(
          ChatAgentRequest request, Object runtimeContext) {
        return reactor.core.publisher.Flux.using(
            () ->
                buildHarnessAgent(
                    agentScopeModel,
                    agentProperties,
                    redisTemplateProvider,
                    requestScope(request),
                    filesystemFactory,
                    webApprovalGate),
            harnessAgent ->
                harnessAgent
                    .streamEvents(request.message(), (RuntimeContext) runtimeContext)
                    .cast(Object.class),
            HarnessAgent::close);
      }

      @Override
      public reactor.core.publisher.Flux<Object> confirm(
          ChatToolConfirmationRequest request, Object runtimeContext) {
        return reactor.core.publisher.Flux.using(
            () ->
                buildHarnessAgent(
                    agentScopeModel,
                    agentProperties,
                    redisTemplateProvider,
                    requestScope(request),
                    filesystemFactory,
                    webApprovalGate),
            harnessAgent ->
                harnessAgent
                    .streamEvents(confirmationMessage(request), (RuntimeContext) runtimeContext)
                    .cast(Object.class),
            HarnessAgent::close);
      }
    };
  }
```

Change `buildHarnessAgent` to create and reuse the request view:

```java
  HarnessAgent buildHarnessAgent(
      Model agentScopeModel,
      AgentProperties agentProperties,
      ObjectProvider<ReactiveStringRedisTemplate> redisTemplateProvider,
      AgentRequestScope requestScope,
      UserScopedFilesystemFactory filesystemFactory,
      WebApprovalGate webApprovalGate) {
    HarnessAgent.Builder builder = HarnessAgent.builder().name("myagent").model(agentScopeModel);
    String userId = requestScope.userId().toString();
    AbstractFilesystem userFilesystem = filesystemFactory.create(userId);
    configureHarnessAgentBuilder(builder, toolPolicy(agentProperties), agentProperties);
    applyRequestScope(builder, requestScope);
    applyDistributedStore(builder, agentProperties, redisTemplateProvider);
    applyFilesystem(builder, agentProperties, userFilesystem);
    applySkillLearning(
        builder, agentProperties, filesystemFactory.usageStore(userId), webApprovalGate);
    return builder.build();
  }
```

Replace `applyFilesystem` with:

```java
  void applyFilesystem(
      HarnessAgent.Builder builder,
      AgentProperties agentProperties,
      AbstractFilesystem userFilesystem) {
    builder.workspace(agentProperties.workspace().path());
    builder.abstractFilesystem(userFilesystem);
  }
```

Remove the now-unused `RemoteFilesystemSpec` import.

- [ ] **Step 5: Run focused configuration tests and verify GREEN**

Run:

```powershell
mvn -q -f backend/pom.xml "-Dtest=AgentScopeConfigTest,UserScopedFilesystemFactoryTest" test
```

Expected: all focused tests pass; captured empty-context writes are visible to user `7` and invisible to user `8`.

- [ ] **Step 6: Commit the HarnessAgent wiring**

```powershell
git add backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java
git commit -m "fix: bind skill promotion to request user"
```

### Task 3: Read review usage from the authenticated user

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java`
- Modify: `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java`

**Interfaces:**
- Consumes: `UserScopedFilesystemFactory.create(String userId)` from Task 1.
- Produces: review DTO usage fields read from `userId/skills/.usage.json`.

- [ ] **Step 1: Replace the mocked global usage store and write a failing isolation test**

In `SkillReviewServiceTest`, replace the `SkillUsageStore usageStore` mock with:

```java
  private UserScopedFilesystemFactory filesystemFactory;
```

Add the import:

```java
import com.example.myagent.config.UserScopedFilesystemFactory;
```

Update `setUp`:

```java
  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    decisionStore = mock(SkillReviewDecisionStore.class);
    fingerprint = mock(SkillDraftFingerprint.class);
    filesystemFactory =
        new UserScopedFilesystemFactory(
            new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore());
    service =
        new SkillReviewService(filesystem, decisionStore, filesystemFactory, fingerprint);
  }
```

Delete `when(usageStore.get("my-skill")).thenReturn(Optional.empty())` stubs and add:

```java
  @Test
  void listReadsUsageFromTheRequestedUserOnly() {
    stubListedDraft();
    when(decisionStore.find("my-skill", "1")).thenReturn(Optional.empty());
    when(decisionStore.find("my-skill", "2")).thenReturn(Optional.empty());
    SkillUsageStore aliceUsage =
        new SkillUsageStore(filesystemFactory.create("1"));
    SkillUsageStore bobUsage =
        new SkillUsageStore(filesystemFactory.create("2"));
    aliceUsage.markAgentDraft("my-skill", "alice-session");
    bobUsage.markAgentDraft("my-skill", "bob-session");
    aliceUsage.bumpUse("my-skill");
    bobUsage.bumpUse("my-skill");
    bobUsage.bumpUse("my-skill");

    assertThat(service.list("1").getFirst().useCount()).isEqualTo(1);
    assertThat(service.list("2").getFirst().useCount()).isEqualTo(2);
  }
```

- [ ] **Step 2: Run the service test and verify RED**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=SkillReviewServiceTest test
```

Expected: compilation fails because `SkillReviewService` still expects a singleton `SkillUsageStore`.

- [ ] **Step 3: Create user-scoped usage stores inside the service**

Replace the `SkillUsageStore` field and constructor argument with:

```java
  private final UserScopedFilesystemFactory filesystemFactory;

  public SkillReviewService(
      AbstractFilesystem filesystem,
      SkillReviewDecisionStore decisionStore,
      UserScopedFilesystemFactory filesystemFactory,
      SkillDraftFingerprint fingerprint) {
    this.filesystem = filesystem;
    this.decisionStore = decisionStore;
    this.filesystemFactory = filesystemFactory;
    this.fingerprint = fingerprint;
  }
```

Import `com.example.myagent.config.UserScopedFilesystemFactory` and add:

```java
  private SkillUsageStore usageStore(String userId) {
    return filesystemFactory.usageStore(userId);
  }
```

Create one user-scoped store per service operation:

```java
  public List<SkillReviewDto> list(String userId) {
    RuntimeContext ctx = userContext(userId);
    SkillUsageStore usageStore = usageStore(userId);
    if (!filesystem.exists(ctx, DRAFTS_DIR)) {
      return List.of();
    }
    LsResult result = filesystem.ls(ctx, DRAFTS_DIR);
    if (!result.isSuccess()) {
      return List.of();
    }
    return result.entries().stream()
        .filter(FileInfo::isDirectory)
        .map(FileInfo::path)
        .sorted()
        .map(skillName -> buildDto(ctx, skillName, userId, usageStore))
        .toList();
  }
```

Use these return statements in `approve` and `reject`:

```java
    return toDto(skillName, decision, usageStore(userId));
```

Change the helper signatures to:

```java
  private SkillReviewDto buildDto(
      RuntimeContext ctx,
      String skillName,
      String userId,
      SkillUsageStore usageStore) {
```

```java
  private SkillReviewDto toDto(
      String skillName,
      SkillReviewDecision decision,
      SkillUsageStore usageStore) {
```

Inside both methods, retain the existing expression below so it reads from the method parameter rather
than a singleton field:

```java
    Optional<SkillUsageRecord> maybeUsage = usageStore.get(skillName);
```

- [ ] **Step 4: Run focused service tests and verify GREEN**

Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=SkillReviewServiceTest test
```

Expected: all service tests pass, including distinct counts for users `1` and `2`.

- [ ] **Step 5: Commit review usage isolation**

```powershell
git add backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java
git commit -m "fix: isolate skill usage by review user"
```

### Task 4: Verify, review, and merge the issue branch

**Files:**
- Verify all files changed in Tasks 1-3.
- Merge branch history into `codex/skill-review-draft-fingerprint`.

**Interfaces:**
- Consumes: completed issue branch.
- Produces: verified merge on the integration branch.

- [ ] **Step 1: Run the full backend suite from a clean build**

Run:

```powershell
mvn -q -f backend/pom.xml clean test
```

Expected: all backend tests pass with zero failures and zero errors.

- [ ] **Step 2: Inspect scope and whitespace**

Run:

```powershell
git diff --check codex/skill-review-draft-fingerprint...HEAD
git status --short --branch
git diff --stat codex/skill-review-draft-fingerprint...HEAD
```

Expected: no whitespace errors; only the planned Java files and tests differ; `.claude/` remains untracked.

- [ ] **Step 3: Perform a focused self-review**

Confirm from the diff and tests that:

- no empty or null `userId` can create a fixed filesystem;
- HarnessAgent and its `SkillUsageStore` share the same fixed-user filesystem instance;
- the same user reuses one `SkillUsageStore` instance inside the process so concurrent sessions retain its lock;
- Web/API decision and fingerprint code still use explicit runtime contexts;
- no `sessionId` is included in the fixed namespace;
- no TOCTOU logic was added in this branch;
- no unrelated source formatting or cleanup is present.

- [ ] **Step 4: Merge back to the integration branch**

Run:

```powershell
git switch codex/skill-review-draft-fingerprint
git merge --no-ff codex/fix-skill-promotion-user-scope -m "merge: fix user-scoped skill promotion"
```

Expected: merge commit succeeds on `codex/skill-review-draft-fingerprint`.

- [ ] **Step 5: Verify the merged integration branch**

Run:

```powershell
mvn -q -f backend/pom.xml clean test
git status --short --branch
```

Expected: all backend tests pass; current branch is `codex/skill-review-draft-fingerprint`; only `.claude/` is untracked.
