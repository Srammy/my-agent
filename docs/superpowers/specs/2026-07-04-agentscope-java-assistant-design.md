# AgentScope Java 通用助手设计方案

日期：2026-07-04

## 目标

使用 AgentScope Java 官方 SDK 构建一个 Java 技术栈的通用助手。系统需要支持：

- 浏览器对话 UI。
- 流式输出。
- 登录系统。
- 根据登录用户隔离数据。
- 多会话隔离。
- 工具调用。
- 用户可配置 skill。
- 权限控制。
- 长期记忆。
- 默认接入 DashScope/Qwen。
- 通过配置切换到 OpenAI-compatible 模型服务。

默认模型为 `dashscope:qwen-plus`，API key 从环境变量 `DASHSCOPE_API_KEY` 读取。

## 官方依据

设计基于 AgentScope Java 官方文档和 Maven 元数据：

- AgentScope Java 快速开始：https://java.agentscope.io/v2/en/docs/quickstart.html
- Agent 构建块：https://java.agentscope.io/v2/en/docs/building-blocks/agent.html
- Message 与 Event 流式事件：https://java.agentscope.io/v2/en/docs/building-blocks/message-and-event.html
- Tool 工具系统：https://java.agentscope.io/v2/en/docs/building-blocks/tool.html
- Permission 权限系统：https://java.agentscope.io/v2/en/docs/building-blocks/permission-system.html
- Context 与 AgentState：https://java.agentscope.io/v2/en/docs/building-blocks/context.html
- Harness Workspace：https://java.agentscope.io/v2/en/docs/harness/workspace.html
- Harness Memory：https://java.agentscope.io/v2/en/docs/harness/memory.html
- Harness Skill：https://java.agentscope.io/v2/en/docs/harness/skill.html
- Maven 元数据：https://maven.aliyun.com/repository/public/io/agentscope/agentscope-harness/maven-metadata.xml

## 范围

首版包含：

- Spring Boot 后端。
- Vue 3 前端。
- MySQL 存储用户账号和会话元数据。
- JWT 登录认证。
- AgentScope Java `HarnessAgent`。
- 流式聊天接口。
- 基于登录用户和会话的隔离。
- 默认 DashScope/Qwen 模型配置。
- OpenAI-compatible 模型配置。
- 基础工具和可配置高权限工具。
- 用户 skill 管理。
- 权限模式管理。
- 长期记忆只读查看。
- 面向核心流程的测试。
- README 启动说明。

首版不包含：

- OAuth、MFA、刷新令牌轮换、管理员后台等完整生产认证体系。
- 多节点部署。
- AgentState 的 Redis/MySQL 存储改造，除非 AgentScope Java SDK 已提供可直接接入的实现。
- 在 UI 中编辑长期记忆。
- 完整 MCP 市场或插件市场。后端先预留配置扩展点。

## 总体架构

系统采用前后端分离架构。

后端技术栈：

- Java 21。
- Spring Boot 3。
- Spring WebFlux，用于流式响应。
- Spring Security + JWT。
- MySQL，存储用户和会话元数据。
- MyBatis-Plus，简化数据库访问。
- AgentScope Java `io.agentscope:agentscope-harness:2.0.0-RC4`。
- Maven。

前端技术栈：

- Vue 3。
- Vite。
- TypeScript。
- Pinia。
- Vue Router。
- Element Plus。
- `fetch` + `ReadableStream` 处理流式输出。

后端负责：

- 登录认证。
- 当前用户解析。
- 模型配置。
- AgentScope runtime 构造。
- 会话归属校验。
- skill 文件访问。
- 权限模式更新。
- 记忆读取。

前端负责：

- 登录/注册页面。
- 聊天工作台。
- 会话列表。
- 流式消息渲染。
- 工具调用展示。
- 权限确认交互。
- skill 管理界面。
- 记忆只读查看。

前端不允许用户手动选择 `userId`。`userId` 由后端从 JWT 登录态解析。

## 后端项目结构

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

## Maven 配置

后端依赖 AgentScope Java Harness：

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

当前 Maven Central 没查到该 artifact，但阿里云 Maven public 仓库存在 `io.agentscope:agentscope-harness`。后端 `pom.xml` 需要配置仓库：

```xml
<repository>
  <id>aliyun-public</id>
  <url>https://maven.aliyun.com/repository/public</url>
</repository>
```

## 应用配置

默认配置：

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

OpenAI-compatible 配置示例：

```yaml
agent:
  model:
    provider: openai-compatible
    name: deepseek-chat
    base-url: https://api.deepseek.com
    api-key-env: OPENAI_API_KEY
```

