# Tool Permission HITL Design

## Goal

Fix the current permission confirmation gap for AgentScope tool calls.

Today the app can surface `permission_required`, but users cannot approve or reject one concrete suspended tool call and continue the same AgentScope run. The fix should support a minimal human-in-the-loop loop:

1. AgentScope asks for confirmation before executing a tool.
2. The frontend shows the specific pending tool call.
3. The user chooses allow once or reject once.
4. The backend sends the confirmation result back to the same AgentScope session.
5. The resumed stream continues without switching the whole session to `BYPASS`.

This design intentionally does not add persistent "always allow" rules yet.

## Current Context

The current code maps AgentScope permission events in `AgentEventMapper`:

- `RequireUserConfirmEvent` becomes `permission_required`.
- `RequireExternalExecutionEvent` also becomes `permission_required`.

The event payload currently only exposes a `permission` string, usually the first tool name. The frontend can display that a permission is required, but it has no stable confirmation id, no full tool call payload, and no endpoint to send a `ConfirmResult`.

Permission mode is stored per session. That is useful for coarse behavior, but changing a session mode is not the same as approving one suspended tool call.

AgentScope RC4 exposes the event classes needed for the official-style loop:

- `ConfirmResult`
- `UserConfirmResultEvent`
- `ExternalExecutionResultEvent`
- `RequireUserConfirmEvent`
- `RequireExternalExecutionEvent`

## Recommended Approach

Add a small confirmation bridge in the backend and wire the frontend to it.

The backend will store pending confirmation records keyed by session and a generated confirmation id. The stream event sent to the frontend will include enough data for the UI to render the pending tool call and enough identifiers for a later approval request.

When the user approves or rejects, the frontend calls a new endpoint. The backend checks session ownership, looks up the pending record, creates `ConfirmResult`, wraps it in `UserConfirmResultEvent`, and feeds it back into the AgentScope stream/resume path for the same session.

## Backend Components

### Pending Confirmation Store

Create a narrow service, for example `ToolConfirmationService`, with in-memory storage first.

Each record stores:

- `confirmationId`
- `userId`
- `sessionId`
- `replyId`
- `toolCallId`
- `toolName`
- `toolInput`
- original `ToolUseBlock`
- confirmation type: user confirm or external execution
- created timestamp
- status: pending, approved, rejected, consumed

The first implementation can be process-local because the existing stream is already held by the current backend process. If multi-instance resume is required later, this store can move to Redis next to the AgentScope distributed store.

### Stream Event Payload

Extend `StreamEventDto.permissionRequired(...)` to include:

- `confirmationId`
- `replyId`
- `toolCallId`
- `toolName`
- `toolInput`
- `kind`

Keep the old `permission` field as a compatibility alias for `toolName`.

### Approval Endpoint

Add an endpoint similar to:

```http
POST /api/sessions/{sessionId}/tool-confirmations/{confirmationId}
{
  "confirmed": true
}
```

Rules:

- The current user must own the session.
- The confirmation must exist and belong to that session and user.
- A consumed confirmation cannot be reused.
- Approval and rejection are single-use.

The response should return a normal stream of chat events if the current HTTP/SSE design allows that cleanly. If the existing client architecture makes a streaming POST awkward, the endpoint can return an accepted result and the frontend can open a follow-up stream using the confirmation id. The implementation should choose the smaller change that preserves one actual AgentScope resume, not a full re-prompt.

### AgentScope Resume Bridge

The stream executor abstraction currently accepts a user message and `RuntimeContext`. It should grow a second operation for confirmation resume, or a small sibling abstraction should be added:

```java
Flux<Object> confirm(ChatToolConfirmationRequest request, Object runtimeContext)
```

The AgentScope-facing implementation will construct:

```java
new ConfirmResult(confirmed, toolUseBlock)
new UserConfirmResultEvent(replyId, List.of(confirmResult))
```

and feed that event into the same AgentScope session state.

If RC4 requires using `interrupt(...)` or `observe(...)` rather than direct event submission, the implementation should wrap that detail behind the bridge so controllers and frontend code do not depend on AgentScope internals.

## Frontend Components

Update the permission card to display the pending tool name and compact input preview.

Add two actions:

- Allow once
- Reject once

The actions call the new confirmation endpoint with `confirmed: true` or `false`. While the request is pending, disable both buttons. After the response stream resumes, append returned events to the same chat message.

The existing session-level permission panel remains available, but it should not be used as the main way to approve a single tool call.

## Error Handling

Return 404 when the session or confirmation does not belong to the current user.

Return 409 when the confirmation has already been consumed.

Return 400 for malformed confirmation requests.

If AgentScope resume fails, surface a normal `error` stream event and mark the confirmation consumed only if AgentScope accepted the confirmation event.

## Tests

Backend tests:

- `RequireUserConfirmEvent` mapping registers a pending confirmation and emits the confirmation metadata.
- Approval rejects sessions not owned by the current user.
- Approval rejects missing or already consumed confirmations.
- Approval creates `ConfirmResult(true, toolCall)` for allow once.
- Rejection creates `ConfirmResult(false, toolCall)` for reject once.
- The confirmation bridge uses the original `ToolUseBlock`, not user-supplied tool input.

Frontend tests:

- `permission_required` events with confirmation metadata render allow and reject buttons.
- Clicking allow/reject calls the confirmation API with the correct session and confirmation id.
- Buttons are disabled while the confirmation request is pending.

## Out Of Scope

- Persistent "always allow" or "always deny" tool rules.
- A UI for editing AgentScope `PermissionRule` objects.
- Support for externally executed tools returning arbitrary `ToolResultBlock` payloads, beyond preserving the existing `RequireExternalExecutionEvent` display.
- Changing default session permission modes.
- Migrating from AgentScope RC4 to GA.

## Open Implementation Detail

The only detail that must be verified during implementation is the exact RC4 resume API. The public classes prove that `ConfirmResult` and `UserConfirmResultEvent` exist, but the code must confirm whether the event is passed through an event sink, `interrupt(...)`, `observe(...)`, or another AgentScope hook. The chosen implementation should keep this behind the backend bridge.
