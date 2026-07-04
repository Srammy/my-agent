# AgentScope Java Assistant Design

Date: 2026-07-04

## Purpose

Build a Java-based general assistant with AgentScope Java official SDK. The assistant must support streaming chat, login-based user isolation, isolated sessions, tool calling, user-configurable skills, permission control, long-term memory, and a browser UI implemented with Vue.

The default model provider is DashScope Qwen, using `dashscope:qwen-plus`. The system must also support switching to an OpenAI-compatible model provider through configuration.

## Official Sources

The design is based on the AgentScope Java documentation and Maven metadata:

- AgentScope Java quickstart: https://java.agentscope.io/v2/en/docs/quickstart.html
- Agent building block: https://java.agentscope.io/v2/en/docs/building-blocks/agent.html
- Message and event streaming: https://java.agentscope.io/v2/en/docs/building-blocks/message-and-event.html
- Tool system: https://java.agentscope.io/v2/en/docs/building-blocks/tool.html
- Permission system: https://java.agentscope.io/v2/en/docs/building-blocks/permission-system.html
- Context and AgentState: https://java.agentscope.io/v2/en/docs/building-blocks/context.html
- Harness workspace: https://java.agentscope.io/v2/en/docs/harness/workspace.html
- Harness memory: https://java.agentscope.io/v2/en/docs/harness/memory.html
- Harness skill: https://java.agentscope.io/v2/en/docs/harness/skill.html
- Maven artifact metadata: https://maven.aliyun.com/repository/public/io/agentscope/agentscope-harness/maven-metadata.xml

## Scope

In scope:

- Spring Boot backend.
- Vue 3 frontend.
- MySQL-backed user accounts and chat session metadata.
- JWT authentication.
- AgentScope Java `HarnessAgent`.
- Streaming chat response.
- Per-user and per-session isolation.
- Default DashScope Qwen model configuration.
- OpenAI-compatible model configuration.
- Basic tools and optional higher-permission tool groups.
- User skill management.
- Permission mode management.
- Read-only long-term memory view.
- Focused tests and developer README.

Out of scope for the first implementation:

- Full production auth features such as OAuth, MFA, refresh-token rotation, or admin console.
- Multi-node deployment.
- Redis/MySQL AgentState store unless AgentScope Java provides a directly usable implementation that is straightforward to wire.
- Editing long-term memory in the UI.
- Full MCP marketplace UI. The backend will reserve a configuration extension point.

## Architecture

The system uses a split frontend/backend architecture.

Backend:

- Java 21.
- Spring Boot 3.
- Spring WebFlux for streaming responses.
- Spring Security with JWT.
- MySQL for users and chat session metadata.
- MyBatis-Plus for persistence.
- AgentScope Java `io.agentscope:agentscope-harness:2.0.0-RC4`.
- Maven.

Frontend:

- Vue 3.
- Vite.
- TypeScript.
- Pinia.
- Vue Router.
- Element Plus.
- `fetch` plus `ReadableStream` for streaming chat.

The backend owns authentication, model configuration, AgentScope runtime construction, session ownership checks, skill file access, permission updates, and memory reads. The frontend is a browser chat workspace that never manually selects `userId`; it receives the current user from the login session.

## Backend Project Structure

```text
backend/
  pom.xml
  src/main/java/com/example/myagent/
    MyAgentApplication.java
    config/
      AgentProperties.java
      SecurityConfig.java
      CorsConfig.java
      AgentScopeConfig.java
    auth/
      AuthController.java
      AuthService.java
      JwtService.java
      CurrentUser.java
      LoginRequest.java
      RegisterRequest.java
      AuthResponse.java
    user/
      UserEntity.java
      UserMapper.java
      UserService.java
    session/
      ChatSessionEntity.java
      ChatSessionMapper.java
      SessionController.java
      SessionService.java
    chat/
      ChatController.java
      ChatService.java
      ChatRequest.java
      StreamEventDto.java
      AgentEventMapper.java
    model/
      ModelFactory.java
      ModelProviderType.java
    skill/
      SkillController.java
      SkillService.java
      SkillDto.java
      SkillValidator.java
    memory/
      MemoryController.java
      MemoryService.java
    permission/
      PermissionController.java
      PermissionService.java
      PermissionModeDto.java
    tools/
      BasicTools.java
      TimeTool.java
      CalculatorTool.java
      SessionSummaryTool.java
```

## Maven Configuration

The backend depends on AgentScope Java Harness:

```xml
<properties>
  <java.version>21</java.version>
  <agentscope.version>2.0.0-RC4</agentscope.version>
</properties>

<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-harness</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

The artifact is available from the Aliyun Maven public repository, so the backend `pom.xml` includes:

```xml
<repository>
  <id>aliyun-public</id>
  <url>https://maven.aliyun.com/repository/public</url>