API key 只从环境变量读取，不写入 MySQL，也不在前端输入。

## 登录与用户隔离

系统内置登录系统，用户信息存储在 MySQL。

认证接口：

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

认证流程：

1. 用户注册账号。
2. 用户登录后获得 JWT。
3. Vue 前端把 JWT 保存在 `localStorage`。
4. 前端请求后端时携带请求头：

```text
Authorization: Bearer <token>
```

5. Spring Security 校验 JWT。
6. Controller 通过当前登录态获取用户。
7. 调用 AgentScope 时，使用当前用户 id 作为 `RuntimeContext.userId`。

MySQL 表结构：

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

所有会话查询、修改、删除都必须带 `user_id` 条件。用户不能看到或操作其他用户的会话。

## AgentScope Runtime

后端启动时创建一个单例 `HarnessAgent`。模型由 `agent.model` 配置决定。Agent 会挂载：

- 模型。
- 基础工具。
- skill 支持。
- memory 支持。
- workspace 配置。
- compaction 配置。
- 默认权限模式。

每一轮聊天都从当前登录用户和当前会话构造 `RuntimeContext`：

```java
RuntimeContext runtimeContext = RuntimeContext.builder()
    .userId(currentUser.id().toString())
    .sessionId(sessionId)
    .build();
```

流式调用：

```java
Flux<AgentEvent> events = harnessAgent.streamEvents(userMessage, runtimeContext);
```

AgentScope 根据 `(userId, sessionId)` 隔离：

- AgentState。
- 上下文。
- 权限上下文。
- 记忆。
- 工具状态。
- 会话执行。

同一个会话串行执行，不同会话可以并行执行。

## 聊天接口

会话接口：

```text
POST   /api/chat/sessions
GET    /api/chat/sessions
GET    /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
POST   /api/chat/sessions/{sessionId}/stream
```

发送消息请求：

```json
{
  "message": "你好"
}
```

流式响应使用 NDJSON。选择 NDJSON 的原因是它天然适合 `POST` 请求、JSON body 和 `Authorization` 请求头。

示例事件：

```json
{"type":"reply_start","replyId":"..."}
{"type":"text_delta","delta":"你好"}
{"type":"tool_call","name":"calculator","input":"..."}
{"type":"tool_result","name":"calculator","output":"..."}
{"type":"permission_required","toolCallId":"...","message":"..."}
{"type":"done"}
```

错误事件：

```json
{"type":"error","message":"..."}
```

错误事件发送后，流关闭。

## 前端流式处理

前端使用 `fetch` 和 `ReadableStream`：

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

流解析器负责：

- 缓存半截 chunk。
- 按换行切分。
- 逐行解析 JSON。
- 根据事件类型更新对话区。

## 工具系统

默认开启的安全工具：

- 当前时间工具。
- 简单计算器工具。
- 会话摘要/标题辅助工具。
- 应用自有 todo/planning 工具，支持创建、列表、更新。
- skill 加载工具。
- 记忆读取和追加工具。

可配置开启的高权限工具：

- 文件读取。
- 文件搜索。
- 文件写入/编辑。
- shell/bash 执行。
- HTTP fetch。
- MCP tools。

高权限工具默认关闭。开启后仍然受 AgentScope 权限模式和规则控制。

## 权限控制

默认权限模式为 `DEFAULT`。

接口：

```text
GET  /api/permissions/sessions/{sessionId}
PUT  /api/permissions/sessions/{sessionId}
POST /api/permissions/sessions/{sessionId}/confirm
```

支持的模式：

- `DEFAULT`：默认安全模式，敏感工具调用需要确认。
- `EXPLORE`：只读探索模式。
- `ACCEPT_EDITS`：允许在工作区内编辑。
- `DONT_ASK`：无人值守模式，需要确认的操作直接拒绝。
- `BYPASS`：可信 sandbox 才使用的放行模式。

权限模式属于会话的 AgentState，并按登录用户和会话隔离。

如果工具调用需要用户确认，后端把 AgentScope 确认事件映射成前端 `permission_required` 事件。UI 展示确认卡片。后端实现会适配 AgentScope Java 的具体确认事件类名，但前端事件协议保持稳定。高权限工具默认关闭，因此确认适配未完成时也不会意外放行危险操作。

## Skill 管理

skill 按用户隔离存储：

```text
.agentscope/workspace/users/{userId}/skills/{skillName}/SKILL.md
```

接口：

```text
GET    /api/skills
POST   /api/skills
GET    /api/skills/{name}
PUT    /api/skills/{name}
DELETE /api/skills/{name}
```

