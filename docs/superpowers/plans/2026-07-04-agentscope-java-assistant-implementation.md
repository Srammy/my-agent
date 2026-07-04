# AgentScope Java 通用助手实现计划

> **给 agentic workers 的要求：** 实施本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`。所有步骤使用 checkbox（`- [ ]`）跟踪执行状态。

**目标：** 构建一个 Docker-first 的 AgentScope Java 通用助手，支持登录、多会话、流式对话、MySQL skill 文件树、权限、记忆、自我进化和 Vue UI。

**架构：** 后端使用 Spring Boot 3 + WebFlux + Spring Security + MyBatis-Plus，通过 `ChatAgentGateway` 隔离 AgentScope Java `HarnessAgent` 的具体 API。前端使用 Vue 3 + Vite + TypeScript + Element Plus，通过 `fetch` 读取 NDJSON 流。MySQL 存业务数据，Redis 用于 distributed 模式下的 AgentState/分布式状态，Docker Compose 提供一键启动。

**技术栈：** Java 21、Spring Boot 3、WebFlux、Spring Security JWT、MyBatis-Plus、MySQL 8.4、Redis 7、AgentScope Java `io.agentscope:agentscope-harness:2.0.0-RC4`、Vue 3、Vite、TypeScript、Pinia、Element Plus、Nginx、Docker Compose。

## 全局约束

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

## 计划中的文件结构

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
      ChatAgentRequest.java
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

## 任务 1：创建仓库骨架和 Docker 启动骨架

**文件：**
- 新建：`backend/pom.xml`
- 新建：`backend/Dockerfile`
- 新建：`backend/.dockerignore`
- 新建：`frontend/package.json`
- 新建：`frontend/vite.config.ts`
- 新建：`frontend/Dockerfile`
- 新建：`frontend/nginx.conf`
- 新建：`frontend/.dockerignore`
- 新建：`docker-compose.yml`
- 新建：`.env.example`
- 新建：`docker/mysql/init.sql`

**接口与产物：**
- 产出 Docker 服务名：`mysql`、`redis`、`backend`、`frontend`。
- 产出端口：后端 `8080`，前端容器 `80`，宿主机前端 `5173`。
- 产出环境变量：`MYSQL_ROOT_PASSWORD`、`DASHSCOPE_API_KEY`、`MYSQL_HOST`、`REDIS_HOST`。

- [ ] **步骤 1：创建后端 Maven 骨架**

创建 `backend/pom.xml`，包含 Spring Boot 3、Java 21、WebFlux、Security、MyBatis-Plus、MySQL driver、Redis、JWT、测试依赖和 AgentScope Harness 依赖。必须配置阿里云 Maven 仓库。

关键配置：

```xml
<properties>
  <java.version>21</java.version>
  <agentscope.version>2.0.0-RC4</agentscope.version>
</properties>
```

- [ ] **步骤 2：创建 Docker Compose**

创建 `docker-compose.yml`，包含 `mysql`、`redis`、`backend`、`frontend`。MySQL 使用 `mysql:8.4`，Redis 使用 `redis:7`，后端设置 `SPRING_PROFILES_ACTIVE=docker`，前端暴露 `5173:80`。

- [ ] **步骤 3：创建后端 Dockerfile**

使用 `maven:3.9-eclipse-temurin-21` 构建，使用 `eclipse-temurin:21-jre` 运行，暴露 `8080`。

- [ ] **步骤 4：创建前端 Vite 骨架**

创建 `frontend/package.json`，依赖包含 `@vitejs/plugin-vue`、`vite`、`typescript`、`vue`、`vue-router`、`pinia`、`element-plus`。

- [ ] **步骤 5：创建前端 Dockerfile 和 Nginx 配置**

使用 `node:22-alpine` 构建前端，使用 `nginx:1.27-alpine` 托管 `dist`。`nginx.conf` 必须把 `/api/` 反代到 `http://backend:8080/api/`，并对流式接口关闭缓冲。

- [ ] **步骤 6：验证 Docker Compose 配置**

运行：

```bash
docker compose config
```

预期：命令退出码为 0，并输出合并后的 services 配置。

