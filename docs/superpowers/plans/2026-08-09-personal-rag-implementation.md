# 个人知识库 RAG 实现计划

> **给执行代理的说明：** 执行本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 技能。计划按照任务拆分，每个步骤使用复选框（`- [ ]`）跟踪完成状态。

**目标：** 在现有 MyAgent 中实现按用户隔离的个人知识库：用户上传文档后通过 Kafka 异步完成解析、OCR/图片理解、表格结构化抽取、父子文档切分、向量化和 Elasticsearch 8.x 双路检索；知识库问答复用普通 Agent，但只有检索到用户自己的文档内容时才允许调用 Agent。

**架构：** 上传事务写入 MySQL 文档记录和 Outbox 消息，Relay 将消息投递到 Kafka；ETL Consumer 使用 Spring AI 解析和切分文档，必要时使用多模态模型完成 OCR、图片理解和表格抽取，使用配置的 embedding 模型向量化 child chunks，最后幂等写入 Elasticsearch parent/child 两个索引。问答请求使用当前用户和文档状态过滤，在 Elasticsearch 中用 BM25 `standard` 检索与 `knn` 检索组成原生 RRF；无命中时直接返回固定拒答，不调用 Agent。

**技术栈：** Java 21、Spring Boot 3.3.5（先按当前版本验证 Spring AI 兼容性）、Spring AI、Spring Kafka、MySQL/MyBatis-Plus、Elasticsearch 8.x Java API Client、Kafka、Docker Compose、Vue 3、TypeScript、Vitest。

---

## 任务 1：增加 RAG 依赖、配置和本地基础设施

