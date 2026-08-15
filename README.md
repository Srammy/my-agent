# MyAgent

MyAgent 是一个基于 AgentScope、Spring Boot 和 Vue 的个人 AI Agent 应用，提供普通对话、Skill 管理和个人知识库问答能力。

## 主要能力

- 用户注册、登录和 JWT 鉴权。
- 普通对话：使用 AgentScope Agent 能力进行流式对话。
- 知识库问答：使用普通 Agent 生成答案，但回答上下文仅来自当前用户的知识库检索结果。
- 文档上传、列表展示、异步解析、重试和删除。
- 支持 TXT、Markdown、PDF、DOC、DOCX、XLS、XLSX、PNG、JPG、JPEG。
- 使用 Spring AI 完成文档读取和切分。
- PDF 无文本页转图片后，使用多模态模型执行 OCR、图片内容理解和表格结构化抽取。
- 使用 Elasticsearch 8.x 做关键词检索，PostgreSQL + pgvector 做向量检索。
- 应用层自实现 RRF，不依赖 Elasticsearch 内置 RRF。
- 支持查询规划、连续 chunk 聚簇、邻居扩窗和证据等级控制。
- 未检索到达到相关性阈值的证据时，知识库问答直接拒答，不调用 Agent 生成答案。
- 个人数据按 `userId` 隔离，用户只能访问自己的会话、文档、chunk、向量和检索结果。

## 技术栈

- 后端：Java 21、Spring Boot 3.3、Spring WebFlux、Spring Security、MyBatis-Plus、Flyway。
- Agent：AgentScope Harness。
- 文档处理：Spring AI Document Reader、Apache Tika、PDFBox。
- 消息队列：Kafka 3.9，使用 Outbox Relay、重试主题和 DLT。
- 关键词检索：Elasticsearch 8.15。
- 向量检索：PostgreSQL 16 + pgvector。
- 业务数据库：MySQL 8.4。
- 状态存储：Redis 7。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia。
- 部署：Docker Compose。

## 系统结构

```text
浏览器
  │
  ├── 前端容器 :5173
  │       └── /api 代理到后端
  │
  └── 后端容器 :8080
          ├── MySQL：用户、会话、消息、文档元数据、ETL 任务
          ├── Redis：Agent 状态
          ├── Kafka：文档异步 ETL 消息、重试和 DLT
          ├── PostgreSQL + pgvector：chunk 和向量
          ├── Elasticsearch：关键词 chunk 索引
          └── 本地知识库目录：上传中的源文件
```

MySQL、Redis、Kafka、PostgreSQL 和 Elasticsearch 只加入 Docker 内部网络，不映射到宿主机端口。前端映射到 `127.0.0.1:5173`，后端映射到 `8080`。

## 前置要求

- Docker Desktop 和 Docker Compose。
- 本地开发后端：JDK 21、Maven 3.9+。
- 本地开发前端：Node.js 22+、npm。
- 使用真实 Agent、Embedding 或多模态模型时，需要对应模型提供方的 API Key。

## 配置环境

复制配置模板：

```bash
cp .env.example .env
```

PowerShell：

```powershell
Copy-Item .env.example .env
```

至少需要填写以下配置：

```dotenv
MYSQL_ROOT_PASSWORD=change-me
MYSQL_PASSWORD=change-me
REDIS_PASSWORD=change-me
SECURITY_JWT_SECRET=change-me-to-a-long-random-secret
KNOWLEDGE_POSTGRES_PASSWORD=change-me
DASHSCOPE_API_KEY=your-api-key
```

`MYSQL_PASSWORD` 在默认 root 配置下应与 `MYSQL_ROOT_PASSWORD` 保持一致。

### Agent 模型配置

```dotenv
AGENT_SCOPE_ENABLED=true
AGENT_MODEL_PROVIDER=dashscope
AGENT_MODEL_NAME=dashscope:qwen-plus
AGENT_MODEL_API_KEY_ENV=DASHSCOPE_API_KEY
```