- [ ] **步骤 7：提交**

```bash
git add backend/pom.xml backend/Dockerfile backend/.dockerignore frontend/package.json frontend/vite.config.ts frontend/Dockerfile frontend/nginx.conf frontend/.dockerignore docker-compose.yml .env.example docker/mysql/init.sql
git commit -m "chore: 添加 Docker 项目骨架"
```

## 任务 2：后端配置与数据库 schema

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/MyAgentApplication.java`
- 新建：`backend/src/main/java/com/example/myagent/config/AgentProperties.java`
- 新建：`backend/src/main/java/com/example/myagent/config/MyBatisPlusConfig.java`
- 新建：`backend/src/main/resources/application.yml`
- 新建：`backend/src/main/resources/application-docker.yml`
- 新建：`backend/src/main/resources/db/migration/V1__init_schema.sql`

**接口与产物：**
- 产出 `AgentProperties`，包含 `Deployment`、`Model`、`StateStore`、`Skill`、`Permission`、`Tools`。
- 产出数据库表：`users`、`chat_sessions`、`skills`、`skill_files`、`user_skill_settings`、`agent_evolution_proposals`。

- [ ] **步骤 1：编写配置绑定测试**

创建 Spring Boot 测试，加载 `agent.model.provider=dashscope` 和 `agent.model.name=qwen-plus`，断言 `AgentProperties.model().name()` 等于 `qwen-plus`。

- [ ] **步骤 2：实现 `AgentProperties`**

使用 `@ConfigurationProperties(prefix = "agent")` 定义配置记录类型。默认值：

```text
provider = dashscope
name = qwen-plus
apiKeyEnv = DASHSCOPE_API_KEY
defaultMode = DEFAULT
```

- [ ] **步骤 3：添加应用配置文件**

`application.yml` 使用本机 MySQL/Redis 默认值。`application-docker.yml` 读取 `MYSQL_HOST`、`MYSQL_PASSWORD`、`REDIS_HOST`。

- [ ] **步骤 4：添加数据库 schema**

创建 `V1__init_schema.sql`，包含已批准 spec 中的六张表。必须包含 username 唯一键、会话 user 索引、skill owner/name 唯一键、skill file path 唯一键、user skill setting 唯一键。

- [ ] **步骤 5：运行后端测试**

运行：

```bash
cd backend
mvn test
```

预期：测试通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent backend/src/main/resources
git commit -m "feat: 添加后端配置和数据库 schema"
```

