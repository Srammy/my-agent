# MyAgent 迁移 DD_Rag 单层 chunk 与 PgVector 实施计划

> 设计依据：`docs/superpowers/specs/2026-08-14-pgvector-ddrag-rag-migration-design.md`

## 目标

将当前知识库的入库与检索改为 DD_Rag 的单层结构感知 chunk 方案：

`Kafka ETL → PostgreSQL document_chunks → PgVector 向量 → ES 关键词 → 查询规划/改写 → 双路召回 → 应用 RRF → 连续 chunk 聚簇 → 邻居扩窗 → 证据等级控制`。

保留 MySQL 业务数据、本地文件读取、Kafka 异步处理和当前 OCR/图片理解/表格抽取；不移植对象存储、Spring Event 和父子文档结构。

## 实施步骤

### 1. 建立 PostgreSQL/PgVector 基础设施

修改：

- `backend/pom.xml`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-docker.yml`（如当前配置存在对应 profile）
- `backend/src/main/java/com/example/myagent/config/KnowledgeProperties.java`
- `backend/src/main/java/com/example/myagent/config/KnowledgePostgresConfiguration.java`（新增）
- `backend/src/main/java/com/example/myagent/config/KnowledgePgVectorConfiguration.java`（新增）
- `docker-compose.yml`
- `.env.example` 或当前项目使用的 env 模板

实施内容：

1. 添加 PostgreSQL JDBC、Flyway PostgreSQL 和 Spring AI PgVector starter 依赖，版本与当前 Spring Boot/Spring AI BOM 对齐。
2. 增加 `pgvector/pgvector:pg16` Docker 服务、持久化 volume、健康检查和 backend `depends_on`。
3. 使用独立 PostgreSQL DataSource/JdbcTemplate，不改变 MySQL 主 DataSource；MySQL 继续由 MyBatis-Plus 和现有 Flyway 使用。
4. 为 PostgreSQL 配置独立 Flyway 执行器，迁移位置使用 `db/knowledge-migration`，避免 PostgreSQL migration 被 MySQL Flyway 执行。
5. 显式创建 PgVectorStore，使它使用 PostgreSQL 专用 JdbcTemplate 和当前 `EmbeddingModel`；不让 PgVector 误连 MySQL。
6. 增加 host、port、database、username、password、vector dimensions、table name、channel topK、RRF K、邻居窗口和查询规划配置绑定。
7. 启动时校验 PgVector dimensions 与 embedding 配置一致，配置错误时快速失败。

验证：

- 配置绑定测试能读取 PostgreSQL/PgVector/retrieval 配置。
- `docker compose config` 能解析 postgres、backend 依赖和 env 引用。
- PostgreSQL 健康检查通过，`vector` extension 可用。

### 2. 建立 PostgreSQL schema 与文档 chunk 仓储

新增/修改：

- `backend/src/main/resources/db/knowledge-migration/V1__knowledge_document_chunks.sql`
- `backend/src/main/resources/db/migration/V8__knowledge_document_chunk_count.sql`
- `backend/src/main/java/com/example/myagent/knowledge/chunk/KnowledgeChunk.java`（新增）
- `backend/src/main/java/com/example/myagent/knowledge/chunk/KnowledgeChunkRepository.java`（新增）
- `backend/src/main/java/com/example/myagent/knowledge/chunk/KnowledgeChunkRowMapper.java`（新增，如需要）
- `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentEntity.java`
- `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentDto.java`
- 对应 controller/service mapper 测试

实施内容：

1. 创建 PostgreSQL `document_chunks`：`user_id`、`document_id`、稳定 `chunk_id`、`chunk_index`、正文、摘要、字符范围、metadata JSON、时间字段。
2. 建立 `(user_id, document_id, chunk_index)` 和 `(user_id, chunk_id)` 唯一约束，避免重试重复写入。
3. repository 提供按用户+文档删除、批量插入、按用户+文档顺序查询、按用户+chunkId 批量查询等方法。
4. 所有公开检索/回查方法必须显式接收 `userId`；不提供只按 documentId 授权的外部入口。
5. MySQL 增加 `chunk_count`；后端 DTO、前端 API 类型和知识库卡片改为展示“chunk 数”。旧 `parent_count`/`child_count` 仅兼容保留，不再参与业务逻辑。

验证：

- repository 单元测试覆盖批量写入、重复键和按用户过滤。
- 两个用户使用同一 documentId/相同内容时，用户 A 的查询不会读到用户 B 的 chunk。

### 3. 将 Spring AI reader 改为 DD_Rag 单层结构感知切分

修改/新增：

- `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentContent.java`
- `backend/src/main/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReader.java`
- `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeChunkingService.java`（新增）
- `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentEtlException.java`

实施内容：

1. 将 `KnowledgeDocumentContent` 从 `parents` 改为扁平 `List<ChunkDocument>`。
2. 保留当前 PDF/Tika/Markdown 读取、无文本 PDF OCR、图片理解和表格 Markdown 抽取逻辑。
3. 把 DD_Rag `StructureAwareChunkTransformer` 的核心规则移植到 `KnowledgeChunkingService`：标题识别、段落拆分、句子拆分、最大预算、目标预算、overlap 和字符范围。
4. 生成稳定 `chunkId=documentId + ":" + chunkIndex`，metadata 记录 userId、documentId、sourceFilename、contentType、pageNumber、sectionPath、charStart、charEnd、chunkStrategy。
5. 不生成 parentId、childId、parentIndex 或 childIndex。
6. chunk 参数改为 env 配置，默认值沿用 DD_Rag 的结构感知策略，避免重新引入当前父子切分常量。

验证：

- Markdown 标题、普通段落、代码块、长段落和页码 metadata 的切分测试。
- OCR/表格抽取结果能继续进入 chunk 文本。
- 同一输入重复切分得到相同的 chunkId、chunkIndex 和文本。

### 4. 改造 Kafka ETL 的持久化、向量化和关键词索引

修改/新增：

- `backend/src/main/java/com/example/myagent/knowledge/KnowledgeDocumentEtlProcessor.java`
- `backend/src/main/java/com/example/myagent/knowledge/KnowledgeDocumentCleanupService.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeIndexService.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeElasticsearchIndexManager.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeChildDocument.java`（改为单层 chunk 文档或替换）
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeChunkIndexDocument.java`（新增）
- `backend/src/main/java/com/example/myagent/knowledge/embedding/KnowledgePgVectorService.java`（新增）