</repository>
```

## Configuration

Default configuration:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/myagent?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password

agent:
  workspace:
    path: ./.agentscope/workspace
  model:
    provider: dashscope
    name: qwen-plus
    api-key-env: DASHSCOPE_API_KEY
  permission:
    default-mode: DEFAULT
  compaction:
    trigger-messages: 30
    keep-messages: 10
  tools:
    file-tools-enabled: false
    shell-enabled: false
    http-fetch-enabled: false
    mcp-enabled: false
```

OpenAI-compatible configuration:

```yaml
agent:
  model:
    provider: openai-compatible
    name: deepseek-chat
    base-url: https://api.deepseek.com
    api-key-env: OPENAI_API_KEY
```

API keys are read only from environment variables. They are not stored in MySQL and are not entered in the frontend.

## Authentication And User Isolation

The application includes a login system backed by MySQL.

Endpoints:

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

The frontend stores the JWT in `localStorage` and sends it as:

```text
Authorization: Bearer <token>
```

Spring Security validates the token and exposes the current user to controllers. The UI shows the current account but does not allow manual user switching.

MySQL schema:

```sql
create table users (
  id bigint primary key auto_increment,
  username varchar(64) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(64),
  created_at datetime not null,
  updated_at datetime not null
);

create table chat_sessions (
  id varchar(64) primary key,
  user_id bigint not null,
  title varchar(120) not null,
  created_at datetime not null,
  updated_at datetime not null,
  index idx_chat_sessions_user_id (user_id)
);
```

All session reads and writes include `user_id`. A logged-in user cannot see or operate on another user's sessions.

## AgentScope Runtime

The backend creates a singleton `HarnessAgent` at startup. The model is constructed from `agent.model`. The agent receives basic tools, skill support, memory support, workspace configuration, compaction configuration, and default permission mode.

Each chat turn constructs a runtime context from the authenticated user and selected session:

```java
RuntimeContext runtimeContext = RuntimeContext.builder()
    .userId(currentUser.id().toString())
    .sessionId(sessionId)
    .build();
```

The assistant streams events with AgentScope Java:

```java
Flux<AgentEvent> events = harnessAgent.streamEvents(userMessage, runtimeContext);
```

AgentScope uses `(userId, sessionId)` for isolated `AgentState`, context, permissions, memory, and execution ordering. The application relies on AgentScope's same-session serialization while allowing different sessions to run independently.

## Chat API

Endpoints:

```text
POST   /api/chat/sessions
GET    /api/chat/sessions
GET    /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
POST   /api/chat/sessions/{sessionId}/stream
```

`POST /stream` accepts:

```json
{
  "message": "Hello"
}
```

The response uses newline-delimited JSON because it works naturally with `POST`, JSON request bodies, and authorization headers:

```json
{"type":"reply_start","replyId":"..."}
{"type":"text_delta","delta":"Hello"}
{"type":"tool_call","name":"calculator","input":"..."}
{"type":"tool_result","name":"calculator","output":"..."}
{"type":"permission_required","toolCallId":"...","message":"..."}
{"type":"done"}
```

If the model call or agent execution fails, the stream emits:

```json
{"type":"error","message":"..."}
```

and then closes.

## Frontend Streaming

The frontend uses `fetch` and `ReadableStream`:

```ts
const response = await fetch(`/api/chat/sessions/${sessionId}/stream`, {
  method: "POST",
  headers: {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  },
  body: JSON.stringify({ message })
});
```

The stream parser buffers partial chunks, splits on newline, parses each JSON object, and updates the chat transcript incrementally.

## Tool System

Default enabled tools:

- Current time.
- Calculator.
- Session summary/title helper.
- A small application-owned todo/planning tool with create, list, and update operations.
- Skill loading.
- Memory read and append operations exposed through the application's memory service and wired into the agent toolkit.

Configurable higher-permission tools:

- File read.
- File search.
- File write/edit.
- Shell or bash execution.
- HTTP fetch.
- MCP tools.

High-permission tools are disabled by default. Enabling them requires backend configuration and still respects AgentScope permission mode and rules.

## Permission Control

Default permission mode is `DEFAULT`.

Endpoints:

```text
GET  /api/permissions/sessions/{sessionId}
PUT  /api/permissions/sessions/{sessionId}
POST /api/permissions/sessions/{sessionId}/confirm
```

Supported modes:

- `DEFAULT`: safe default; unmatched sensitive actions require confirmation.
- `EXPLORE`: read-only exploration.
- `ACCEPT_EDITS`: allows edits in the configured workspace.
- `DONT_ASK`: converts confirmation prompts to denial.
- `BYPASS`: trusted sandbox mode only.

Permission mode is part of the session's AgentState and is isolated by authenticated user and session.

If a tool call requires confirmation, the backend maps AgentScope confirmation events to a frontend `permission_required` event. The UI renders an approval card. The backend keeps the frontend event contract stable while adapting to the exact AgentScope Java confirmation class names during implementation. High-permission tools remain disabled by default, so an incomplete confirmation adapter cannot accidentally allow unsafe operations.

