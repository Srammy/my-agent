# AgentScope Event Error Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `stream()` 与 `confirm()` 都把 SDK 作为流元素发出的 `Throwable` 视为终止性错误，同时保留各自现有的错误响应边界。

**Architecture:** 在 `AgentScopeChatAgentGateway` 内提取共用的原始事件处理方法，并在调用 `AgentEventMapper` 前将 `Throwable` 转成 reactive error。`stream()` 继续用末端 `onErrorResume` 输出协议错误，`confirm()` 继续向 `ChatService` 传播 reactive error。

**Tech Stack:** Java 21、Project Reactor、JUnit 5、Mockito、AssertJ

---

### Task 1: 统一 Throwable 元素的终止语义

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- Test: `backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java`

- [ ] **Step 1: 编写失败测试**

在 `AgentScopeChatAgentGatewayTest` 增加测试，构造“文本事件、Throwable 元素、结束事件”的 SDK 流，并断言错误后的 `done` 不会被输出：

```java
@Test
void sdkThrowableEventTerminatesStreamBeforeLaterEvents() {
  when(executor.stream(any(ChatAgentRequest.class), any()))
      .thenReturn(Flux.just(
          new TextBlockDeltaEvent("reply-1", "block-1", "before error"),
          new IllegalArgumentException("sdk error event"),
          new AgentEndEvent("reply-1")));

  var events = gateway().stream(request()).collectList().block();

  assertThat(events).extracting(StreamEventDto::type)
      .containsExactly("text_delta", "error");
}
```

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `mvn -q -Dtest=AgentScopeChatAgentGatewayTest#sdkThrowableEventTerminatesStreamBeforeLaterEvents test`（工作目录 `backend`）

Expected: FAIL；实际类型包含 `text_delta, error, done`，证明当前 Throwable 元素没有终止流。

- [ ] **Step 3: 编写最小实现**

在 `AgentScopeChatAgentGateway` 增加共用方法：

```java
private Flux<StreamEventDto> mapAgentEvent(
    Long userId, String sessionId, Object agentEvent) {
  if (agentEvent instanceof Throwable throwable) {
    return Flux.error(throwable);
  }
  if (agentEvent instanceof RequireUserConfirmEvent confirmationEvent) {
    return registerUserConfirmation(userId, sessionId, confirmationEvent);
  }
  StreamEventDto mapped = agentEventMapper.map(agentEvent);
  return mapped == null ? Flux.empty() : Flux.just(mapped);
}
```

让 `stream()` 和 `confirm()` 的 `concatMap` 都调用该方法；只在 `stream()` 保留现有 `onErrorResume`。不修改 `AgentEventMapper`，避免扩大变更范围。

- [ ] **Step 4: 运行聚焦测试并确认通过**

Run: `mvn -q -Dtest=AgentScopeChatAgentGatewayTest test`（工作目录 `backend`）

Expected: PASS；包括现有 `confirmationPropagatesThrowableEvents`，证明 `confirm()` 仍传播 reactive error。

- [ ] **Step 5: 运行相关服务回归测试**

Run: `mvn -q -Dtest=AgentScopeChatAgentGatewayTest,ChatServiceTest test`（工作目录 `backend`）

Expected: PASS；确认 `ChatService` 仍将确认恢复失败转换为协议错误事件。

- [ ] **Step 6: 提交实现**

```bash
git add backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java backend/src/test/java/com/example/myagent/chat/AgentScopeChatAgentGatewayTest.java docs/superpowers/plans/2026-07-13-agent-event-error-semantics.md
git commit -m "fix: terminate streams on sdk throwable events"
```