实施内容：

1. ETL 开始时按 `(userId, documentId)` 清理 PostgreSQL chunks、PgVector 向量和 ES chunk 索引。
2. reader 返回扁平 chunks 后，先批量写入 PostgreSQL，再按配置批量调用 `EmbeddingModel` 并写入 PgVector。
3. PgVector Document 的 id 使用稳定 chunkId，metadata 强制写入 userId/documentId/chunkId/chunkIndex/sourceFilename/pageNumber。
4. ES 建立单一关键词 chunk index，字段包含 userId、documentId、chunkId、chunkIndex、sourceFilename、pageNumber、content、status；删除 parent/child mapping 和 dense_vector mapping。
5. ES 关键词查询沿用 DD_Rag 的 match_phrase + match + filename boost + operator/rescore 思路，返回统一的 chunkId。
6. 三个外部索引都成功后，MySQL 文档更新 READY、chunkCount 和清空错误；成功后删除本地原文。
7. 任意步骤失败时执行同一用户+文档范围的补偿清理，再交由 Kafka retry/DLT 处理；不得清理其他用户数据。
8. 删除旧索引方法中的 parent/child 逻辑，确保 retry 不留下旧版本可检索数据。

验证：

- ETL 成功时 PostgreSQL、PgVector、ES 的 chunk 数一致。
- ETL 任意一步异常后，三处均无该文档半成品。
- retry 在已有旧数据时保持幂等，不产生重复 chunk/vector/ES 文档。
- 用户隔离测试覆盖清理、写入和失败补偿。

### 5. 移植 DD_Rag 的查询规划/改写

新增：

- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeQueryPlan.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeQueryPlanningService.java`
- `backend/src/main/java/com/example/myagent/config/KnowledgeQueryPlanningChatModelConfiguration.java`（如当前没有可复用的 Spring AI ChatModel）
- `backend/src/main/resources/prompts/knowledge/query-planning.st`

实施内容：

1. 适配 DD_Rag 的 DIRECT/REWRITE/DECOMPOSE 结果模型。
2. 使用当前 Spring AI ChatModel 进行结构化查询规划；模型不可用、输出解析失败、超过最大查询数时回退 DIRECT 原问题。
3. 规划器只接收用户 ID 和问题，不接触其他用户文档内容，不绕过检索授权。
4. 查询数量上限默认 3，并通过 env 控制；查询去重、空白归一化和顺序稳定。

验证：

- DIRECT、REWRITE、DECOMPOSE 解析测试。
- 模型异常和非法 JSON 回退原问题。
- userId 不影响授权范围，只影响调用上下文和日志关联。

### 6. 实现 PgVector + ES + 应用 RRF 混合检索

新增/修改：

- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgePgVectorRetrievalService.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeKeywordRetrievalService.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeRrf.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeEvidenceLevel.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeEvidenceBundle.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java`
- `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchHit.java`

实施内容：

