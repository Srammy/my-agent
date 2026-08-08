# Hide Internal Paths in Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent assistant chat text from exposing internal Skill and workspace paths while preserving business-level wording and user-provided content.

**Architecture:** Add one small backend redactor for known internal path prefixes. Apply it to assistant text stream deltas before they reach the frontend and to assistant message DTOs when history is loaded; do not rewrite stored database content or alter user messages and tool event payloads.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Maven.

---

### Task 1: Add failing redaction tests

**Files:**

- Create: `backend/src/test/java/com/example/myagent/chat/InternalPathRedactorTest.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/AgentEventMapperTest.java`
- Modify: `backend/src/test/java/com/example/myagent/chat/ChatMessageServiceTest.java`

- [ ] **Step 1: Write the redactor behavior test**

Create `InternalPathRedactorTest` with a case asserting that known internal paths become business wording:

```java
@Test
void redactsKnownInternalPathsAndKeepsBusinessText() {
  String text = "草稿目录（`skills/_drafts/`）为空；文件位于 skills/jp_drama_recommend/SKILL.md；工作区 .agentscope/workspace/tmp。";

  assertThat(InternalPathRedactor.redact(text))
      .isEqualTo("草稿目录（草稿区域）为空；文件位于正式 Skill 区域；工作区 工作区。");
}
```

- [ ] **Step 2: Add the streaming regression test**

Add this test to `AgentEventMapperTest`:

```java
@Test
void mapsTextDeltaWithoutExposingInternalPaths() {
  StreamEventDto event =
      mapper.map(new TextBlockDeltaEvent("reply-1", "block-1", "草稿目录：skills/_drafts/"));

  assertThat(event.payload()).containsEntry("delta", "草稿目录：草稿区域");
}
```

- [ ] **Step 3: Add the history regression test**

Add this test to `ChatMessageServiceTest`:

```java
@Test
void toDtoRedactsAssistantPathsButKeepsUserPaths() {
  ChatMessageDto assistant =
      service.toDto(message("m_1", "assistant", "草稿目录：skills/_drafts/", "[]", CREATED_AT));
  ChatMessageDto user =
      service.toDto(message("m_2", "user", "请读取 skills/_drafts/example", "[]", CREATED_AT));

  assertThat(assistant.content()).isEqualTo("草稿目录：草稿区域");
  assertThat(user.content()).isEqualTo("请读取 skills/_drafts/example");
}
```

- [ ] **Step 4: Run the focused tests and verify the expected failure**

Run from `backend`:

```powershell
.\mvnw.cmd -q -Dtest=InternalPathRedactorTest,AgentEventMapperTest,ChatMessageServiceTest test
```

Expected: compilation/test failure because `InternalPathRedactor` does not exist and the new behavior is not implemented.

### Task 2: Implement backend redaction

**Files:**

- Create: `backend/src/main/java/com/example/myagent/chat/InternalPathRedactor.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatMessageService.java`

- [ ] **Step 1: Add the minimal redactor**

Create a final utility with ordered replacements so the specific draft prefix is handled before the general Skill prefix:

```java
private static final Pattern DRAFT_PATH =
    Pattern.compile("`?/?skills/_drafts(?:/[A-Za-z0-9._-]+)*/*`?");
private static final Pattern SKILL_PATH =
    Pattern.compile("`?/?skills/(?!_drafts(?:/|`|\\b))[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*/*`?");
private static final Pattern WORKSPACE_PATH =
    Pattern.compile("`?/?\\.agentscope/workspace(?:/[A-Za-z0-9._-]+)*/*`?");
```

The `redact(String)` method should return null/empty input unchanged, then replace matches with `草稿区域`, `正式 Skill 区域`, and `工作区` respectively.

- [ ] **Step 2: Redact assistant stream deltas**

In `AgentEventMapper`, wrap only `TextBlockDeltaEvent.getDelta()`:

```java
return StreamEventDto.textDelta(InternalPathRedactor.redact(textBlockDeltaEvent.getDelta()));
```

Leave tool result events and error payloads unchanged.

- [ ] **Step 3: Redact assistant history at DTO conversion**

In `ChatMessageService.toDto`, use redacted content only for assistant messages:

```java
String visibleContent =
    "assistant".equals(message.getRole())
        ? InternalPathRedactor.redact(message.getContent())
        : message.getContent();
```

Pass `visibleContent` to `ChatMessageDto`; do not update the entity or database.

- [ ] **Step 4: Run focused tests and verify they pass**

Run from `backend`:

```powershell
.\mvnw.cmd -q -Dtest=InternalPathRedactorTest,AgentEventMapperTest,ChatMessageServiceTest test
```

Expected: all selected tests pass.

### Task 3: Verify the complete backend and restart the service

**Files:**

- No additional source changes expected.

- [ ] **Step 1: Run the complete backend test suite**

Run from `backend`:

```powershell
.\mvnw.cmd -q test
```

Expected: Maven exits with code 0 and the test summary contains no new failures.

- [ ] **Step 2: Build and restart the backend container**

Run from the repository root:

```powershell
docker compose up -d --build backend
```

Expected: the backend image rebuilds and the backend container is running.

- [ ] **Step 3: Check the service health**

Run:

```powershell
docker compose ps backend
```

Expected: the backend service reports a running/healthy state.

- [ ] **Step 4: Review the final diff and commit implementation**

Run:

```powershell
git diff --check
git status --short
git add -- backend/src/main/java/com/example/myagent/chat/InternalPathRedactor.java backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java backend/src/main/java/com/example/myagent/chat/ChatMessageService.java backend/src/test/java/com/example/myagent/chat/InternalPathRedactorTest.java backend/src/test/java/com/example/myagent/chat/AgentEventMapperTest.java backend/src/test/java/com/example/myagent/chat/ChatMessageServiceTest.java
git commit -m "fix: hide internal paths from chat"
```

Expected: only the listed implementation/test files are committed; unrelated `.env` and existing untracked plan files remain untouched.
