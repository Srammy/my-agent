# AgentScope Java 通用助手 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个 Docker-first 的 AgentScope Java 通用助手，支持登录、多会话、流式对话、MySQL skill 文件树、权限、记忆、自我进化和 Vue UI。

**Architecture:** 后端采用 Spring Boot 3 + WebFlux + Spring Security + MyBatis-Plus，通过 `ChatAgentGateway` 适配 AgentScope Java `HarnessAgent`。前端采用 Vue 3 + Vite + TypeScript + Element Plus，通过 `fetch` 读取 NDJSON 流。MySQL 存业务数据，Redis 用于 distributed 模式下的 AgentState/分布式状态，Docker Compose 提供一键启动。

**Tech Stack:** Java 21, Spring Boot 3, WebFlux, Spring Security JWT, MyBatis-Plus, MySQL 8.4, Redis 7, AgentScope Java `io.agentscope:agentscope-harness:2.0.0-RC4`, Vue 3, Vite, TypeScript, Pinia, Element Plus, Nginx, Docker Compose.

## Global Constraints

- 后端包名使用 `com.example.myagent`。
- 后端 Java 版本为 21。
- 前端使用 Vue 3 + Vite + TypeScript。
- 默认模型为 `dashscope:qwen-plus`。
- DashScope API key 从 `DASHSCOPE_API_KEY` 环境变量读取。
- OpenAI-compatible 模型通过 `agent.model.provider=openai-compatible`、`agent.model.base-url`、`agent.model.name`、`agent.model.api-key-env` 配置。
- MySQL 存用户、会话、skills、skill 文件、进化建议。
- Redis 用于 distributed 模式下的 AgentState/分布式状态。
- 前端不允许手动选择 `userId`，后端从 JWT 登录态解析。
- 高权限工具默认关闭。
- 自我进化不能自动打开高权限工具。
- 公共 skill、系统 prompt、代码 patch、高权限工具策略变更需要管理员批准。
- 流式聊天接口使用 `POST + NDJSON`。
- Docker Compose 必须能启动 MySQL、Redis、backend、frontend。

---

## File Structure

```text
backend/
  pom.xml
  Dockerfile
  .dockerignore
  src/main/java/com/example/myagent/
    MyAgentApplication.java
    config/
      AgentProperties.java
      SecurityConfig.java
      CorsConfig.java
      MyBatisPlusConfig.java
    auth/
      AuthController.java
      AuthService.java
      JwtService.java
      JwtAuthenticationFilter.java
      CurrentUser.java
      LoginRequest.java
      RegisterRequest.java
      AuthResponse.java
    user/
      UserEntity.java
      UserMapper.java
    session/
      ChatSessionEntity.java
      ChatSessionMapper.java
      SessionController.java
      SessionService.java
    chat/
      ChatAgentGateway.java
      AgentScopeChatAgentGateway.java
      StubChatAgentGateway.java
      ChatController.java
      ChatService.java
      ChatRequest.java
      StreamEventDto.java
      AgentEventMapper.java
    skill/
      SkillEntity.java
      SkillFileEntity.java
      UserSkillSettingEntity.java
      SkillMapper.java
      SkillFileMapper.java
      UserSkillSettingMapper.java
      SkillController.java
      SkillService.java
      SkillMaterializer.java
      SkillValidator.java
    memory/
      MemoryController.java
      MemoryService.java
    permission/
      PermissionController.java
      PermissionService.java
      PermissionModeDto.java
    evolution/
      EvolutionProposalEntity.java
      EvolutionProposalMapper.java
      EvolutionController.java
      EvolutionService.java
    tools/
      BasicTools.java
  src/main/resources/
    application.yml
    application-docker.yml
    db/migration/V1__init_schema.sql
  src/test/java/com/example/myagent/
    auth/AuthServiceTest.java
    session/SessionServiceTest.java
    skill/SkillValidatorTest.java
    skill/SkillMaterializerTest.java
    evolution/EvolutionServiceTest.java
    chat/ChatControllerTest.java

frontend/
  package.json
  vite.config.ts
  Dockerfile
  nginx.conf
  src/
    main.ts
    router.ts
    App.vue
    api/
      client.ts
      auth.ts
      chat.ts
      skills.ts
      memory.ts
      permissions.ts
      evolution.ts
    stores/
      auth.ts
      sessions.ts
      chat.ts
      skills.ts
      evolution.ts
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
      SkillFileTree.vue
      MemoryPanel.vue
      ModelInfoPanel.vue
      EvolutionPanel.vue

docker/
  mysql/init.sql

docker-compose.yml
.env.example
README.md
```

