# AgentScope Java 通用助手设计方案

日期：2026-07-04

## 目标

使用 AgentScope Java 官方 SDK 构建一个 Java 技术栈的通用助手。系统支持登录、多会话、流式输出、工具调用、公共和个人 skill、权限控制、长期记忆、多机部署、Docker 一键启动和可审计的自我进化。

默认模型为 `dashscope:qwen-plus`，API key 从环境变量 `DASHSCOPE_API_KEY` 读取。系统也支持通过配置切换到 OpenAI-compatible 模型服务。

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

## 总体架构

系统采用前后端分离架构，并支持本地开发、单机 Docker Compose 和生产多机三种运行方式。

```text
Vue 3 前端 / Nginx
  -> Nginx / SLB
    -> Spring Boot 多副本
      -> MySQL：用户、会话、skills、skill 文件、进化建议
      -> Redis：AgentState、分布式状态、会话恢复
      -> 本机缓存目录：运行时 materialize skill 文件
      -> DashScope / OpenAI-compatible 模型服务
```

后端多副本保持无状态。请求打到任意机器，都通过 JWT 获取当前用户，通过 MySQL 读取业务数据，通过 Redis 恢复 AgentScope 状态。

## 技术栈

后端：

- Java 21。
- Spring Boot 3。
- Spring WebFlux。
- Spring Security + JWT。
- MySQL。
- Redis。
- MyBatis-Plus。
- AgentScope Java `io.agentscope:agentscope-harness:2.0.0-RC4`。
- Maven。
- Docker。

前端：

- Vue 3。
- Vite。
- TypeScript。
- Pinia。
- Vue Router。
- Element Plus。
- Nginx。
- Docker。
- `fetch` + `ReadableStream` 处理 NDJSON 流式输出。

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

生产多机模式需要 Redis 状态存储扩展。实现时以 AgentScope Java 实际发布的 Redis 扩展 artifact 为准；如果 SDK 版本命名与文档不同，保留 `AgentStateStore` 适配层，避免业务代码直接依赖具体实现类。

## 应用配置

默认本地配置：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/myagent?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password

agent:
  deployment:
    mode: local
  workspace:
    path: ./.agentscope/workspace
  model:
    provider: dashscope
    name: qwen-plus
    api-key-env: DASHSCOPE_API_KEY
  permission:
    default-mode: DEFAULT
  tools:
    file-tools-enabled: false
    shell-enabled: false
    http-fetch-enabled: false
    mcp-enabled: false
```

Docker/生产多机配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:mysql}:3306/myagent?useSSL=false&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USERNAME:root}
    password: ${MYSQL_PASSWORD}

agent:
  deployment:
    mode: distributed
  state-store:
    type: redis
    redis:
      uri: redis://${REDIS_HOST:redis}:6379
      key-prefix: myagent:agent-state:
  skill:
    storage: mysql
    cache-dir: ./.agentscope/cache/skills
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

## Docker 启动与部署

项目必须提供 Docker-first 启动方式。

本地和单机部署使用 Docker Compose：

```text
docker compose up -d
```

容器组成：

```text
myagent-mysql      MySQL，存用户、会话、skills、进化建议
myagent-redis      Redis，存 AgentState 和分布式状态
myagent-backend    Spring Boot + AgentScope Java
myagent-frontend   Nginx 托管 Vue dist，反代 /api 到 backend
```

需要新增文件：

```text
backend/
  Dockerfile
  .dockerignore

frontend/
  Dockerfile
  nginx.conf
  .dockerignore

docker/
  mysql/
    init.sql
  backend/
    application-docker.yml