保存 skill 时校验 `SKILL.md` frontmatter 至少包含：

```yaml
---
name: skill-name
description: what this skill does
---
```

Agent 通过 AgentScope Harness 的 skill 支持加载用户 skill。用户 A 不能读取或修改用户 B 的 skill 文件。

## 长期记忆

长期记忆按用户隔离：

```text
.agentscope/workspace/users/{userId}/MEMORY.md
.agentscope/workspace/users/{userId}/memory/YYYY-MM-DD.md
```

UI 提供只读记忆面板，用于查看：

- 整理后的长期记忆 `MEMORY.md`。
- 每日追加记忆文件列表。
- 某一天的记忆内容。

接口：

```text
GET /api/memory
GET /api/memory/daily
GET /api/memory/daily/{date}
```

首版不提供 UI 编辑记忆能力。原因是长期记忆会影响后续回答，错误编辑会污染 agent 行为。后续可以增加“删除/修正记忆”的受控操作。

## Vue 前端结构

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

页面：

- 登录/注册页。
- 聊天工作台。

聊天工作台布局：

- 左侧：会话列表、新建会话、删除会话。
- 中间：消息流、流式输出、工具调用卡片、错误提示。
- 底部：输入框、发送按钮、停止按钮。
- 右侧：模型信息、权限模式、skill 管理、记忆只读查看。

UI 风格采用面向工作台的紧凑布局，不做 landing page。

## 错误处理

后端：

- 缺少或无效 JWT 返回 `401`。
- 访问其他用户会话返回 `404` 或 `403`。
- 缺少模型 API key 返回清晰的模型配置错误。
- 模型流式调用失败时发送 `error` 流事件。
- skill 校验失败返回 `400`。
- MySQL 启动失败时应用启动失败，并输出 Spring 数据源错误。
- 用户 workspace 文件不存在时按需创建。

前端：

- 认证错误跳转登录页。
- 流式错误显示在对话区。
- 权限确认显示为确认卡片。
- skill 校验错误显示在编辑器旁边。

## 测试计划

后端测试：

- 注册和登录。
- JWT 校验。
- 会话归属过滤。
- DashScope 配置解析。
- OpenAI-compatible 配置解析。
- `RuntimeContext` 使用当前登录用户 id 和 session id。
- skill frontmatter 校验。
- 使用 mock `ChatAgentGateway` 的聊天流接口 smoke test。

前端测试或手动 smoke check：

- 登录和 token 保存。
- 会话列表渲染。
- NDJSON 流解析。
- `text_delta` 事件更新对话区。
- 工具事件渲染。
- 权限模式选择。
- skill 编辑器校验。

手动验收流程：

1. 启动 MySQL。
2. 启动后端。
3. 启动 Vue dev server。
4. 注册并登录。
5. 创建会话。
6. 发送消息，确认浏览器中实时流式输出。
7. 用第二个账号验证会话隔离。
8. 创建和编辑 skill。
9. 查看记忆面板。
10. 切换权限模式。

## 开发命令

后端：

```bash
cd backend
mvn spring-boot:run
```

前端：

```bash
cd frontend
npm install
npm run dev
```

访问地址：

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
```

## 实现顺序

1. 创建后端 Maven 项目。
2. 添加 Spring Boot、MySQL、MyBatis-Plus、Spring Security、JWT、AgentScope Harness 依赖。
3. 实现数据库 schema 和登录认证。
4. 实现会话元数据接口。
5. 接入 AgentScope Java 模型和 `HarnessAgent`。
6. 实现流式聊天接口和事件映射。
7. 创建 Vue 3 前端项目。
8. 实现登录 UI 和路由守卫。
9. 实现聊天工作台和 NDJSON 流解析。
10. 实现 skill 管理。
11. 实现记忆只读查看。
12. 实现权限模式 UI 和后端接口。
13. 增加核心测试。
14. 编写 README 启动说明。

## 验收标准

- 用户可以注册和登录。
- 登录用户只能看到自己的会话。
- 登录用户可以创建会话并与助手对话。
- 助手回复可以实时流式显示在浏览器 UI。
- 默认模型为 DashScope `qwen-plus`。
- 可以通过配置切换到 OpenAI-compatible 模型。
- 基础工具调用事件可以在 UI 中展示。
- skill 文件可以按用户创建、查看、编辑、删除。
- 长期记忆可以按用户只读查看。
- 权限模式可以按会话查看和切换。
- 缺少 API key、未登录、越权访问时都有清晰错误。
