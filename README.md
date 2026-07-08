# MyAgent

MyAgent 是一个基于 AgentScope 的助手项目，后端使用 Java/Spring Boot，前端使用 Vue。默认 Docker 启动路径会同时启动 MySQL、Redis、后端和前端。

## 前置要求

- 如果要端到端启动，需要 Docker 和 Docker Compose。
- 如果要本地开发后端，需要 JDK 21 和 Maven 3.9+。
- 如果要本地开发前端，需要 Node.js 22+ 和 npm。
- 如果要调用真实模型，需要 DashScope API key。没有 key 时，请保持 `AGENT_SCOPE_ENABLED=false`，使用占位实现或错误展示路径。

## 环境变量

启动 Docker 前，先复制示例配置文件：

```bash
cp .env.example .env
```

重要变量：

- `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USERNAME`, `MYSQL_PASSWORD`：MySQL 配置。示例配置使用 root 用户和 `change-me`，仅适合本地开发。
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_DATABASE`：Redis 配置。
- `SECURITY_JWT_SECRET`：后端必需配置。非本地开发环境请替换为足够长的随机密钥。
- `AGENT_SCOPE_ENABLED`：默认值为 `false`。只有在模型凭证和运行时行为都准备好后，才设置为 `true`。
- `DASHSCOPE_API_KEY`：默认模型提供方使用的 DashScope key。
- `AGENT_MODEL_PROVIDER`：默认值为 `dashscope`。
- `AGENT_MODEL_NAME`：默认值为 `dashscope:qwen-plus`。
- `AGENT_MODEL_BASE_URL`：OpenAI-compatible 提供方可选的 base URL。
- `AGENT_MODEL_API_KEY_ENV`：保存模型 API key 的环境变量名。默认值为 `DASHSCOPE_API_KEY`。
- `VITE_API_PROXY_TARGET`：本地 Vite 开发代理目标，可选。默认值为 `http://localhost:8080`。

## Docker 启动

```bash
cp .env.example .env && docker compose up -d
```

前端发布在 `http://localhost:5173`，并将 `/api/` 请求代理到后端容器。后端监听 `http://localhost:8080`。

如果 UI 提示后端或模型配置错误，可以使用 `docker compose logs -f backend` 查看日志。

## 后端本地启动

先启动本地 MySQL 和 Redis。默认后端本地配置要求：

- MySQL：`localhost:3306`，数据库 `myagent`，用户 `root`，密码 `root`。
- Redis：`localhost:6379`，数据库 `0`。

然后运行：

```bash
cd backend
$env:SECURITY_JWT_SECRET="dev-only-change-me-to-a-long-random-secret"
$env:AGENT_SCOPE_ENABLED="false"
mvn spring-boot:run
```

在 PowerShell 中，上面的 `$env:` 赋值只对当前 shell 生效。在 Bash 中，请使用 `export SECURITY_JWT_SECRET=...` 和 `export AGENT_SCOPE_ENABLED=false`。

## 前端本地启动

安装依赖并启动 Vite：

```bash
cd frontend
npm install
npm run dev
```

开发服务器默认地址为 `http://localhost:5173`。API 调用需要单独在 `http://localhost:8080` 启动后端。如果后端运行在其他地址，请在执行 `npm run dev` 前设置 `VITE_API_PROXY_TARGET`。

## MySQL 和 Redis

Docker Compose 会启动 MySQL 8.4 和 Redis 7，并配置健康检查。`docker/mysql/init.sql` 只创建 `myagent` 数据库并设置 UTF-8 默认值。应用表结构由后端 Flyway 迁移管理，位置在 `backend/src/main/resources/db/migration`；不要在 Docker 初始化脚本中重复编写表 DDL。

Compose 会将 MySQL 数据持久化到 `mysql_data` volume。如果要重置本地 Docker 数据，请停止服务并显式删除 volume：

```bash
docker compose down -v
```

## 模型提供方切换

默认提供方是 DashScope，`AGENT_MODEL_NAME=dashscope:qwen-plus`。

如果使用 OpenAI-compatible 提供方，请在 `.env` 中配置提供方、base URL、API key 环境变量名和模型名：

```dotenv
AGENT_MODEL_PROVIDER=openai-compatible
AGENT_MODEL_BASE_URL=https://example.com/v1
AGENT_MODEL_API_KEY_ENV=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=replace-me
AGENT_MODEL_NAME=openai-compatible:your-model-name
AGENT_SCOPE_ENABLED=true
```

没有可用 API key 时，请保持 `AGENT_SCOPE_ENABLED=false`。

## 高权限工具安全

后端默认关闭高权限工具：

- `agent.tools.file-tools-enabled=false`
- `agent.tools.shell-enabled=false`
- `agent.tools.http-fetch-enabled=false`
- `agent.tools.mcp-enabled=false`

文件、shell、HTTP fetch 和 MCP 工具可能会根据运行时能力访问本地文件、执行命令、连接网络或调用外部服务。只应在可信的开发环境中启用这些工具。

权限模式同样重要。`DEFAULT`、`EXPLORE` 和 `ACCEPT_EDITS` 是相对更安全的交互模式。`DONT_ASK`，尤其是 `BYPASS`，会降低或移除确认边界，只应在可信沙箱中配合可丢弃的凭证和数据使用。

## AgentScope 原生记忆与 Skill 体系

### 记忆
记忆由 AgentScope Harness 自动维护，无需应用干预：
- `MEMORY.md` — 精选长期记忆（每次推理注入系统提示）
- `memory/YYYY-MM-DD.md` — 每日原始记忆日志

### Skill 管理
Skill 文件存储在 AgentScope workspace 文件系统，不使用 MySQL：
- 正式 Skill：`skills/<skillName>/SKILL.md` + `references/`、`scripts/`、`assets/`
- Agent 草稿：`skills/_drafts/<skillName>/`

### 自学习审核
Agent 自动创建的 Skill 草稿需通过 Web 审核界面（`/api/skill-reviews`）人工批准后才能晋升为正式 Skill，晋升后受 EnvironmentFilter 和 CanaryFilter 可见性控制。

### 部署模式
- **本地模式**（`agent.deployment.mode=local`）：Workspace 存储在 `.agentscope/workspace`
- **分布式模式**（`agent.deployment.mode=distributed`）：通过 Redis-backed remote filesystem with `IsolationScope.USER` 实现多副本共享状态、用户隔离