没有可用模型 Key 时，可将 `AGENT_SCOPE_ENABLED=false`，使用项目中的占位 Agent 路径进行本地开发。

如果使用 OpenAI-compatible 提供方：

```dotenv
AGENT_MODEL_PROVIDER=openai-compatible
AGENT_MODEL_BASE_URL=https://example.com/v1
AGENT_MODEL_API_KEY_ENV=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=your-api-key
AGENT_MODEL_NAME=openai-compatible:your-model
AGENT_SCOPE_ENABLED=true
```

### 知识库模型配置

Embedding 和 OCR/多模态模型分别配置：

```dotenv
KNOWLEDGE_EMBEDDING_PROVIDER=dashscope
KNOWLEDGE_EMBEDDING_MODEL=text-embedding-v4
KNOWLEDGE_EMBEDDING_DIMENSIONS=1024
KNOWLEDGE_EMBEDDING_API_KEY_ENV=DASHSCOPE_API_KEY

KNOWLEDGE_MULTIMODAL_PROVIDER=dashscope
KNOWLEDGE_MULTIMODAL_MODEL=qwen3.7-plus
KNOWLEDGE_MULTIMODAL_API_KEY_ENV=DASHSCOPE_API_KEY
```

其中 `KNOWLEDGE_MULTIMODAL_MODEL` 同时用于图片 OCR、图片内容理解和表格抽取。

### 检索和切分配置

```dotenv
KNOWLEDGE_RETRIEVAL_MIN_RRF_SCORE=0.02
KNOWLEDGE_RETRIEVAL_TOP_K=8
KNOWLEDGE_RETRIEVAL_CHANNEL_TOP_K=50
KNOWLEDGE_RETRIEVAL_RRF_K=60
KNOWLEDGE_RETRIEVAL_NEIGHBOR_WINDOW=1
KNOWLEDGE_QUERY_PLANNING_ENABLED=true

KNOWLEDGE_CHUNKING_TARGET_TOKENS=240
KNOWLEDGE_CHUNKING_MAX_TOKENS=320
KNOWLEDGE_CHUNKING_OVERLAP_TOKENS=32
```

`KNOWLEDGE_RETRIEVAL_MIN_RRF_SCORE` 是知识库问答的拦截阈值。检索结果最高 RRF 分数低于该值时，系统返回“未在知识库中找到相关内容，无法回答”，不会调用 Agent。

### 基础设施配置

Docker Compose 默认使用以下服务地址：

```dotenv
MYSQL_HOST=mysql
MYSQL_PORT=3306
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DATABASE=0
KNOWLEDGE_KAFKA_BOOTSTRAP_SERVERS=kafka:9092
KNOWLEDGE_ELASTICSEARCH_URL=http://elasticsearch:9200
KNOWLEDGE_POSTGRES_HOST=postgres
KNOWLEDGE_POSTGRES_PORT=5432
KNOWLEDGE_POSTGRES_DATABASE=myagent_knowledge
KNOWLEDGE_POSTGRES_USERNAME=postgres
KNOWLEDGE_PGVECTOR_TABLE=vector_store
KNOWLEDGE_PGVECTOR_DIMENSIONS=1024
```

完整配置以 [.env.example](.env.example) 和 `backend/src/main/resources/application.yml` 为准。

## Docker 启动

```bash
cp .env.example .env
# 编辑 .env，填写数据库密码、JWT Secret 和模型 Key
docker compose --env-file .env up -d
```

访问：

- 前端：<http://127.0.0.1:5173/chat>
- 后端：<http://127.0.0.1:8080>

查看服务状态：

```bash
docker compose --env-file .env ps
```

查看后端日志：

```bash
docker compose --env-file .env logs -f backend
```

基于当前代码重新构建并重启：