### Task 1: Repository And Docker Skeleton

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/Dockerfile`
- Create: `backend/.dockerignore`
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/Dockerfile`
- Create: `frontend/nginx.conf`
- Create: `frontend/.dockerignore`
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `docker/mysql/init.sql`

**Interfaces:**
- Produces: Docker service names `mysql`, `redis`, `backend`, `frontend`.
- Produces: backend port `8080`, frontend container port `80`, host frontend port `5173`.
- Produces: environment variables `MYSQL_ROOT_PASSWORD`, `DASHSCOPE_API_KEY`, `MYSQL_HOST`, `REDIS_HOST`.

- [ ] **Step 1: Create backend Maven skeleton**

Create `backend/pom.xml` with Spring Boot 3, Java 21, WebFlux, Security, MyBatis-Plus, MySQL driver, Redis, JWT, test dependencies, and AgentScope Harness dependency using Aliyun Maven repository.

Expected key content:

```xml
<properties>
  <java.version>21</java.version>
  <agentscope.version>2.0.0-RC4</agentscope.version>
</properties>
```

- [ ] **Step 2: Create Docker Compose**

Create `docker-compose.yml` with services `mysql`, `redis`, `backend`, and `frontend`. Use MySQL 8.4 and Redis 7. Backend must use `SPRING_PROFILES_ACTIVE=docker`. Frontend must publish `5173:80`.

- [ ] **Step 3: Create backend Dockerfile**

Use `maven:3.9-eclipse-temurin-21` for build and `eclipse-temurin:21-jre` for runtime. Expose `8080`.

- [ ] **Step 4: Create frontend Vite skeleton**

Create `frontend/package.json` with dependencies `@vitejs/plugin-vue`, `vite`, `typescript`, `vue`, `vue-router`, `pinia`, `element-plus`.

- [ ] **Step 5: Create frontend Dockerfile and nginx config**

Build with `node:22-alpine`; serve `dist` with `nginx:1.27-alpine`. `nginx.conf` must proxy `/api/` to `http://backend:8080/api/` and disable buffering for stream endpoints.

- [ ] **Step 6: Verify compose config parses**

Run: `docker compose config`

Expected: exits 0 and prints merged services.

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/Dockerfile backend/.dockerignore frontend/package.json frontend/vite.config.ts frontend/Dockerfile frontend/nginx.conf frontend/.dockerignore docker-compose.yml .env.example docker/mysql/init.sql
git commit -m "chore: add docker project skeleton"
```

### Task 2: Backend Configuration And Database Schema

**Files:**
- Create: `backend/src/main/java/com/example/myagent/MyAgentApplication.java`
- Create: `backend/src/main/java/com/example/myagent/config/AgentProperties.java`
- Create: `backend/src/main/java/com/example/myagent/config/MyBatisPlusConfig.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-docker.yml`
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`

**Interfaces:**
- Produces: `AgentProperties` with nested `Deployment`, `Model`, `StateStore`, `Skill`, `Permission`, `Tools`.
- Produces: database tables `users`, `chat_sessions`, `skills`, `skill_files`, `user_skill_settings`, `agent_evolution_proposals`.

- [ ] **Step 1: Write configuration binding test**

Create a Spring Boot test that loads `agent.model.provider=dashscope`, `agent.model.name=qwen-plus`, and asserts `AgentProperties.model().name()` equals `qwen-plus`.

- [ ] **Step 2: Implement `AgentProperties`**

Define immutable configuration records with `@ConfigurationProperties(prefix = "agent")` and defaults:

```java
provider = "dashscope";
name = "qwen-plus";
apiKeyEnv = "DASHSCOPE_API_KEY";
defaultMode = "DEFAULT";
```

- [ ] **Step 3: Add application YAML files**

`application.yml` uses localhost MySQL/Redis defaults. `application-docker.yml` reads `MYSQL_HOST`, `MYSQL_PASSWORD`, and `REDIS_HOST`.