docker-compose.yml
.env.example
```

`docker-compose.yml` 结构：

```yaml
services:
  mysql:
    image: mysql:8.4
    container_name: myagent-mysql
    environment:
      MYSQL_DATABASE: myagent
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
    volumes:
      - mysql_data:/var/lib/mysql
      - ./docker/mysql/init.sql:/docker-entrypoint-initdb.d/init.sql
    ports:
      - "3306:3306"

  redis:
    image: redis:7
    container_name: myagent-redis
    ports:
      - "6379:6379"

  backend:
    build: ./backend
    container_name: myagent-backend
    depends_on:
      - mysql
      - redis
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DASHSCOPE_API_KEY: ${DASHSCOPE_API_KEY}
      MYSQL_HOST: mysql
      MYSQL_USERNAME: root
      MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      REDIS_HOST: redis
    ports:
      - "8080:8080"

  frontend:
    build: ./frontend
    container_name: myagent-frontend
    depends_on:
      - backend
    ports:
      - "5173:80"

volumes:
  mysql_data:
```

后端 Dockerfile 使用多阶段构建：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

前端 Dockerfile 使用 Vite 构建后由 Nginx 托管：

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

前端 Nginx 负责：

- 托管 Vue 静态文件。
- 对 `/api` 反代到后端。
- 支持流式响应，不缓存 `/api/chat/**/stream`。

生产多机部署以镜像为交付物：

- 构建 backend 镜像。
- 构建 frontend 镜像。
- MySQL 使用独立托管或主从集群。
- Redis 使用独立托管或 Redis Cluster。
- 后端镜像多副本部署。
- 前端/Nginx/SLB 负责入口和负载均衡。

Docker Compose 用于本地开发、演示、单机部署；真正多机生产推荐 Kubernetes、Docker Swarm、云厂商容器服务，或多台机器上的 Docker + 外部 Nginx/SLB。

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
4. 前端请求后端时携带 `Authorization: Bearer <token>`。
5. Spring Security 校验 JWT。
6. Controller 通过当前登录态获取用户。
7. 调用 AgentScope 时，使用当前用户 id 作为 `RuntimeContext.userId`。

核心表：

```sql
create table users (
  id bigint primary key auto_increment,
  username varchar(64) not null unique,
  password_hash varchar(255) not null,
  display_name varchar(64),
  role varchar(32) not null default 'USER',
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

所有会话、skill、记忆、权限、进化建议都按登录用户隔离。普通用户不能看到或操作其他用户数据。

## AgentScope Runtime

后端启动时创建 `HarnessAgent` 或通过工厂创建可复用 agent 实例。模型由 `agent.model` 配置决定。每轮聊天从当前登录用户和当前会话构造 `RuntimeContext`：

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

AgentScope 根据 `(userId, sessionId)` 隔离 AgentState、上下文、权限、记忆、工具状态和会话执行。同一个会话串行执行，不同会话可以并行执行。

## 多机部署

支持两种模式：

- `local`：开发环境，本地 workspace，本地状态存储。
- `distributed`：生产多机，Redis 存 AgentState 和分布式状态，MySQL 存业务数据，后端多副本无状态部署。

多机部署要求：

- 不能依赖某一台机器的本地 AgentState。
- skill 源数据存 MySQL。
- skill 运行时 materialize 到当前机器本机缓存目录。
- 一次流式请求固定在一个后端节点直到结束。
- 下一轮请求可以命中另一台机器，并通过 Redis 恢复状态。
- 负载均衡需要支持长连接或流式响应。

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

流式响应使用 NDJSON，便于 `POST` 请求、JSON body 和 `Authorization` 请求头共存。

示例事件：

```json
{"type":"reply_start","replyId":"..."}
{"type":"text_delta","delta":"你好"}
{"type":"tool_call","name":"calculator","input":"..."}
{"type":"tool_result","name":"calculator","output":"..."}
{"type":"permission_required","toolCallId":"...","message":"..."}
{"type":"evolution_proposal","proposalId":"...","title":"建议创建 Skill"}
{"type":"done"}
{"type":"error","message":"..."}
```

前端使用 `fetch` 和 `ReadableStream` 读取流，按换行切分并逐行解析 JSON。

## 工具系统

默认开启的安全工具：

- 当前时间工具。
- 简单计算器工具。
- 会话摘要/标题辅助工具。
- 应用自有 todo/planning 工具。
- skill 加载工具。
- 记忆读取和追加工具。
- 进化建议生成工具。

可配置开启的高权限工具：

- 文件读取。
- 文件搜索。
- 文件写入/编辑。
- shell/bash 执行。
- HTTP fetch。
- MCP tools。

高权限工具默认关闭。开启后仍然受 AgentScope 权限模式和规则控制。自我进化流程不能自动打开高权限工具。

## 权限控制

默认权限模式为 `DEFAULT`。

接口：

```text
GET  /api/permissions/sessions/{sessionId}
PUT  /api/permissions/sessions/{sessionId}
POST /api/permissions/sessions/{sessionId}/confirm
```

支持模式：

- `DEFAULT`：默认安全模式，敏感工具调用需要确认。
- `EXPLORE`：只读探索模式。
- `ACCEPT_EDITS`：允许在工作区内编辑。
- `DONT_ASK`：无人值守模式，需要确认的操作直接拒绝。
- `BYPASS`：可信 sandbox 才使用的放行模式。

权限模式属于会话 AgentState，并按登录用户和会话隔离。UI 展示工具调用确认卡片，用户批准或拒绝后由后端写回 AgentScope 对应确认流程。

## Skill 设计

skill 分两类：

- `SYSTEM`：公共 skill，所有用户可用。
- `USER`：个人 skill，只属于当前用户。

skill 全部存 MySQL，支持目录结构：

```text
SKILL.md
references/**
scripts/**
assets/**  后续扩展
```

表结构：

```sql
create table skills (
  id bigint primary key auto_increment,
  owner_type varchar(16) not null,
  owner_user_id bigint null,
  name varchar(100) not null,
  description varchar(255) not null,
  enabled tinyint(1) not null default 1,
  created_at datetime not null,
  updated_at datetime not null,
  unique key uk_skill_owner_name (owner_type, owner_user_id, name)
);

create table skill_files (
  id bigint primary key auto_increment,
  skill_id bigint not null,
  path varchar(500) not null,
  content mediumtext null,
  content_type varchar(64) not null default 'text/markdown',
  executable tinyint(1) not null default 0,
  created_at datetime not null,
  updated_at datetime not null,
  unique key uk_skill_file_path (skill_id, path)
);

create table user_skill_settings (
  id bigint primary key auto_increment,
  user_id bigint not null,
  skill_id bigint not null,
  enabled tinyint(1) not null default 1,
  unique key uk_user_skill_setting (user_id, skill_id)
);
```

安全规则：

- 每个 skill 必须存在 `SKILL.md`。
- `SKILL.md` 必须包含 `name` 和 `description` frontmatter。
- `path` 必须是相对路径。
- 禁止 `../`。
- 禁止绝对路径。
- 禁止 Windows 盘符。
- `scripts/**` 首版只作为文本给 agent 阅读，不直接执行。
- 后续如果允许执行脚本，必须走权限系统确认。

运行时加载：

```text
MySQL skills + skill_files
  -> 加载当前用户启用的 SYSTEM skills
  -> 加载当前用户启用的 USER skills
  -> 用户同名 skill 覆盖公共 skill
  -> materialize 到本机缓存目录
  -> AgentScope Harness 读取 skill 目录
```

缓存 key 为 `skillId + updatedAt`。缓存目录可以安全删除，因为 MySQL 是源数据。

## Skill 接口与 UI

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

管理员接口可以复用同一套 service，但需要 `ADMIN` 角色才能创建、编辑、删除公共 skill。

右侧 Skill 面板分两个 tab：

```text
公共 Skill | 我的 Skill
```

公共 Skill：

- 普通用户可查看。
- 普通用户可启用/停用对自己是否生效。
- 普通用户不可编辑。
- 管理员可创建、编辑、删除。

我的 Skill：

- 新建。
- 编辑。
- 删除。
- 启用/停用。
- 文件树管理。

文件树示例：

```text
SKILL.md
references/
  checklist.md
  style-guide.md
scripts/
  analyze.java
```

## 长期记忆

记忆按用户隔离。多机下不能只依赖某台机器本地文件。

首版采用共享存储策略：

- 记忆内容优先存 MySQL，或通过 AgentScope 可共享状态存储写入 Redis/OSS。
- UI 只读查看长期记忆。
- 不允许直接编辑长期记忆。
- 记忆修正通过“进化建议”审批后应用。

接口：

```text
GET /api/memory
GET /api/memory/daily
GET /api/memory/daily/{date}
```

## 自我进化设计

自我进化采用提案制：可审计、可回滚、用户批准后应用。agent 不能在生产环境中偷偷修改自身代码、公共 skill 或高权限工具策略。

首版支持四类进化：

1. 记忆进化：总结用户偏好、业务术语、项目约定。
2. Skill 进化：发现重复任务后生成个人 skill 草稿。
3. 工具策略进化：建议启用或停用工具，但不自动打开高权限工具。
4. Prompt/配置进化：生成 system prompt、模型参数、权限模式建议。

代码层进化不自动执行。agent 只能生成 patch 或建议，后续接 PR、测试、人工 review 流程。

表结构：

```sql
create table agent_evolution_proposals (
  id bigint primary key auto_increment,
  user_id bigint not null,
  session_id varchar(64) null,
  type varchar(32) not null,
  title varchar(200) not null,
  summary varchar(1000) null,
  content mediumtext not null,
  status varchar(32) not null,
  created_at datetime not null,
  updated_at datetime not null,
  applied_at datetime null
);
```

`type` 可取值：

- `MEMORY`
- `SKILL`
- `TOOL_POLICY`
- `PROMPT`
- `CODE_PATCH`

`status` 可取值：

- `DRAFT`
- `APPROVED`
- `REJECTED`
- `APPLIED`

进化流程：

```text
Agent 发现可改进点
  -> 生成 evolution proposal
  -> UI 展示给用户
  -> 用户批准或拒绝
  -> 批准后应用到 memory / user skill / tool policy / prompt config
  -> 记录 applied_at
```

首版允许自动应用的内容：

- 用户批准后的个人记忆更新。
- 用户批准后的个人 skill 创建或修改。
- 用户批准后的低风险工具策略建议。

需要管理员批准的内容：

- 公共 skill 修改。
- 系统 prompt 修改。
- 代码 patch。
- 高权限工具策略变更。

进化接口：

```text
GET  /api/evolution/proposals
POST /api/evolution/proposals/{id}/approve
POST /api/evolution/proposals/{id}/reject
POST /api/evolution/proposals/{id}/apply
```

## Vue 前端

页面：

- 登录/注册页。
- 聊天工作台。

聊天工作台布局：

- 左侧：会话列表。
- 中间：消息流、工具卡片、错误提示、进化建议卡片。
- 底部：输入框、发送按钮、停止按钮。
- 右侧：模型信息、权限模式、公共 Skill、我的 Skill、记忆查看、进化建议。

前端结构：

```text
frontend/
  package.json
  vite.config.ts
  nginx.conf
  Dockerfile
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
```

UI 风格采用面向工作台的紧凑布局，不做 landing page。

## 错误处理

后端：

- 缺少或无效 JWT 返回 `401`。
- 访问其他用户资源返回 `404` 或 `403`。
- 缺少模型 API key 返回清晰的模型配置错误。
- 模型流式调用失败时发送 `error` 流事件。
- skill 校验失败返回 `400`。
- 非法 skill 文件路径返回 `400`。
- MySQL 或 Redis 不可用时，生产模式启动失败并输出明确错误。
- 本机 skill 缓存目录缺失时按需创建。

前端：

- 认证错误跳转登录页。
- 流式错误显示在对话区。
- 权限确认显示为确认卡片。
- skill 校验错误显示在编辑器旁边。
- 进化建议应用失败时保留原 proposal 状态并显示错误。

## 测试计划

后端测试：

- 注册、登录、JWT 校验。
- 会话归属过滤。
- DashScope 与 OpenAI-compatible 配置解析。
- `RuntimeContext` 使用当前登录用户 id 和 session id。
- Redis state store 配置选择。
- skill frontmatter 校验。
- skill 文件路径安全校验。
- SYSTEM/USER skill 加载优先级。
- skill materialize 缓存更新。
- 进化建议创建、批准、拒绝、应用。
- 使用 mock `ChatAgentGateway` 的聊天流接口 smoke test。

前端测试或手动 smoke check：

- 登录和 token 保存。
- 会话列表渲染。
- NDJSON 流解析。
- `text_delta` 事件更新对话区。
- 工具事件渲染。
- 权限模式选择。
- skill 文件树编辑。
- 进化建议审批。

Docker 验证：

- `docker compose up -d` 可以启动 MySQL、Redis、backend、frontend。
- frontend 容器可以反代 `/api` 到 backend。
- backend 容器能连接 MySQL 和 Redis。
- 容器环境下能完成注册、登录、建会话和流式聊天。

手动验收：

1. 启动 MySQL 和 Redis，或运行 `docker compose up -d`。
2. 启动后端。
3. 启动 Vue dev server 或访问 frontend 容器。
4. 注册并登录。
5. 创建会话。
6. 发送消息，确认浏览器中实时流式输出。
7. 用第二个账号验证会话隔离。
8. 创建个人 skill，并添加 `references/**` 和 `scripts/**` 文件。
9. 启用或停用公共 skill。
10. 查看记忆面板。
11. 生成并批准一个 skill 进化建议。
12. 多副本后端下验证会话状态可恢复。

## 开发和启动命令

Docker 一键启动：

```bash
cp .env.example .env
docker compose up -d
```

后端本地启动：

```bash
cd backend
mvn spring-boot:run
```

前端本地启动：

```bash
cd frontend
npm install
npm run dev
```

访问地址：

```text
Docker 前端: http://localhost:5173
本地前端:   http://localhost:5173
后端 API:   http://localhost:8080
```

## 实现顺序

1. 创建后端 Maven 项目。
2. 添加 Spring Boot、MySQL、Redis、MyBatis-Plus、Spring Security、JWT、AgentScope Harness 依赖。
3. 编写 Docker Compose、后端 Dockerfile、前端 Dockerfile、Nginx 配置和 `.env.example`。
4. 实现数据库 schema 和登录认证。
5. 实现会话元数据接口。
6. 接入 AgentScope Java 模型和 `HarnessAgent`。
7. 接入 local/distributed 状态存储配置。
8. 实现流式聊天接口和事件映射。
9. 实现 MySQL skill、skill 文件树、materialize 缓存。
10. 实现权限模式接口。
11. 实现记忆只读查看。
12. 实现自我进化 proposal 流程。
13. 创建 Vue 3 前端项目。
14. 实现登录 UI 和路由守卫。
15. 实现聊天工作台和 NDJSON 流解析。
16. 实现公共 Skill、我的 Skill、文件树编辑。
17. 实现权限、记忆、进化建议面板。
18. 增加核心测试。
19. 验证 Docker Compose 启动链路。
20. 编写 README 启动和部署说明。

## 验收标准

- 用户可以注册和登录。
- 登录用户只能看到自己的会话。
- Docker Compose 可以一键启动 MySQL、Redis、backend、frontend。
- 前端容器可以通过 Nginx 访问页面并反代 `/api`。
- 多副本后端下，会话状态可通过 Redis 恢复。
- 默认模型为 DashScope `qwen-plus`。
- 可以通过配置切换到 OpenAI-compatible 模型。
- 浏览器可以流式显示助手回复。
- 工具调用事件可以在 UI 展示。
- 权限确认可以展示和处理。
- skills 支持 `SYSTEM` 和 `USER` 两类。
- skills 支持 `SKILL.md`、`references/**`、`scripts/**`。
- skill 数据存 MySQL，多机可用。
- 长期记忆可以按用户只读查看。
- agent 可以生成进化建议。
- 用户批准后可以应用记忆或个人 skill 进化。
- 公共 skill、系统 prompt、代码 patch、高权限工具策略变更需要管理员批准。
- 高权限工具默认关闭，不能被进化流程自动打开。
- 缺少 API key、未登录、越权访问时都有清晰错误。