**涉及文件：**
- 修改：`backend/pom.xml`
- 修改：`backend/src/main/java/com/example/myagent/config/`（在现有配置目录旁增加配置类）
- 修改：`backend/src/main/resources/application.yml`
- 修改：`docker-compose.yml`
- 测试：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeConfigurationTest.java`

- [ ] **步骤 1：先编写失败的配置测试**

  增加 Spring 上下文测试，将 `knowledge.embedding.model`、`knowledge.embedding.dimensions`、`knowledge.multimodal.model` 以及 Kafka/Elasticsearch 地址绑定到 `KnowledgeProperties` Bean。断言向量维度为 `1024`，并确认模型名称来自配置，而不是服务中的硬编码常量。

- [ ] **步骤 2：运行专项测试，确认测试确实失败**

  在 `backend` 目录运行 `mvn -q -Dtest=KnowledgeConfigurationTest test`。此时应因配置属性类和绑定关系尚未实现而失败。

- [ ] **步骤 3：增加依赖和配置属性**

  增加文档读取器、模型客户端和结构化输出所需的 Spring AI BOM/模块，同时增加 Spring Kafka 和 Elasticsearch 8.x Java API Client。先保持当前 Spring Boot 父版本不变；如果依赖解析或编译证明选定的 Spring AI 版本与 Boot 3.3.5 不兼容，则在本任务内做最小化且有记录的父版本调整，并重新运行现有后端测试集。

  增加一个 `@ConfigurationProperties(prefix = "knowledge")` record，并定义明确的嵌套配置项：

  ```java
  public record KnowledgeProperties(
      Embedding embedding,
      Multimodal multimodal,
      Elasticsearch elasticsearch,
      Kafka kafka,
      Storage storage) {
    public record Embedding(String provider, String model, int dimensions, String apiKeyEnv) {}
    public record Multimodal(String provider, String model, String apiKeyEnv) {}
    public record Elasticsearch(String url, String username, String password,
                                String parentIndex, String childIndex) {}
    public record Kafka(String topic, String group, String bootstrapServers) {}
    public record Storage(String root) {}
  }
  ```

  默认模型配置为：向量模型 `text-embedding-v4`、向量维度 `1024`、多模态抽取模型 `qwen3.7-plus`。普通 Agent 对话配置单独保留（`AGENT_MODEL_NAME=qwen-plus`）。密钥只从环境变量读取，禁止写入源代码或配置文件。

- [ ] **步骤 4：增加 Docker 服务和健康检查**

  增加 Elasticsearch 8.x 和 Kafka 服务，使用持久化卷和私有网络；Kafka 使用 KRaft 单节点开发模式。两个服务都不暴露宿主机端口，Kafka 本地开发副本数设置为 `1`，并增加健康检查。保持 MySQL/Redis 现有行为不变。

- [ ] **步骤 5：运行测试并提交**

  运行 `mvn -q -Dtest=KnowledgeConfigurationTest test`，再运行现有后端测试集。提交信息使用 `feat: configure rag infrastructure`。

## 任务 2：增加按用户隔离的文档和会话持久化

**涉及文件：**
- 新增：`backend/src/main/resources/db/migration/V5__knowledge_documents.sql`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentEntity.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentMapper.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/job/KnowledgeDocumentJobEntity.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/job/KnowledgeDocumentJobMapper.java`
- 修改：`backend/src/main/java/com/example/myagent/session/ChatSessionEntity.java`
- 修改：`backend/src/main/java/com/example/myagent/session/SessionService.java`
- 修改：`backend/src/main/java/com/example/myagent/session/ChatSessionDto.java`
- 测试：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentMapperTest.java`
- 测试：`backend/src/test/java/com/example/myagent/session/` 下的现有会话测试

- [ ] **步骤 1：先编写失败的持久化测试**

  测试文档读取必须同时使用 `id` 和当前认证用户的 `userId`；测试任务表通过 `document_id` 保证唯一；测试新的会话 DTO 暴露固定的 `mode`（`NORMAL` 或 `KNOWLEDGE`）。确认历史会话默认按 `NORMAL` 处理。

- [ ] **步骤 2：运行专项测试并确认失败**

  运行知识库持久化测试和会话测试。此时应因数据库迁移、实体以及会话模式字段尚未实现而失败。

- [ ] **步骤 3：增加 Flyway 数据库迁移**

  创建 `knowledge_documents` 表，包含 `id`、`user_id`、文件名/内容类型/大小、`storage_key`、`status`、父文档数、子文档数、错误信息和时间字段。创建 `knowledge_document_jobs` 表，通过唯一的 `document_id` 关联任务，并记录 `user_id`、`status`、重试次数、最后错误和时间字段。为 `chat_sessions` 增加默认值为 `NORMAL` 的 `mode` 字段，并增加 `NORMAL`/`KNOWLEDGE` 约束。所有用户数据表都增加以 `user_id` 开头的索引。

- [ ] **步骤 4：增加实体、Mapper，并贯通会话模式**

  增加文档状态和会话模式枚举，以及对应的 MyBatis-Plus 实体和 Mapper。更新会话创建/列表 DTO 和服务方法，使模式能够持久化并返回。所有文档/任务查询都必须使用现有认证上下文中的用户 ID，不能把客户端传入的 `userId` 当作授权依据。

- [ ] **步骤 5：运行测试并提交**

  运行专项测试和完整的现有会话测试集。提交信息使用 `feat: persist user scoped knowledge documents`。

## 任务 3：实现文档上传、列表展示和持久化源文件存储

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentController.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentService.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentDto.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentStorage.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentJobService.java`
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentControllerTest.java`

- [ ] **步骤 1：先编写失败的 API 测试**

  测试使用 multipart 文件调用 `POST /api/knowledge/documents` 时返回 `202`，创建一个 `PROCESSING` 文档和一个 `PENDING` 任务，并且响应中不返回内部存储路径。测试 `GET /api/knowledge/documents` 只返回当前认证用户的文档。测试空文件或不支持的文件类型会返回校验错误，且不会创建任务。

- [ ] **步骤 2：运行 API 测试并确认失败**

  运行 `mvn -q -Dtest=KnowledgeDocumentControllerTest test`；此时接口尚未实现，测试应失败。

- [ ] **步骤 3：实现安全存储和上传事务**

  将原始文件存放在 `<root>/<userId>/<documentId>/source/<sanitized filename>` 下。路径必须由经过校验的 ID 和服务端生成的文档 ID 组成。使用一个事务写入文档记录和 Outbox 任务；事务提交前完成文件写入，事务失败时删除本次新写入的文件。返回状态为 `PROCESSING` 且父子文档数量为零的文档 DTO。

- [ ] **步骤 4：实现按用户隔离的文档列表**

  增加分页或有上限的文档列表接口，按创建时间倒序返回。映射 `PROCESSING`、`READY`、`FAILED` 三种状态；错误信息只返回给文档所有者，任何情况下都不返回源文件存储 key。

- [ ] **步骤 5：运行测试并提交**

  运行控制器专项测试以及现有 Web/安全测试。提交信息使用 `feat: add asynchronous knowledge document upload`。

## 任务 4：实现 Kafka Outbox Relay、重试和 DLT

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeDocumentProcessMessage.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeDocumentOutboxRelay.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeKafkaConfig.java`
- 修改：`backend/src/main/resources/application.yml`
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentOutboxRelayTest.java`

- [ ] **步骤 1：先编写失败的 Relay 测试**

  测试 `PENDING` 任务发布的消息以 `documentId` 为 Kafka key，消息体只包含 `documentId` 和 `userId`；只有 Kafka 发送成功后任务才变为 `SENT`。测试发送失败时任务仍可重试，并记录错误信息。

- [ ] **步骤 2：运行 Relay 测试并确认失败**

  运行 `mvn -q -Dtest=KnowledgeDocumentOutboxRelayTest test`；此时 Relay 和生产者配置尚未实现，测试应失败。

- [ ] **步骤 3：增加 Topic 和生产者/消费者配置**

  配置 Topic `myagent.knowledge.document.process`、消费者组 `myagent-knowledge-etl`、JSON 序列化、手动确认、有上限的并发数、重试退避和 DLT。使用 `documentId` 作为 Kafka key，保证同一文档的消息有序处理。

- [ ] **步骤 4：实现事务性消息 Relay**

  以小批量轮询待发送任务，使用 `KafkaTemplate` 发布消息，并且只在发送成功回调中更新任务状态。使用行锁或原子认领状态，避免多个应用实例并发发布同一任务。由于 ETL 流程必须幂等，重复消息也必须能够安全处理。

- [ ] **步骤 5：运行测试并提交**

  运行专项测试和 Kafka 配置测试。提交信息使用 `feat: publish knowledge etl jobs through kafka`。

## 任务 5：使用 Spring AI 实现解析、OCR、图片理解和表格抽取

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentReader.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/MultimodalExtraction.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/TableExtraction.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentContent.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReader.java`
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentReaderTest.java`

- [ ] **步骤 1：先编写失败的文档读取测试**

  测试数字文本/PDF 样例能够生成带页码的内容、父文档元数据和规范化文本。测试扫描件/图片样例会将图片媒体发送给配置的多模态模型，并保存 OCR 文本和图片描述。测试表格样例同时生成 Markdown 文本和经过校验的 `TableExtraction` JSON，其中包含表头、行、页码和置信度。

- [ ] **步骤 2：运行文档读取测试并确认失败**

  运行 `mvn -q -Dtest=KnowledgeDocumentReaderTest test`；此时 Spring AI Reader 和抽取结构尚未实现，测试应失败。

- [ ] **步骤 3：使用 Spring AI Reader 实现文本抽取**

  根据内容类型选择 Reader，在适合的场景使用 Spring AI 的 Tika/page/text Reader。元数据中保留页码、源文件名、内容类型和逻辑父文档序号。规范化空白字符时不能破坏表格行结构。

- [ ] **步骤 4：实现多模态内容抽取**

  为扫描页/图片构造包含文本指令和 `Media` 的 Spring AI 多模态 `UserMessage`。使用 `ChatModel` 和 Schema 校验请求结构化输出。`MultimodalExtraction` 必须包含 OCR 文本、图片描述和表格结果；表格结果需要序列化成用于检索的 Markdown，以及用于展示/审计的结构化 JSON。视觉模型处理失败时抛出可重试的 ETL 异常，不能将文档标记为就绪。

- [ ] **步骤 5：实现父子文档切分**

  按文档结构优先切分为约 1500–2000 tokens 的父文档，再切分为约 300–500 tokens、重叠 50–80 tokens 的子块。父子两级都保留文档/页码/内容类型元数据；每个表格子块都必须保留表头，并为每个子块设置 `parentId`。

- [ ] **步骤 6：运行测试并提交**

  运行文档读取/切分测试和完整后端测试集。提交信息使用 `feat: parse knowledge documents with spring ai`。

## 任务 6：增加 Elasticsearch 映射、向量化和幂等索引

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeElasticsearchIndexManager.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeEmbeddingService.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeIndexService.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeParentDocument.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeChildDocument.java`
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeIndexServiceTest.java`

- [ ] **步骤 1：先编写失败的索引测试**

  测试子块使用配置的向量模型进行向量化，且维度必须严格为 `1024`；测试父子记录携带相同的认证用户 `userId`、文档 ID 和状态。测试同一文档重复索引时生成确定性的 ID，不能产生重复记录。

- [ ] **步骤 2：运行索引测试并确认失败**

  运行 `mvn -q -Dtest=KnowledgeIndexServiceTest test`；此时 ES 映射和向量化服务尚未实现，测试应失败。

- [ ] **步骤 3：创建两个 Elasticsearch 索引**

  创建 `myagent_knowledge_parents` 和 `myagent_knowledge_children` 两个索引，并显式定义映射。子索引的 `content` 使用 text 类型，精确元数据使用 keyword/integer 类型，`embedding` 使用 `dims: 1024`、余弦相似度的 `dense_vector`。使用 `parentId` 建立逻辑关联，不使用 Elasticsearch join 字段。

- [ ] **步骤 4：实现向量化和批量写入**

  注入 Spring AI 的 `EmbeddingModel`，文档向量和查询向量都使用同一个配置模型；如果返回维度不是 `1024`，立即失败。使用 `documentId + parentIndex/childIndex` 生成确定性 ID，批量写入所有记录，重建前按 `userId + documentId` 删除旧记录。禁止使用客户端传入的用户 ID 写入任何记录。

- [ ] **步骤 5：运行测试并提交**

  运行索引测试和完整后端测试集。提交信息使用 `feat: index rag parent child documents in elasticsearch`。

## 任务 7：实现 Kafka ETL 消费者和失败清理

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentEtlProcessor.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentKafkaConsumer.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentCleanupService.java`
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentEtlProcessorTest.java`

- [ ] **步骤 1：先编写失败的 ETL 处理测试**

  测试处理成功后 MySQL 文档变为 `READY`，记录父子文档数量，在索引前删除之前尝试产生的 Elasticsearch 数据，并删除原始源文件。测试解析/索引失败时，只删除精确 `userId + documentId` 对应的 ES 父子记录；重试耗尽后将文档变为 `FAILED`，并保留原始源文件。

- [ ] **步骤 2：运行 ETL 处理测试并确认失败**

  运行 `mvn -q -Dtest=KnowledgeDocumentEtlProcessorTest test`；此时消费者和 ETL 编排尚未实现，测试应失败。

- [ ] **步骤 3：实现 ETL 处理器**

  根据消息中的 `documentId` 和持久化的 `userId` 加载文档并校验所有权；从服务端存储 key 读取源文件，完成解析/切分/抽取、向量化和批量索引。只有完整 ES 写入成功后才更新 MySQL 状态；只有 `READY` 状态更新成功后才删除源文件。

- [ ] **步骤 4：实现重试和 DLT 清理**

  对暂时性的模型、Kafka 或 ES 失败，从 Listener 抛出可重试异常。每次失败尝试都要在重试前清理部分 ES 数据；进入 DLT 后将文档标记为 `FAILED`，持久化简短错误信息，保留源文件供重新处理，并确保清理操作幂等。绝不能删除其他文档或其他用户的数据。

- [ ] **步骤 5：运行测试并提交**

  运行 ETL 专项测试和全部后端测试。提交信息使用 `feat: process rag documents asynchronously`。

## 任务 8：实现 Elasticsearch RRF 检索和基于普通 Agent 的知识库问答

**涉及文件：**
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java`
- 新增：`backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchHit.java`
- 修改：`backend/src/main/java/com/example/myagent/chat/ChatService.java`
- 修改：`backend/src/main/java/com/example/myagent/chat/ChatController.java`
- 新增/修改：`backend/src/main/java/com/example/myagent/chat/` 下的聊天 DTO
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeSearchServiceTest.java`
- 新增/修改：`backend/src/test/java/com/example/myagent/chat/` 下的聊天测试

- [ ] **步骤 1：先编写失败的检索和聊天测试**

  测试 ES 请求同时包含 BM25 `standard` Retriever 和向量 `knn` Retriever，并通过原生 RRF 合并；两个分支都必须过滤当前 `userId` 和 `READY` 状态。测试知识库会话有命中时只将检索到的父文档上下文传给普通 Agent Gateway。测试零命中时返回固定拒答，不调用 Agent Gateway。

- [ ] **步骤 2：运行专项测试并确认失败**

  运行检索和聊天测试；此时 RRF 服务和知识库模式分支尚未实现，测试应失败。

- [ ] **步骤 3：实现 Elasticsearch 原生 RRF 检索**

  使用同一个配置的向量模型将查询向量化；针对子文档 `content` 构建 `standard` Retriever，针对子文档 `embedding` 构建 `knn` Retriever，再使用 Elasticsearch 8.x 原生 RRF 合并。两个检索分支都应用 `userId`、可选的 `documentId` 范围和 `status = READY` 过滤。按父文档 ID 合并或去重，再通过 `userId + parentId` 查询父文档，并返回源文件名/页码/块信息。

- [ ] **步骤 4：将知识库会话接入普通 Agent**

  在聊天处理流程中增加固定的会话模式。`NORMAL` 保持现有流程。`KNOWLEDGE` 先执行检索；有命中时，只用检索到的上下文和来源元数据构造 grounded prompt，然后调用同一个普通 Agent Gateway 并流式返回。无命中时返回 `未在知识库中找到相关内容。`，不调用 Agent。

- [ ] **步骤 5：运行测试并提交**

  运行检索/聊天测试和完整后端测试集。提交信息使用 `feat: add rrf grounded knowledge chat`。

## 任务 9：增加前端文档 API、会话模式和轮询状态

**涉及文件：**
- 修改：`frontend/src/api/chat.ts`
- 新增：`frontend/src/api/knowledge.ts`
- 修改：`frontend/src/stores/sessions.ts`
- 新增：`frontend/src/stores/knowledge.ts`
- 修改：`frontend/src/types/`
- 测试：`frontend/src/stores/sessions.spec.ts`
- 测试：`frontend/src/stores/knowledge.spec.ts`

- [ ] **步骤 1：先编写失败的前端测试**

  测试创建会话时发送 `mode`，会话列表保留模式标签，文档上传使用 multipart 表单，并且轮询会持续刷新 `PROCESSING` 文档，直到变为 `READY` 或 `FAILED`。

- [ ] **步骤 2：运行专项前端测试并确认失败**

  在 `frontend` 目录运行 `npm run test -- sessions knowledge`；此时新类型、API 和状态管理行为尚未实现，测试应失败。

- [ ] **步骤 3：增加类型化 API 和状态管理**

  增加 `KnowledgeDocument`、`KnowledgeDocumentStatus` 和 `ChatMode` 类型。增加文档列表/上传 API 和有上限的轮询动作。更新会话 Store 的创建/列表行为，持久化 `NORMAL`/`KNOWLEDGE`，并且不信任路由查询参数中的模式。

- [ ] **步骤 4：运行测试并提交**

  运行前端专项测试和现有前端测试集。提交信息使用 `feat: add knowledge frontend state`。

## 任务 10：实现已确认的前端交互样式

**涉及文件：**
- 修改：`frontend/src/views/ChatView.vue`
- 修改：`frontend/src/components/SessionSidebar.vue`
- 修改：`frontend/src/components/Composer.vue`
- 修改：`frontend/src/components/ChatTranscript.vue`
- 新增：`frontend/src/components/KnowledgePanel.vue`
- 修改：`frontend/src/style.css`
- 测试：`frontend/src/views/ChatView.spec.ts`
- 测试：`frontend/src/components/` 下的组件测试

- [ ] **步骤 1：先编写失败的界面测试**

  测试右侧有同级的 `Skill` 和 `知识库` 顶层页签；`知识库` 页签下只有一个名为 `知识库` 的子页签。测试新建会话流程可以选择 `普通对话` 或 `知识库问答`，左侧会话列表能够展示所选模式。测试知识库面板能够展示上传、列表、状态和错误状态。

- [ ] **步骤 2：运行界面测试并确认失败**

  运行相关 Vitest 文件；此时已确认的页签和模式交互尚未实现，测试应失败。

- [ ] **步骤 3：实现会话模式选择和标签展示**

  在新建会话流程中增加模式选择器。会话创建后模式不可修改。每个会话标题旁显示紧凑的模式标签：`普通对话` 或 `知识库问答`。在聊天头部和输入区显示当前模式，让用户无需依赖隐藏状态即可区分两种对话路径。

- [ ] **步骤 4：实现右侧知识库页签**

  保持 `Skill` 和 `知识库` 为同级顶层页签。在 `知识库` 下只渲染一个 `知识库` 子页签，包含上传控件、文档列表、处理状态、父子文档数量、重试/错误展示和处理中轮询。不要增加 `知识库对话` 子页签；知识库问答从模式为 `知识库问答` 的会话进入。

- [ ] **步骤 5：运行测试并提交**

  运行界面专项测试、完整前端测试集、Lint 和构建。提交信息使用 `feat: add knowledge base interaction ui`。

## 任务 11：端到端验证和交付说明

**涉及文件：**
- 修改：`README.md`
- 修改：`docker-compose.yml`（如冒烟测试配置需要修正）
- 新增：`backend/src/test/java/com/example/myagent/knowledge/KnowledgeRagIntegrationTest.java`（测试容器或基础设施可用时增加）

- [ ] **步骤 1：运行全部自动化检查**

  运行完整后端测试集、完整前端测试集、前端 Lint/构建以及现有 Docker Compose 安全测试。只修复由本功能引起的失败，不修改无关问题。

- [ ] **步骤 2：运行本地基础设施冒烟测试**

  使用 Docker Compose 启动 MySQL、Redis、Elasticsearch 和 Kafka。上传文本/PDF 样例，确认 API 返回 `PROCESSING`；消费 Kafka 任务，确认 ES 父子记录按用户隔离且文档变为 `READY`；然后提出知识库问题，确认回答包含检索来源。再提出无关问题，确认返回固定拒答。

- [ ] **步骤 3：验证用户隔离和失败清理**

  使用第二个用户重复检索，确认零命中。人为制造解析/向量化/ES 失败，确认部分 ES 记录被删除；进入 DLT 后 MySQL 状态变为 `FAILED`，并且原始源文件仍然保留。

- [ ] **步骤 4：补充运维和配置说明**

  更新 README，说明环境变量、Docker 启动方式、Topic/消费者组名称、模型配置、存储清理行为、重试/DLT 运维方式，以及索引向量和查询向量必须使用同一个向量模型和维度。

- [ ] **步骤 5：运行最终验证并提交**

  文档修改完成后重新运行全部检查。提交信息使用 `docs: document personal rag setup`。