## 任务 3：登录认证与当前用户解析

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/auth/*`
- 新建：`backend/src/main/java/com/example/myagent/user/UserEntity.java`
- 新建：`backend/src/main/java/com/example/myagent/user/UserMapper.java`
- 新建：`backend/src/main/java/com/example/myagent/config/SecurityConfig.java`
- 新建：`backend/src/test/java/com/example/myagent/auth/AuthServiceTest.java`

**接口与产物：**
- 产出：`AuthService.register(RegisterRequest): AuthResponse`
- 产出：`AuthService.login(LoginRequest): AuthResponse`
- 产出：`JwtService.createToken(UserEntity): String`
- 产出：`JwtService.parseUserId(String): Long`
- 产出：`CurrentUser(Long id, String username, String role)`

- [ ] **步骤 1：编写认证服务测试**

测试内容：

- 注册新用户后存储 BCrypt hash，不存明文密码。
- 重复 username 注册失败。
- 正确账号密码登录后返回 JWT。
- 错误密码登录失败。

- [ ] **步骤 2：实现用户实体和 mapper**

映射 `users` 表字段：`id`、`username`、`passwordHash`、`displayName`、`role`、`createdAt`、`updatedAt`。

- [ ] **步骤 3：实现 JWT 服务**

使用 `security.jwt.secret` 中的 HMAC secret。token 包含 `sub=userId`、`username`、`role`。

- [ ] **步骤 4：实现认证服务和 controller**

接口：

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

- [ ] **步骤 5：实现 Spring Security filter**

除 `/api/auth/register` 和 `/api/auth/login` 外，所有 `/api/**` 都需要 JWT。

- [ ] **步骤 6：运行测试**

运行：

```bash
cd backend
mvn -Dtest=AuthServiceTest test
```

预期：认证测试全部通过。

- [ ] **步骤 7：提交**

```bash
git add backend/src/main/java/com/example/myagent/auth backend/src/main/java/com/example/myagent/user backend/src/main/java/com/example/myagent/config/SecurityConfig.java backend/src/test/java/com/example/myagent/auth
git commit -m "feat: 添加 JWT 登录认证"
```

## 任务 4：会话元数据与用户隔离

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/session/*`
- 新建：`backend/src/test/java/com/example/myagent/session/SessionServiceTest.java`

**接口与产物：**
- 产出：`SessionService.createSession(CurrentUser, String title): ChatSessionEntity`
- 产出：`SessionService.listSessions(CurrentUser): List<ChatSessionEntity>`
- 产出：`SessionService.requireOwnedSession(CurrentUser, String sessionId): ChatSessionEntity`
- 产出：`SessionService.deleteSession(CurrentUser, String sessionId): void`

- [ ] **步骤 1：编写归属测试**

测试内容：

- 用户 A 只能看到用户 A 的会话。
- 用户 A 不能读取用户 B 的会话。
- 删除会话时必须按 user id 过滤。

- [ ] **步骤 2：实现 entity 和 mapper**

映射 `chat_sessions` 表字段：`id`、`userId`、`title`、`createdAt`、`updatedAt`。

- [ ] **步骤 3：实现 service**

会话 ID 使用 `s_` + 去掉短横线的 UUID。默认标题为用户消息前 30 个字符，或 `新会话`。

- [ ] **步骤 4：实现 controller**

接口：

```text
POST   /api/chat/sessions
GET    /api/chat/sessions
GET    /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
```

- [ ] **步骤 5：运行测试**

运行：

```bash
cd backend
mvn -Dtest=SessionServiceTest test
```

预期：会话测试全部通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent/session backend/src/test/java/com/example/myagent/session
git commit -m "feat: 添加用户隔离会话"
```

## 任务 5：聊天网关与 NDJSON 流

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/StubChatAgentGateway.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/ChatService.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/ChatController.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/ChatRequest.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/ChatAgentRequest.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/StreamEventDto.java`
- 新建：`backend/src/test/java/com/example/myagent/chat/ChatControllerTest.java`

**接口与产物：**
- 产出：`ChatAgentGateway.stream(ChatAgentRequest): Flux<StreamEventDto>`
- 产出：`ChatAgentRequest(Long userId, String sessionId, String message)`
- 产出：`StreamEventDto(String type, Map<String, Object> payload)`
- 产出：`POST /api/chat/sessions/{sessionId}/stream`，响应类型为 `application/x-ndjson`。

- [ ] **步骤 1：编写流式 controller 测试**

使用 `StubChatAgentGateway` 输出三行事件：

```json
{"type":"reply_start"}
{"type":"text_delta","delta":"你好"}
{"type":"done"}
```

断言响应包含三行 NDJSON。

- [ ] **步骤 2：实现流式 DTO**

`StreamEventDto` 提供静态工厂方法：`replyStart`、`textDelta`、`toolCall`、`toolResult`、`permissionRequired`、`evolutionProposal`、`done`、`error`。

- [ ] **步骤 3：实现 `ChatService`**

先调用 `SessionService.requireOwnedSession` 校验会话归属，再调用 `ChatAgentGateway.stream`。

- [ ] **步骤 4：实现 controller**

返回 `Flux<String>`，每个事件用 Jackson 序列化并追加 `\n`。

- [ ] **步骤 5：运行测试**

运行：

```bash
cd backend
mvn -Dtest=ChatControllerTest test
```

预期：流式接口测试通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent/chat backend/src/test/java/com/example/myagent/chat
git commit -m "feat: 添加 NDJSON 聊天流"
```

## 任务 6：接入 AgentScope Java Gateway

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java`
- 新建：`backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java`
- 新建：`backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java`
- 修改：`backend/src/main/java/com/example/myagent/chat/ChatAgentGateway.java`

**接口与产物：**
- 消费：`ChatAgentGateway.stream(ChatAgentRequest)`
- 产出：非测试 profile 下使用 AgentScope 的实现。
- 产出：`AgentEventMapper.map(Object agentEvent): StreamEventDto`

- [ ] **步骤 1：添加适配层边界**

所有 AgentScope Java 直接 import 只允许出现在 `AgentScopeChatAgentGateway`、`AgentEventMapper`、`AgentScopeConfig` 中。

- [ ] **步骤 2：在 config 中实现模型工厂**

读取 `AgentProperties.model`。`dashscope` 使用模型 id `dashscope:qwen-plus`。`openai-compatible` 要求配置 `baseUrl`、`name` 和 API key env。

- [ ] **步骤 3：实现 RuntimeContext 创建**

用请求创建 AgentScope `RuntimeContext`：

```text
userId = request.userId().toString()
sessionId = request.sessionId()
```

- [ ] **步骤 4：映射核心事件**

映射规则：

```text
reply_start -> reply_start
text delta  -> text_delta
tool start  -> tool_call
tool result -> tool_result
confirm     -> permission_required
end         -> done
exception   -> error
```

- [ ] **步骤 5：保留稳定前端协议**

如果 AgentScope 实际事件类名与预期不同，只修改 `AgentEventMapper`，不修改前端事件协议。

- [ ] **步骤 6：编译**

运行：

```bash
cd backend
mvn -DskipTests compile
```

预期：使用真实 AgentScope Java API 编译通过。

- [ ] **步骤 7：提交**

```bash
git add backend/src/main/java/com/example/myagent/chat/AgentScopeChatAgentGateway.java backend/src/main/java/com/example/myagent/chat/AgentEventMapper.java backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java
git commit -m "feat: 接入 AgentScope 聊天网关"
```

## 任务 7：MySQL Skill 文件树

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/skill/*`
- 新建：`backend/src/test/java/com/example/myagent/skill/SkillValidatorTest.java`

**接口与产物：**
- 产出：`SkillService.listSystemSkills(CurrentUser)`
- 产出：`SkillService.listMySkills(CurrentUser)`
- 产出：`SkillService.createMySkill(CurrentUser, SkillCreateRequest)`
- 产出：`SkillService.upsertFile(CurrentUser, Long skillId, String path, String content)`
- 产出：`SkillValidator.validatePath(String path)`
- 产出：`SkillValidator.validateSkillMarkdown(String content)`

- [ ] **步骤 1：编写路径校验测试**

测试用例：

- 接受 `SKILL.md`
- 接受 `references/checklist.md`
- 接受 `scripts/analyze.java`
- 拒绝 `../secret`
- 拒绝 `/etc/passwd`
- 拒绝 `C:\Users\a`
- 拒绝空路径

- [ ] **步骤 2：编写 `SKILL.md` 校验测试**

测试用例：

- 接受包含 `name` 和 `description` 的 frontmatter。
- 拒绝缺少 `name`。
- 拒绝缺少 `description`。

- [ ] **步骤 3：实现 entities 和 mappers**

映射 `skills`、`skill_files`、`user_skill_settings`。

- [ ] **步骤 4：实现 validator**

统一使用 `/` 分隔路径，拒绝目录穿越、绝对路径和盘符。首段只允许 `SKILL.md`、`references`、`scripts`、`assets`。

- [ ] **步骤 5：实现 service 和 controller**

接口：

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

- [ ] **步骤 6：运行测试**

运行：

```bash
cd backend
mvn -Dtest=SkillValidatorTest test
```

预期：skill 校验测试通过。

- [ ] **步骤 7：提交**

```bash
git add backend/src/main/java/com/example/myagent/skill backend/src/test/java/com/example/myagent/skill
git commit -m "feat: 添加 MySQL skill 文件树"
```

## 任务 8：Skill Materializer

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/skill/SkillMaterializer.java`
- 新建：`backend/src/test/java/com/example/myagent/skill/SkillMaterializerTest.java`

**接口与产物：**
- 消费：`SkillService` 中启用的 SYSTEM 和 USER skills。
- 产出：`SkillMaterializer.materializeForUser(Long userId): Path`
- 产出：AgentScope Harness 可读取的本机 skill 缓存目录。

- [ ] **步骤 1：编写 materializer 测试**

给定同名 system skill 和 user skill，断言 user skill 覆盖 system skill。断言写出文件：

```text
<cache>/<userId>/<skillName>/SKILL.md
<cache>/<userId>/<skillName>/references/checklist.md
```

- [ ] **步骤 2：实现缓存 key**

使用 `skillId + "-" + updatedAtEpochMillis` 判断 skill 目录是否需要重写。

- [ ] **步骤 3：实现安全写文件**

目标 path 必须 resolve 到 cache root 下，normalize 后必须仍以 cache root 开头。

- [ ] **步骤 4：集成 AgentScope config**

每次聊天请求前 materialize 当前用户启用的 skills，并把 skill root 交给 AgentScope gateway。

- [ ] **步骤 5：运行测试**

运行：

```bash
cd backend
mvn -Dtest=SkillMaterializerTest test
```

预期：materializer 测试通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent/skill/SkillMaterializer.java backend/src/test/java/com/example/myagent/skill/SkillMaterializerTest.java
git commit -m "feat: 为 AgentScope materialize skills"
```

## 任务 9：权限与记忆接口

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/permission/*`
- 新建：`backend/src/main/java/com/example/myagent/memory/*`

**接口与产物：**
- 产出：`PermissionService.getMode(CurrentUser, String sessionId): PermissionModeDto`
- 产出：`PermissionService.setMode(CurrentUser, String sessionId, PermissionModeDto): PermissionModeDto`
- 产出：`MemoryService.getSummary(CurrentUser): String`
- 产出：`MemoryService.listDaily(CurrentUser): List<String>`
- 产出：`MemoryService.getDaily(CurrentUser, LocalDate): String`

- [ ] **步骤 1：添加权限模式 DTO**

允许值：`DEFAULT`、`EXPLORE`、`ACCEPT_EDITS`、`DONT_ASK`、`BYPASS`。

- [ ] **步骤 2：实现权限接口**

读取或更新模式前必须校验会话归属。

- [ ] **步骤 3：实现记忆只读接口**

通过共享存储抽象读取。第一版可以使用应用自有 MySQL 存储或 AgentScope 共享状态适配层；Controller 契约保持稳定。

- [ ] **步骤 4：添加无效模式和归属测试**

无效模式返回 `400`。访问其他用户会话返回 `404` 或 `403`。

- [ ] **步骤 5：运行测试**

运行：

```bash
cd backend
mvn test
```

预期：当前后端测试全部通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent/permission backend/src/main/java/com/example/myagent/memory backend/src/test/java/com/example/myagent
git commit -m "feat: 添加权限和记忆接口"
```

## 任务 10：自我进化提案流程

**文件：**
- 新建：`backend/src/main/java/com/example/myagent/evolution/*`
- 新建：`backend/src/test/java/com/example/myagent/evolution/EvolutionServiceTest.java`

**接口与产物：**
- 产出：`EvolutionService.createProposal(CurrentUser, EvolutionCreateRequest)`
- 产出：`EvolutionService.approve(CurrentUser, Long id)`
- 产出：`EvolutionService.reject(CurrentUser, Long id)`
- 产出：`EvolutionService.apply(CurrentUser, Long id)`

- [ ] **步骤 1：编写状态流转测试**

允许流转：

```text
DRAFT -> APPROVED
DRAFT -> REJECTED
APPROVED -> APPLIED
```

拒绝流转：

```text
REJECTED -> APPLIED
APPLIED -> REJECTED
```

- [ ] **步骤 2：实现 entity 和 mapper**

映射 `agent_evolution_proposals`。

- [ ] **步骤 3：实现 service**

`SKILL` proposal 的 `apply` 只能创建或更新 USER skill。`MEMORY` 追加到用户记忆。`TOOL_POLICY` 只应用低风险建议。`PROMPT` 和 `CODE_PATCH` 需要 ADMIN。

- [ ] **步骤 4：实现 controller**

接口：

```text
GET  /api/evolution/proposals
POST /api/evolution/proposals/{id}/approve
POST /api/evolution/proposals/{id}/reject
POST /api/evolution/proposals/{id}/apply
```

- [ ] **步骤 5：运行测试**

运行：

```bash
cd backend
mvn -Dtest=EvolutionServiceTest test
```

预期：进化提案测试通过。

- [ ] **步骤 6：提交**

```bash
git add backend/src/main/java/com/example/myagent/evolution backend/src/test/java/com/example/myagent/evolution
git commit -m "feat: 添加自我进化提案流程"
```

## 任务 11：Vue 应用基础与登录

**文件：**
- 新建：`frontend/src/main.ts`
- 新建：`frontend/src/router.ts`
- 新建：`frontend/src/App.vue`
- 新建：`frontend/src/api/client.ts`
- 新建：`frontend/src/api/auth.ts`
- 新建：`frontend/src/stores/auth.ts`
- 新建：`frontend/src/views/LoginView.vue`

**接口与产物：**
- 产出 auth store：`token`、`user`、`login`、`register`、`loadMe`、`logout`。
- 产出路由守卫：未登录用户跳转 `/login`。

- [ ] **步骤 1：创建 Vue 入口**

挂载 app，安装 Pinia、router 和 Element Plus。

- [ ] **步骤 2：实现 API client**

封装 `fetch`，自动附加 JWT，解析 JSON 错误，暴露 `apiGet`、`apiPost`、`apiPut`、`apiDelete`。

- [ ] **步骤 3：实现 auth store**

token 保存到 `localStorage`。`loadMe` 调用 `/api/auth/me`。

- [ ] **步骤 4：实现登录/注册页面**

使用 Element Plus 表单。登录成功跳转 `/chat`。

- [ ] **步骤 5：运行前端构建**

运行：

```bash
cd frontend
npm install
npm run build
```

预期：构建成功。

- [ ] **步骤 6：提交**

```bash
git add frontend/src frontend/package.json frontend/vite.config.ts
git commit -m "feat: 添加 Vue 登录基础"
```

## 任务 12：Vue 聊天工作台与流解析

**文件：**
- 新建：`frontend/src/api/chat.ts`
- 新建：`frontend/src/stores/sessions.ts`
- 新建：`frontend/src/stores/chat.ts`
- 新建：`frontend/src/views/ChatView.vue`
- 新建：`frontend/src/components/SessionSidebar.vue`
- 新建：`frontend/src/components/ChatTranscript.vue`
- 新建：`frontend/src/components/Composer.vue`
- 新建：`frontend/src/components/ToolEventCard.vue`

**接口与产物：**
- 产出：`streamChat(sessionId: string, message: string, onEvent: (event) => void): Promise<void>`
- 产出 chat store 消息模型，支持事件：`text_delta`、`tool_call`、`tool_result`、`permission_required`、`evolution_proposal`、`done`、`error`。

- [ ] **步骤 1：实现 session API 和 store**

通过 `/api/chat/sessions` 加载、创建、删除会话。

- [ ] **步骤 2：实现 NDJSON parser**

缓存 chunk，按 `\n` 切分，解析完整行，保留半截行到下一个 chunk。

- [ ] **步骤 3：实现 chat store**

追加用户消息，创建 assistant draft，追加 delta，挂载工具卡片，显示错误。

- [ ] **步骤 4：实现聊天工作台组件**

左侧会话列表，中间 transcript，底部输入框。

- [ ] **步骤 5：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建成功。

- [ ] **步骤 6：提交**

```bash
git add frontend/src/api/chat.ts frontend/src/stores/sessions.ts frontend/src/stores/chat.ts frontend/src/views/ChatView.vue frontend/src/components
git commit -m "feat: 添加流式聊天工作台"
```

## 任务 13：Vue Skill、权限、记忆、进化面板

**文件：**
- 新建：`frontend/src/api/skills.ts`
- 新建：`frontend/src/api/memory.ts`
- 新建：`frontend/src/api/permissions.ts`
- 新建：`frontend/src/api/evolution.ts`
- 新建：`frontend/src/stores/skills.ts`
- 新建：`frontend/src/stores/evolution.ts`
- 新建：`frontend/src/components/PermissionPanel.vue`
- 新建：`frontend/src/components/SkillPanel.vue`
- 新建：`frontend/src/components/SkillFileTree.vue`
- 新建：`frontend/src/components/MemoryPanel.vue`
- 新建：`frontend/src/components/ModelInfoPanel.vue`
- 新建：`frontend/src/components/EvolutionPanel.vue`

**接口与产物：**
- 消费：后端 skill、memory、permission、evolution endpoints。
- 产出：`ChatView` 右侧控制面板。

- [ ] **步骤 1：实现 API 模块**

为 spec 中所有相关接口创建 typed functions。

- [ ] **步骤 2：实现 Skill 面板**

tabs：`公共 Skill`、`我的 Skill`。公共 skill 除启用开关外只读。我的 skill 支持创建、编辑、删除和文件树。

- [ ] **步骤 3：实现权限面板**

模式下拉值必须是：`DEFAULT`、`EXPLORE`、`ACCEPT_EDITS`、`DONT_ASK`、`BYPASS`。

- [ ] **步骤 4：实现记忆面板**

只读展示长期记忆摘要和每日记忆列表。

- [ ] **步骤 5：实现进化面板**

展示 proposals，支持 approve、reject、apply，显示状态标签。

- [ ] **步骤 6：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建成功。

- [ ] **步骤 7：提交**

```bash
git add frontend/src/api frontend/src/stores frontend/src/components frontend/src/views/ChatView.vue
git commit -m "feat: 添加助手控制面板"
```

## 任务 14：端到端 Docker 验证和 README

**文件：**
- 新建：`README.md`
- 修改：`.env.example`
- 修改：`docker-compose.yml`
- 修改：`docker/mysql/init.sql`

**接口与产物：**
- 产出启动路径：`cp .env.example .env && docker compose up -d`。
- 产出本地后端和前端启动说明。

- [ ] **步骤 1：编写 README**

README 必须包含：

- 前置条件。
- 环境变量。
- Docker 启动。
- 后端本地启动。
- 前端本地启动。
- MySQL/Redis 说明。
- 模型供应商切换。
- 高权限工具安全说明。

- [ ] **步骤 2：验证 Docker Compose**

运行：

```bash
docker compose up -d --build
```

预期：MySQL、Redis、backend、frontend 容器启动。

- [ ] **步骤 3：手动验证健康状态**

打开 `http://localhost:5173`，注册、登录、创建会话、发送消息。如果缺少 API key，UI 必须显示清晰的模型配置错误。

- [ ] **步骤 4：运行全部后端测试**

运行：

```bash
cd backend
mvn test
```

预期：全部后端测试通过。

- [ ] **步骤 5：运行前端构建**

运行：

```bash
cd frontend
npm run build
```

预期：构建成功。

- [ ] **步骤 6：提交**

```bash
git add README.md .env.example docker-compose.yml docker/mysql/init.sql backend frontend
git commit -m "docs: 添加启动指南并验证 Docker 部署"
```

## 自检结果

规格覆盖：

- Docker-first 启动由任务 1 和任务 14 覆盖。
- 登录和用户隔离由任务 3 和任务 4 覆盖。
- 流式聊天由任务 5 和任务 6 覆盖。
- AgentScope Java 集成由任务 6 覆盖。
- MySQL skill 文件树和 materialize 由任务 7 和任务 8 覆盖。
- 权限和记忆由任务 9 覆盖。
- 自我进化 proposal 由任务 10 覆盖。
- Vue 登录、聊天、控制面板由任务 11、12、13 覆盖。
- 测试和 README 分布在各任务中，并由任务 14 收尾验证。

计划文本检查：

- 没有未完成标记或含糊实现指令。
- AgentScope SDK 类名不确定性被隔离在 `ChatAgentGateway` 和 `AgentEventMapper`，任务 6 通过编译验证真实 API。

类型一致性：

- `CurrentUser`、`ChatAgentGateway`、`ChatAgentRequest`、`StreamEventDto`、`SkillMaterializer`、`EvolutionService` 在生产任务和消费任务中的命名保持一致。