1. 对每个 planned query 执行 PgVector 与 ES 两路召回，单路默认取 50 条。
2. PgVector 查询使用 metadata filter 强制 `userId`，并再次校验返回 metadata 中的 userId/documentId/chunkId。
3. ES 查询强制过滤 userId、READY 和可选 documentIds。
4. 以 chunkId 合并候选，按 `1/(rrfK+rank)` 累加多查询、多通道得分，默认 rrfK=60；不调用 ES 内置 RRF。
5. 按最终 hybridScore 过滤 `KNOWLEDGE_RETRIEVAL_MIN_RRF_SCORE`，再限制最终 topK。
6. 将候选按 documentId 分组，按连续 chunkIndex 聚簇；每个 cluster 选最高分 chunk 为 primary。
7. 通过 PostgreSQL 按 userId+documentId 取有效 chunk，前后扩展 neighborWindow，拼接 evidence。
8. 计算 NONE/WEAK/PARTIAL/SUFFICIENT，并返回 evidenceGuidance、文件名、页码、chunk 范围和 retrievalSource。
9. 任何 userId 或文档状态校验失败的候选直接丢弃，不降级为跨用户数据。

验证：

- 关键词命中、向量命中、双路命中和多查询命中的 RRF 排序测试。
- 相同 chunk 在两路结果中能合并，RRF 分值可重复计算。
- 连续 chunk 聚簇与 neighborWindow=0/1 的边界测试。
- 阈值拦截、空结果、四级证据等级测试。
- 用户 A/B 相同内容隔离测试，覆盖 ES、PgVector 和 PostgreSQL 回查。

### 7. 接入普通 Agent，并强制参考来源

修改：

- `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- `backend/src/test/java/com/example/myagent/chat/ChatServiceTest.java`
- `backend/src/test/java/com/example/myagent/knowledge/KnowledgeChatServiceTest.java`

实施内容：

1. 知识库会话仍调用普通 `ChatAgentGateway`/AgentScope 能力，不引入第二套回答 Agent。
2. 将 evidenceGuidance 和扩窗后的 evidence 注入 grounded prompt。
3. NONE 或阈值拦截时不调用 Agent，直接返回拒答文本。
4. Agent 输出完成后追加“参考来源”，来源使用 evidence 的文件名、页码和 chunk 范围。
5. 普通对话模式不触发知识库检索和来源约束。

验证：

- 知识库命中时仍由普通 Agent 生成回答。
- 无命中/弱相关时不调用 Agent 或只允许受限回答。
- 参考来源始终是回答最后一个文本事件。
- 普通对话回归测试保持通过。

### 8. 更新知识库页面与 API 文档计数

修改：

- `frontend/src/api/knowledge.ts`
- `frontend/src/components/KnowledgePanel.vue`
- `frontend/src/stores/knowledge.ts`
- 前端对应 API/store/component 测试
- `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentDto.java`

实施内容：

1. 文档列表由“父文档/子文档”改为“chunk 数”。
2. 继续展示状态、失败原因、上传时间、文件大小和删除/重试按钮。
3. 处理接口字段从 parentCount/childCount 迁移到 chunkCount，同时兼容旧接口数据为空的情况。

验证：

- READY、PROCESSING、FAILED 文档卡片展示正确。
- chunkCount 为 0、正常值和缺省值的渲染测试。
- 删除/重试操作不改变用户隔离行为。

### 9. 完成 Docker 与集成验证

执行：

1. 运行后端单元测试和前端测试。
2. 使用 `docker compose config` 检查最终配置。
3. 启动 `docker compose up -d --build`，检查 MySQL、Redis、Kafka、ES、PostgreSQL、backend、frontend 健康状态。
4. 创建两个测试用户，分别上传同名/同内容和不同内容文档。
5. 验证单用户列表、上传状态、失败重试、删除中的文档不会被检索。
6. 验证用户 A 的知识库问答不会返回用户 B 的 chunk、文件名或来源。
7. 验证“如何安装 Python”等关键词问题可以命中对应 chunk，而不再只返回无关文档。
8. 保存服务日志和关键 API 响应，最后再报告测试中存在的预先失败项。

## 测试命令

后端：

```powershell
cd D:\ideaccproj\myagent\backend
mvn -q test
```

前端：

```powershell
cd D:\ideaccproj\myagent\frontend
npm test -- --run
```

Docker 配置与启动：

```powershell
cd D:\ideaccproj\myagent
docker compose config
docker compose up -d --build
```

## 完成定义

- 单层 chunk 入库，不存在运行时 parent/child 依赖。
- PostgreSQL `document_chunks`、PgVector 和 ES 关键词索引均支持 userId 强过滤。
- 查询规划失败可回退，双路召回由应用层 RRF 融合。
- 连续 chunk 聚簇、邻居扩窗和证据等级控制均有测试。
- 知识库回答由普通 Agent 生成，并以参考来源结尾。
- Docker 服务可启动，至少完成一个真实文档的上传、异步解析、检索和用户隔离验证。