## Skill Management

Skills are stored per user:

```text
.agentscope/workspace/users/{userId}/skills/{skillName}/SKILL.md
```

Endpoints:

```text
GET    /api/skills
POST   /api/skills
GET    /api/skills/{name}
PUT    /api/skills/{name}
DELETE /api/skills/{name}
```

The backend validates that `SKILL.md` contains frontmatter with at least:

```yaml
---
name: skill-name
description: what this skill does
---
```

The agent loads user skills through AgentScope Harness skill support. User A cannot read or edit User B's skill files.

## Memory

Memory is stored per user under the AgentScope workspace:

```text
.agentscope/workspace/users/{userId}/MEMORY.md
.agentscope/workspace/users/{userId}/memory/YYYY-MM-DD.md
```

The UI includes a read-only memory panel. It shows the consolidated long-term memory and the daily memory files. The first implementation does not allow editing memory from the UI because incorrect manual edits can distort future assistant behavior.

Endpoints:

```text
GET /api/memory
GET /api/memory/daily
GET /api/memory/daily/{date}
```

## Vue Frontend

Project structure:

```text
frontend/
  package.json
  vite.config.ts
  src/
    main.ts
    router.ts
    App.vue
    api/
      auth.ts
      chat.ts
      skills.ts
      memory.ts
      permissions.ts
    stores/
      auth.ts
      sessions.ts
      chat.ts
    views/
      LoginView.vue
      ChatView.vue
    components/
      SessionSidebar.vue
      ChatTranscript.vue
      Composer.vue
      ToolEventCard.vue
      PermissionPanel.vue
      SkillPanel.vue
      MemoryPanel.vue
      ModelInfoPanel.vue
```

Views:

- Login/register page.
- Main chat workspace.

Main chat workspace:

- Left sidebar: chat sessions, create session, delete session.
- Center: transcript, streaming assistant output, tool event cards, errors.
- Bottom composer: input, send, stop.
- Right side: model info, permission mode, skill management, read-only memory view.

The UI uses Element Plus components with a dense work-focused layout. It is not a landing page.

## Error Handling

Backend behavior:

- Missing or invalid JWT returns `401`.
- Access to another user's session returns `404` or `403`.
- Missing model API key returns a clear model configuration error.
- Model stream failures emit an error stream event.
- Skill validation failures return `400`.
- MySQL startup failure stops the app with Spring's normal datasource error.
- Missing user workspace files are created lazily.

Frontend behavior:

- Auth errors route to login.
- Stream errors appear in the transcript.
- Permission prompts appear as approval cards.
- Skill validation errors appear next to the editor.

## Testing

Backend tests:

- Auth registration and login.
- JWT validation.
- Session ownership filtering.
- Configuration parsing for DashScope and OpenAI-compatible providers.
- RuntimeContext construction uses authenticated user id and session id.
- Skill frontmatter validation.
- Chat stream endpoint smoke test with a mocked `ChatAgentGateway` adapter.

Frontend tests or smoke checks:

- Login and token storage.
- Session list rendering.
- NDJSON stream parser.
- Transcript update from text delta events.
- Tool event rendering.
- Permission mode selection.
- Skill editor validation.

Manual verification:

- Start MySQL.
- Start backend.
- Start Vue dev server.
- Register/login.
- Create a session.
- Send a message and observe streaming output.
- Check session isolation with a second account.
- Create and edit a skill.
- View memory panel.
- Switch permission mode.

## Development Commands

Backend:

```bash
cd backend
mvn spring-boot:run
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

URLs:

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
```

## Implementation Order

1. Scaffold backend Maven project.
2. Add Spring Boot, MySQL, MyBatis-Plus, Spring Security, JWT, and AgentScope Harness dependencies.
3. Implement database schema and auth.
4. Implement session metadata APIs.
5. Wire AgentScope Java model and HarnessAgent.
6. Implement streaming chat endpoint and event mapping.
7. Scaffold Vue 3 frontend.
8. Implement auth UI and route guards.
9. Implement chat workspace and NDJSON stream parser.
10. Implement skill management.
11. Implement read-only memory view.
12. Implement permission mode UI and backend endpoint.
13. Add focused tests.
14. Add README with setup instructions.

## Acceptance Criteria

- A user can register and log in.
- A logged-in user sees only their sessions.
- A logged-in user can create a session and chat with the assistant.
- Assistant responses stream into the browser UI.
- The default model is DashScope `qwen-plus`.
- OpenAI-compatible model settings can be configured.
- Basic tool events render in the UI.
- Skill files can be created, viewed, edited, and deleted per user.
- Long-term memory can be viewed per user.
- Permission mode can be viewed and changed per session.
- Missing API keys and unauthorized access produce clear errors.