- [ ] **Step 4: Add schema migration**

Create `V1__init_schema.sql` with all six tables from the approved spec. Include unique keys for username, session user index, skill owner/name, skill file path, and user skill settings.

- [ ] **Step 5: Run backend tests**

Run: `cd backend && mvn test`

Expected: tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent backend/src/main/resources
git commit -m "feat: add backend configuration and schema"
```

### Task 3: Authentication And Current User

**Files:**
- Create: `backend/src/main/java/com/example/myagent/auth/*`
- Create: `backend/src/main/java/com/example/myagent/user/UserEntity.java`
- Create: `backend/src/main/java/com/example/myagent/user/UserMapper.java`
- Create: `backend/src/main/java/com/example/myagent/config/SecurityConfig.java`
- Create: `backend/src/test/java/com/example/myagent/auth/AuthServiceTest.java`

**Interfaces:**
- Produces: `AuthService.register(RegisterRequest): AuthResponse`
- Produces: `AuthService.login(LoginRequest): AuthResponse`
- Produces: `JwtService.createToken(UserEntity): String`
- Produces: `JwtService.parseUserId(String): Long`
- Produces: `CurrentUser(Long id, String username, String role)`

- [ ] **Step 1: Write auth service tests**

Tests:
- registering a new user stores a BCrypt hash, not the raw password.
- duplicate username fails.
- login returns a JWT for valid credentials.
- invalid password fails.

- [ ] **Step 2: Implement user entity and mapper**

Map `users` table fields: `id`, `username`, `passwordHash`, `displayName`, `role`, `createdAt`, `updatedAt`.

- [ ] **Step 3: Implement JWT service**

Use HMAC secret from `security.jwt.secret`; default only for local dev. Token must include `sub=userId`, `username`, and `role`.

- [ ] **Step 4: Implement auth service and controller**

Endpoints:

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

- [ ] **Step 5: Implement Spring Security filter**

All `/api/**` endpoints require JWT except `/api/auth/register` and `/api/auth/login`.

- [ ] **Step 6: Run tests**

Run: `cd backend && mvn -Dtest=AuthServiceTest test`

Expected: all auth tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/auth backend/src/main/java/com/example/myagent/user backend/src/main/java/com/example/myagent/config/SecurityConfig.java backend/src/test/java/com/example/myagent/auth
git commit -m "feat: add jwt authentication"
```

### Task 4: Chat Session Metadata

**Files:**
- Create: `backend/src/main/java/com/example/myagent/session/*`
- Create: `backend/src/test/java/com/example/myagent/session/SessionServiceTest.java`

**Interfaces:**
- Produces: `SessionService.createSession(CurrentUser, String title): ChatSessionEntity`
- Produces: `SessionService.listSessions(CurrentUser): List<ChatSessionEntity>`
- Produces: `SessionService.requireOwnedSession(CurrentUser, String sessionId): ChatSessionEntity`
- Produces: `SessionService.deleteSession(CurrentUser, String sessionId): void`

- [ ] **Step 1: Write ownership tests**

Tests:
- user A sees only user A sessions.
- user A cannot require user B session.
- deleting a session filters by user id.

- [ ] **Step 2: Implement entity and mapper**

Map `chat_sessions` table with `id`, `userId`, `title`, `createdAt`, `updatedAt`.

- [ ] **Step 3: Implement service**

Generate session IDs with prefix `s_` plus UUID without dashes. Default title is first 30 characters of user message or `新会话`.

- [ ] **Step 4: Implement controller**

Endpoints:

```text
POST   /api/chat/sessions
GET    /api/chat/sessions
GET    /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
```

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn -Dtest=SessionServiceTest test`

Expected: all session tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent/session backend/src/test/java/com/example/myagent/session
git commit -m "feat: add user-scoped chat sessions"
```

### Task 5: Chat Gateway And NDJSON Stream

**Files:**
- Create: `backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`
- Create: `backend/src/main/java/com/example/myagent/chat/StubChatAgentGateway.java`
- Create: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Create: `backend/src/main/java/com/example/myagent/chat/ChatController.java`
- Create: `backend/src/main/java/com/example/myagent/chat/ChatRequest.java`
- Create: `backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`
- Create: `backend/src/test/java/com/example/myagent/chat/ChatControllerTest.java`

**Interfaces:**
- Produces: `ChatAgentGateway.stream(ChatAgentRequest): Flux<StreamEventDto>`
- Produces: `ChatAgentRequest(Long userId, String sessionId, String message)`
- Produces: `StreamEventDto(String type, Map<String, Object> payload)`
- Produces: `POST /api/chat/sessions/{sessionId}/stream` as `application/x-ndjson`.

- [ ] **Step 1: Write stream controller test**

Use `StubChatAgentGateway` to emit:

```json
{"type":"reply_start"}
{"type":"text_delta","delta":"你好"}
{"type":"done"}
```

Assert response content type is NDJSON-compatible and contains all three lines.

- [ ] **Step 2: Implement stream DTOs**

`StreamEventDto` has helper factories: `replyStart`, `textDelta`, `toolCall`, `toolResult`, `permissionRequired`, `evolutionProposal`, `done`, `error`.

- [ ] **Step 3: Implement `ChatService`**

Validate session ownership using `SessionService.requireOwnedSession`, then call `ChatAgentGateway.stream`.

- [ ] **Step 4: Implement controller**

Return `Flux<String>` where each event is serialized with Jackson and suffixed with `\n`.

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn -Dtest=ChatControllerTest test`

Expected: stream endpoint test passes.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent/chat backend/src/test/java/com/example/myagent/chat
git commit -m "feat: add ndjson chat stream"
```

### Task 6: AgentScope Gateway Adapter

**Files:**
- Create: `backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- Create: `backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java`
- Create: `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`

**Interfaces:**
- Consumes: `ChatAgentGateway.stream(ChatAgentRequest)`
- Produces: AgentScope-backed implementation selected outside test profile.
- Produces: `AgentEventMapper.map(Object agentEvent): StreamEventDto`

- [ ] **Step 1: Add adapter boundary**

Keep all direct AgentScope Java imports inside `AgentScopeChatAgentGateway`, `AgentEventMapper`, and `AgentScopeConfig`.

- [ ] **Step 2: Implement model factory inside config**

Read `AgentProperties.model`. For `dashscope`, construct model id `dashscope:qwen-plus`. For `openai-compatible`, require `baseUrl`, `name`, and API key env.

- [ ] **Step 3: Implement runtime context creation**

Create AgentScope `RuntimeContext` with:

```java
userId = request.userId().toString()
sessionId = request.sessionId()
```

- [ ] **Step 4: Map core events**

Map AgentScope events to stable frontend events:

```text
reply_start -> reply_start
text delta  -> text_delta
tool start  -> tool_call
tool result -> tool_result
confirm     -> permission_required
end         -> done
exception   -> error
```

- [ ] **Step 5: Add fallback behavior**

If AgentScope event class names differ, keep `StreamEventDto` stable and adjust only `AgentEventMapper`.

- [ ] **Step 6: Compile**

Run: `cd backend && mvn -DskipTests compile`

Expected: compile succeeds with actual AgentScope Java API.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java
git commit -m "feat: wire agentscope chat gateway"
```

### Task 7: MySQL Skill File Tree

**Files:**
- Create: `backend/src/main/java/com/example/myagent/skill/*`
- Create: `backend/src/test/java/com/example/myagent/skill/SkillValidatorTest.java`

**Interfaces:**
- Produces: `SkillService.listSystemSkills(CurrentUser)`
- Produces: `SkillService.listMySkills(CurrentUser)`
- Produces: `SkillService.createMySkill(CurrentUser, SkillCreateRequest)`
- Produces: `SkillService.upsertFile(CurrentUser, Long skillId, String path, String content)`
- Produces: `SkillValidator.validatePath(String path)`
- Produces: `SkillValidator.validateSkillMarkdown(String content)`

- [ ] **Step 1: Write path validation tests**

Cases:
- accepts `SKILL.md`
- accepts `references/checklist.md`
- accepts `scripts/analyze.java`
- rejects `../secret`
- rejects `/etc/passwd`
- rejects `C:\Users\a`
- rejects empty path

- [ ] **Step 2: Write SKILL.md validation tests**

Cases:
- accepts frontmatter with `name` and `description`
- rejects missing `name`
- rejects missing `description`

- [ ] **Step 3: Implement entities and mappers**

Map `skills`, `skill_files`, `user_skill_settings`.

- [ ] **Step 4: Implement validator**

Normalize path with `/`, reject traversal, absolute paths, and drive letters. Require first segment to be `SKILL.md`, `references`, `scripts`, or `assets`.

- [ ] **Step 5: Implement service and controller**

Endpoints:

```text
GET    /api/skills/system
GET    /api/skills/mine
POST   /api/skills/mine
PUT    /api/skills/mine/{skillId}
DELETE /api/skills/mine/{skillId}
GET    /api/skills/{skillId}/files
PUT    /api/skills/{skillId}/files/{path}
DELETE /api/skills/{skillId}/files/{path}
PUT    /api/skills/{skillId}/enabled
```

- [ ] **Step 6: Run tests**

Run: `cd backend && mvn -Dtest=SkillValidatorTest test`

Expected: validator tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/example/myagent/skill backend/src/test/java/com/example/myagent/skill
git commit -m "feat: add mysql skill file tree"
```

### Task 8: Skill Materializer

**Files:**
- Create: `backend/src/main/java/com/example/myagent/skill/SkillMaterializer.java`
- Create: `backend/src/test/java/com/example/myagent/skill/SkillMaterializerTest.java`

**Interfaces:**
- Consumes: `SkillService` enabled SYSTEM and USER skills.
- Produces: `SkillMaterializer.materializeForUser(Long userId): Path`
- Produces: local cache layout readable by AgentScope Harness.

- [ ] **Step 1: Write materializer test**

Given a system skill and user skill with same name, assert user skill wins. Assert files are written:

```text
<cache>/<userId>/<skillName>/SKILL.md
<cache>/<userId>/<skillName>/references/checklist.md
```

- [ ] **Step 2: Implement cache key**

Use `skillId + "-" + updatedAtEpochMillis` to decide whether a skill directory needs rewriting.

- [ ] **Step 3: Implement safe file write**

Resolve target path under cache root and assert normalized target starts with cache root before writing.

- [ ] **Step 4: Integrate with AgentScope config**

Before each chat request, materialize current user's enabled skills and pass the resulting skill root to AgentScope gateway.

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn -Dtest=SkillMaterializerTest test`

Expected: materializer tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent/skill/SkillMaterializer.java backend/src/test/java/com/example/myagent/skill/SkillMaterializerTest.java
git commit -m "feat: materialize skills for agentscope"
```

### Task 9: Permissions And Memory APIs

**Files:**
- Create: `backend/src/main/java/com/example/myagent/permission/*`
- Create: `backend/src/main/java/com/example/myagent/memory/*`

**Interfaces:**
- Produces: `PermissionService.getMode(CurrentUser, String sessionId): PermissionModeDto`
- Produces: `PermissionService.setMode(CurrentUser, String sessionId, PermissionModeDto): PermissionModeDto`
- Produces: `MemoryService.getSummary(CurrentUser): String`
- Produces: `MemoryService.listDaily(CurrentUser): List<String>`
- Produces: `MemoryService.getDaily(CurrentUser, LocalDate): String`

- [ ] **Step 1: Add permission mode enum DTO**

Allowed values: `DEFAULT`, `EXPLORE`, `ACCEPT_EDITS`, `DONT_ASK`, `BYPASS`.

- [ ] **Step 2: Implement permission endpoints**

Validate session ownership before reading or updating mode.

- [ ] **Step 3: Implement memory read endpoints**

Use shared storage abstraction. For first implementation, read through application-owned MySQL or AgentScope shared storage adapter; expose stable controller contract either way.

- [ ] **Step 4: Add tests for invalid mode and ownership**

Invalid mode returns `400`. Access to another user's session returns `404` or `403`.

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn test`

Expected: all current backend tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent/permission backend/src/main/java/com/example/myagent/memory backend/src/test/java/com/example/myagent
git commit -m "feat: add permission and memory APIs"
```

### Task 10: Evolution Proposals

**Files:**
- Create: `backend/src/main/java/com/example/myagent/evolution/*`
- Create: `backend/src/test/java/com/example/myagent/evolution/EvolutionServiceTest.java`

**Interfaces:**
- Produces: `EvolutionService.createProposal(CurrentUser, EvolutionCreateRequest)`
- Produces: `EvolutionService.approve(CurrentUser, Long id)`
- Produces: `EvolutionService.reject(CurrentUser, Long id)`
- Produces: `EvolutionService.apply(CurrentUser, Long id)`

- [ ] **Step 1: Write proposal state transition tests**

Allowed transitions:

```text
DRAFT -> APPROVED
DRAFT -> REJECTED
APPROVED -> APPLIED
```

Reject:

```text
REJECTED -> APPLIED
APPLIED -> REJECTED
```

- [ ] **Step 2: Implement entity and mapper**

Map `agent_evolution_proposals`.

- [ ] **Step 3: Implement service**

For `SKILL` proposals, `apply` creates or updates a USER skill only. For `MEMORY`, append to user memory store. For `TOOL_POLICY`, only apply low-risk suggestions. For `PROMPT` and `CODE_PATCH`, require ADMIN.

- [ ] **Step 4: Implement controller**

Endpoints:

```text
GET  /api/evolution/proposals
POST /api/evolution/proposals/{id}/approve
POST /api/evolution/proposals/{id}/reject
POST /api/evolution/proposals/{id}/apply
```

- [ ] **Step 5: Run tests**

Run: `cd backend && mvn -Dtest=EvolutionServiceTest test`

Expected: evolution tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/example/myagent/evolution backend/src/test/java/com/example/myagent/evolution
git commit -m "feat: add evolution proposal workflow"
```

### Task 11: Vue App Foundation

**Files:**
- Create: `frontend/src/main.ts`
- Create: `frontend/src/router.ts`
- Create: `frontend/src/App.vue`
- Create: `frontend/src/api/client.ts`
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/views/LoginView.vue`

**Interfaces:**
- Produces: auth store with `token`, `user`, `login`, `register`, `loadMe`, `logout`.
- Produces: route guard redirecting unauthenticated users to `/login`.

- [ ] **Step 1: Create Vue entry**

Mount app with Pinia, router, and Element Plus.

- [ ] **Step 2: Implement API client**

Wrap `fetch`, attach JWT, parse JSON errors, and expose `apiGet`, `apiPost`, `apiPut`, `apiDelete`.

- [ ] **Step 3: Implement auth store**

Persist token to `localStorage`. `loadMe` calls `/api/auth/me`.

- [ ] **Step 4: Implement login/register view**

Use Element Plus form. Login success routes to `/chat`.

- [ ] **Step 5: Run frontend typecheck/build**

Run: `cd frontend && npm install && npm run build`

Expected: build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src frontend/package.json frontend/vite.config.ts
git commit -m "feat: add vue auth foundation"
```

### Task 12: Vue Chat Workspace And Stream Parser

**Files:**
- Create: `frontend/src/api/chat.ts`
- Create: `frontend/src/stores/sessions.ts`
- Create: `frontend/src/stores/chat.ts`
- Create: `frontend/src/views/ChatView.vue`
- Create: `frontend/src/components/SessionSidebar.vue`
- Create: `frontend/src/components/ChatTranscript.vue`
- Create: `frontend/src/components/Composer.vue`
- Create: `frontend/src/components/ToolEventCard.vue`

**Interfaces:**
- Produces: `streamChat(sessionId: string, message: string, onEvent: (event) => void): Promise<void>`
- Produces: chat store message model with events `text_delta`, `tool_call`, `tool_result`, `permission_required`, `evolution_proposal`, `done`, `error`.

- [ ] **Step 1: Implement session API and store**

Load, create, delete sessions using `/api/chat/sessions`.

- [ ] **Step 2: Implement NDJSON parser**

Buffer chunks, split on `\n`, parse full lines, keep partial line for next chunk.

- [ ] **Step 3: Implement chat store**

Append user message, create assistant draft, append deltas, attach tool cards, show errors.

- [ ] **Step 4: Implement chat workspace components**

Left sidebar for sessions, center transcript, bottom composer.

- [ ] **Step 5: Run frontend build**

Run: `cd frontend && npm run build`

Expected: build succeeds.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/chat.ts frontend/src/stores/sessions.ts frontend/src/stores/chat.ts frontend/src/views/ChatView.vue frontend/src/components
git commit -m "feat: add streaming chat workspace"
```

### Task 13: Vue Skill, Permission, Memory, Evolution Panels

**Files:**
- Create: `frontend/src/api/skills.ts`
- Create: `frontend/src/api/memory.ts`
- Create: `frontend/src/api/permissions.ts`
- Create: `frontend/src/api/evolution.ts`
- Create: `frontend/src/stores/skills.ts`
- Create: `frontend/src/stores/evolution.ts`
- Create: `frontend/src/components/PermissionPanel.vue`
- Create: `frontend/src/components/SkillPanel.vue`
- Create: `frontend/src/components/SkillFileTree.vue`
- Create: `frontend/src/components/MemoryPanel.vue`
- Create: `frontend/src/components/ModelInfoPanel.vue`
- Create: `frontend/src/components/EvolutionPanel.vue`

**Interfaces:**
- Consumes: backend skill, memory, permission, evolution endpoints.
- Produces: right-side panels in `ChatView`.

- [ ] **Step 1: Implement API modules**

Create typed functions for all endpoints listed in spec.

- [ ] **Step 2: Implement Skill panel**

Tabs: `公共 Skill`, `我的 Skill`. Public skills read-only except enable toggle. My skills support create/edit/delete/file tree.

- [ ] **Step 3: Implement Permission panel**

Select mode from exact values: `DEFAULT`, `EXPLORE`, `ACCEPT_EDITS`, `DONT_ASK`, `BYPASS`.

- [ ] **Step 4: Implement Memory panel**

Read-only summary and daily list.

- [ ] **Step 5: Implement Evolution panel**

List proposals, approve, reject, apply. Show status chips.

- [ ] **Step 6: Run frontend build**

Run: `cd frontend && npm run build`

Expected: build succeeds.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api frontend/src/stores frontend/src/components frontend/src/views/ChatView.vue
git commit -m "feat: add assistant control panels"
```

### Task 14: End-To-End Docker Verification And README

**Files:**
- Create: `README.md`
- Modify: `.env.example`
- Modify: `docker-compose.yml`
- Modify: `docker/mysql/init.sql`

**Interfaces:**
- Produces: documented startup path `cp .env.example .env && docker compose up -d`.
- Produces: documented local dev startup path for backend and frontend.

- [ ] **Step 1: Add README**

README sections:
- prerequisites
- environment variables
- Docker startup
- local backend startup
- local frontend startup
- MySQL/Redis notes
- model provider switching
- safety notes for high-permission tools

- [ ] **Step 2: Verify Docker Compose**

Run: `docker compose up -d --build`

Expected: MySQL, Redis, backend, frontend containers start.

- [ ] **Step 3: Verify health manually**

Open `http://localhost:5173`, register, login, create a session, send a message. If API key is missing, UI must show a clear model configuration error.

- [ ] **Step 4: Run full backend tests**

Run: `cd backend && mvn test`

Expected: all backend tests pass.

- [ ] **Step 5: Run frontend build**

Run: `cd frontend && npm run build`

Expected: build succeeds.

- [ ] **Step 6: Commit**

```bash
git add README.md .env.example docker-compose.yml docker/mysql/init.sql backend frontend
git commit -m "docs: add startup guide and verify docker deployment"
```

## Self-Review

Spec coverage:

- Docker-first startup is covered by Tasks 1 and 14.
- Login and user isolation are covered by Tasks 3 and 4.
- Streaming chat is covered by Tasks 5 and 6.
- AgentScope Java integration is covered by Task 6.
- MySQL skill file tree and materialization are covered by Tasks 7 and 8.
- Permissions and memory are covered by Task 9.
- Self-evolution proposals are covered by Task 10.
- Vue auth/chat/control panels are covered by Tasks 11, 12, and 13.
- Tests and README are covered across each task and Task 14.

Placeholder scan:

- No unfinished markers or intentionally vague implementation instructions remain.
- AgentScope SDK class-name uncertainty is isolated behind `ChatAgentGateway` and `AgentEventMapper`, with compile verification in Task 6.

Type consistency:

- `CurrentUser`, `ChatAgentGateway`, `StreamEventDto`, `SkillMaterializer`, and `EvolutionService` names are consistent across producer and consumer tasks.