```bash
docker compose --env-file .env build backend frontend
docker compose --env-file .env up -d --force-recreate --no-build
```

停止服务但保留数据卷：

```bash
docker compose --env-file .env down
```

只有在明确需要清空本地数据库、向量库、ES、Kafka、Redis 和上传文件时，才执行：

```bash
docker compose --env-file .env down -v
```

## 知识库处理流程

文档上传接口只负责保存源文件、写入文档元数据和创建 ETL 任务，解析和入库通过 Kafka 异步执行：

```text
上传文档
  ↓
MySQL 保存文档元数据和任务
  ↓
Outbox Relay 投递 Kafka
  ↓
Kafka Consumer 消费
  ↓
Spring AI 文档读取
  ├── Markdown：MarkdownDocumentReader
  ├── PDF：按页读取；无文本页转 PNG 后调用多模态模型
  ├── DOC/DOCX/XLS/XLSX：TikaDocumentReader
  └── 图片：直接调用多模态模型
  ↓
OCR、图片理解、表格 Markdown 结构化
  ↓
单层 chunk 切分和 Embedding
  ↓
PostgreSQL/pgvector + Elasticsearch 写入
  ↓
MySQL 文档状态更新为 READY
```

本分支使用单层文档 chunk，不保留父子文档结构。解析失败时会清理已写入的 chunk、向量和 Elasticsearch 索引记录，并通过 Kafka 重试；达到最大重试次数后进入 DLT，文档状态变为 `FAILED`。配额或权限类多模态失败不会无限重试。

## 检索和知识库问答流程

知识库问答使用当前用户的 `userId` 作为隔离条件：

1. 查询规划服务改写用户问题，生成检索查询。
2. Elasticsearch 执行关键词召回。
3. PostgreSQL/pgvector 执行向量召回。
4. 应用层使用 RRF 合并两路结果，公式为 `1 / (rrfK + rank)`。
5. 对连续 chunk 聚簇，并按 `KNOWLEDGE_RETRIEVAL_NEIGHBOR_WINDOW` 扩展邻居 chunk。
6. 根据命中数量以及关键词/向量双路命中情况计算证据等级。
7. 低于 `KNOWLEDGE_RETRIEVAL_MIN_RRF_SCORE` 时直接拒答。
8. 有证据时，将证据注入普通 Agent，回答末尾附加参考来源。

知识库问答不会把检索不到的内容交给 Agent 自由发挥。普通对话模式不经过知识库检索，仍使用普通 Agent 能力。

## Web 页面

聊天页左侧会话列表会显示会话模式：

- `普通对话`
- `知识库问答`

右侧辅助面板包含：

- `Skill`：我的 Skill、自进化 Skill 审核。
- `知识库`：上传文档、查看状态和 chunk 数量、重试失败文档、删除文档。

知识库文档状态：

- `PROCESSING`：等待或正在解析。
- `READY`：已完成 chunk、向量和关键词索引。
- `FAILED`：解析或入库失败，可点击重试。

## API 概览

所有 `/api/**` 接口都需要 `Authorization: Bearer <JWT>`，登录和注册接口除外。

### 认证

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/auth/me
```

### 会话和对话

```text
POST   /api/chat/sessions
GET    /api/chat/sessions
GET    /api/chat/sessions/{sessionId}
PUT    /api/chat/sessions/{sessionId}
DELETE /api/chat/sessions/{sessionId}
POST   /api/chat/sessions/{sessionId}/stream
```

### 知识库文档

```text
POST   /api/knowledge/documents
GET    /api/knowledge/documents
DELETE /api/knowledge/documents/{documentId}
POST   /api/knowledge/documents/{documentId}/retry
```

删除文档会清理 MySQL 文档和任务、上传源文件、PostgreSQL chunk、pgvector 向量以及 Elasticsearch 索引记录。

## 本地开发

### 后端

先准备 MySQL、Redis、PostgreSQL、Elasticsearch 和 Kafka，或直接使用 Docker Compose 启动基础设施。然后：

```powershell
cd backend
$env:SECURITY_JWT_SECRET="dev-only-change-me-to-a-long-random-secret"
$env:MYSQL_HOST="localhost"
$env:MYSQL_DATABASE="myagent"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="your-mysql-password"
$env:REDIS_HOST="localhost"
$env:REDIS_PASSWORD="your-redis-password"
$env:KNOWLEDGE_POSTGRES_HOST="localhost"
$env:KNOWLEDGE_POSTGRES_PASSWORD="your-postgres-password"
$env:KNOWLEDGE_ELASTICSEARCH_URL="http://localhost:9200"
$env:KNOWLEDGE_KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev
```

默认前端地址为 <http://localhost:5173>，Vite 会将 `/api` 请求代理到 `http://localhost:8080`。如后端地址不同，设置：

```bash
VITE_API_PROXY_TARGET=http://localhost:8080 npm run dev
```

## 测试和构建

前端测试、类型检查和构建：

```bash
cd frontend
npm test
npm run typecheck
npm run build
```

后端测试：

```bash
cd backend
mvn test
```

部分后端测试使用 Testcontainers，需要 Docker Desktop 正常运行。

## 数据和目录

- MySQL：用户、会话、聊天消息、知识库文档元数据和 Kafka ETL 任务。
- PostgreSQL：`document_chunks` 以及 Spring AI pgvector 表。
- Elasticsearch：关键词检索 chunk 索引。
- Redis：Agent 状态。
- Docker volume `knowledge_data`：知识库上传源文件及知识库存储目录。
- AgentScope workspace：Skill 和 Agent workspace 文件，实际位置由部署配置决定。
- Docker volume `mysql_data`、`knowledge_postgres_data`、`elasticsearch_data`、`kafka_data`、`redis_data`：服务持久化数据。

个人知识库源文件按以下路径隔离：

```text
<knowledge-storage-root>/<userId>/<documentId>/source/<filename>
```

任何文档查询、检索、重试和删除都会校验当前用户归属。

## 安全注意事项

- 不要将 `.env`、模型 API Key、数据库密码或 JWT Secret 提交到 Git。
- 生产环境应为 MySQL、Redis、PostgreSQL、Kafka 和 Elasticsearch 配置独立强密码和网络策略。
- Elasticsearch 的 Docker Compose 开发配置关闭了安全认证，仅适用于本地环境。
- Agent 高权限工具默认关闭：文件工具、Shell、HTTP Fetch 和 MCP。
- `DONT_ASK` 和 `BYPASS` 权限模式会降低确认边界，只应在可信、可丢弃的数据环境中使用。
- 生产部署建议开启 HTTPS、限制管理接口访问范围，并为 Docker volume 做备份。

## 常见问题

### 上传返回 413

这是反向代理或 Nginx 的请求体大小限制。检查前端 Nginx 配置和实际代理链路的 `client_max_body_size`，同时确认文件不超过后端单文件 50 MB 限制。

### 文档长时间处于 PROCESSING

检查 Kafka、后端消费者和模型服务：

```bash
docker compose --env-file .env logs -f kafka backend
```

### 文档进入 FAILED

查看文档卡片中的状态并点击“重试”。如果是模型配额、权限或 API Key 问题，应先修复模型配置；如果是临时网络或服务错误，再执行重试。

### 知识库回答拒答

确认：

1. 文档状态为 `READY`。
2. 文档确实包含问题相关内容。
3. `KNOWLEDGE_RETRIEVAL_MIN_RRF_SCORE` 没有设置过高。
4. Elasticsearch、PostgreSQL/pgvector 和 Embedding 配置正常。

### 清空本地开发数据

以下命令会删除所有 Docker 持久化数据，包含用户、会话、文档、向量、索引和 Redis 状态，执行前请确认：

```bash
docker compose --env-file .env down -v
```
