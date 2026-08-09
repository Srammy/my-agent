# 个人知识库 RAG 设计

## 目标

在现有 MyAgent 中增加按用户隔离的个人知识库：用户上传文档后，系统使用 Spring AI 读取和切分文档，生成父子文档并写入 Elasticsearch 8.x；知识库问答使用 Elasticsearch 的关键词检索和向量检索，经原生 RRF 融合后，将命中的父文档上下文交给现有普通 Agent 生成回答。没有检索到可用内容时不调用 Agent 生成自由回答。

## 已确认的产品行为

- 左侧会话列表展示会话名称和模式：`普通对话` 或 `知识库问答`。
- 会话创建时选择模式，创建后模式固定；切换会话时同步切换回答策略。
- 右侧顶层保留 `Skill` 和 `知识库` 两个 tab。
- `知识库` 下只保留一个 `知识库` 子 tab，用于上传文档和查看文档处理状态。
- 知识库问答仍使用普通 Agent 能力，但只能使用检索得到的个人文档上下文。
- 知识库问答未检索到内容时显示固定提示，不让 Agent 自由回答。
- 文档、父文档、子文档、检索和会话都必须按当前登录用户隔离。

## 技术方案

### 文档处理

使用 Spring AI 的 ETL 文档模型：

1. 上传接口接收 PDF、DOCX、TXT 和 Markdown 文件，并为上传生成后端文档 ID。
2. 使用 `TikaDocumentReader` 将文件读取为 Spring AI `Document`，保留来源文件名等元数据。
3. 将读取结果按逻辑上下文组装为父文档。一个父文档约 1,500～2,000 个 token，边界优先在标题、段落和页面边界处确定。
4. 对每个父文档使用 Spring AI `TokenTextSplitter` 生成子文档，子文档约 300～500 个 token，重叠约 50～80 个 token；长段落在句子边界处切分。
5. 为每个父文档和子文档写入以下元数据：`userId`、`documentId`、`parentId`、`chunkIndex`、`sourceFilename`、`status`。
6. 使用 Spring AI `EmbeddingModel` 对子文档批量生成向量，只将子文档写入检索索引；父文档保留完整上下文，供命中后回取。

父子关系使用应用层 `parentId` 逻辑关联，不使用 Elasticsearch `join` 字段。这样 RRF 只在子文档索引上执行，命中后根据 `parentId` 批量读取父文档，检索和隔离逻辑更容易测试。

### Elasticsearch 索引

Docker Compose 增加 Elasticsearch 8.x 服务和持久化 volume，不将 Elasticsearch 端口暴露给宿主机。后端通过 Compose 网络访问它。

使用两个索引：

`myagent_knowledge_parents`

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `parentId` | `keyword` | 父文档 ID |
| `documentId` | `keyword` | 关联 MySQL 文档记录 |
| `userId` | `keyword` | 强制用户过滤 |
| `status` | `keyword` | 只允许检索 `READY` 父文档 |
| `content` | `text` | 交给 Agent 的完整父上下文 |
| `sourceFilename` | `keyword` | 来源文件名 |
| `parentIndex` | `integer` | 父文档顺序 |

`myagent_knowledge_children`

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `childId` | `keyword` | 子文档 ID |
| `parentId` | `keyword` | 回取父文档 |
| `documentId` | `keyword` | 文档归属 |
| `userId` | `keyword` | 强制用户过滤 |
| `status` | `keyword` | 只允许检索 `READY` 子文档 |
| `content` | `text` | BM25 关键词检索字段 |
| `embedding` | `dense_vector` | 向量检索字段 |
| `sourceFilename` | `keyword` | UI 展示来源 |
| `chunkIndex` | `integer` | 子片段顺序 |

两个索引都在初始化时创建明确 mapping；向量维度来自 EmbeddingModel 配置，不能在运行时对已存在索引静默变更。

### 混合检索和 RRF

知识库问答收到问题后：

1. 校验会话归属和会话模式必须为 `KNOWLEDGE`。
2. 使用同一个 `EmbeddingModel` 将问题向量化。
3. 对子文档索引发起 Elasticsearch 原生 `_search` 请求，使用 `rrf` retriever 合并两个子检索器：
   - `standard`：对 `content` 执行 BM25 `multi_match`。
   - `knn`：对 `embedding` 执行近邻检索。
4. 在 RRF retriever 的共享过滤条件中加入 `userId = 当前用户`，并只允许检索已完成文档的子文档。
5. 使用 `rank_window_size` 和 `rank_constant` 配置 RRF；默认分别为 50 和 60，最终取前 5 个子文档。
6. 如果没有检索结果，直接返回“未在知识库中找到相关内容”，不调用 Agent。
7. 如果有结果，按 `parentId` 去重并限制父文档数量，再从父文档索引批量获取完整上下文。
8. 将父文档上下文和原始问题交给现有 `ChatAgentGateway`，系统提示明确要求只根据上下文回答；上下文不能支持答案时仍返回固定的未找到提示。
9. 通过聊天事件向前端发送来源文件名、父文档 ID 和片段摘要；普通对话不产生知识库来源事件。

RRF 只负责融合排序，不承担用户权限判断。权限判断由每个检索器共享的 `userId` 过滤和后端当前用户上下文共同保证。

### 用户隔离

用户 ID 只从 `@AuthenticationPrincipal CurrentUser` 取得，禁止从上传表单、URL 或前端 JSON 中接收作为权限依据。

- MySQL 文档列表使用 `WHERE user_id = currentUser.id()`。
- 上传创建的文档记录写入当前用户 ID。
- 父、子文档的 Elasticsearch metadata 写入当前用户 ID。
- RRF 的 `standard` 和 `knn` 子检索器都继承同一个 `userId` filter。
- 父文档回取使用 `userId + parentId` 双重条件。
- 文档状态更新只允许当前用户更新自己的文档记录。
- 测试必须覆盖用户 A 无法看到、检索或回取用户 B 的文档。

## 后端数据模型和接口

### MySQL 迁移

新增 `knowledge_documents` 表：

- `id varchar(64) primary key`
- `user_id bigint not null`
- `original_filename varchar(255) not null`
- `content_type varchar(128) not null`
- `size_bytes bigint not null`
- `status varchar(32) not null`，取值 `PROCESSING`、`READY`、`FAILED`
- `parent_count int not null default 0`
- `child_count int not null default 0`
- `error_message varchar(500)`
- `created_at datetime not null`
- `updated_at datetime not null`

索引为 `(user_id, created_at)` 和 `(user_id, status)`。文档二进制只在处理期间保存到由后端生成的用户隔离临时目录，处理结束后删除；本期列表不提供原文件下载。

新增 `chat_sessions.mode varchar(32) not null`，取值 `NORMAL`、`KNOWLEDGE`。已有会话迁移为 `NORMAL`。

### HTTP 接口

- `POST /api/knowledge/documents`：multipart 上传单个文档，返回文档元数据和处理状态。
- `GET /api/knowledge/documents`：只返回当前用户的文档列表，按更新时间倒序。
- `POST /api/chat/sessions`：请求新增可选 `mode`；缺省为 `NORMAL`。
- `GET /api/chat/sessions`：返回 `mode`，供左侧列表展示标签。
- `POST /api/chat/sessions/{sessionId}/stream`：沿用现有流式接口；服务端根据会话模式选择普通 Agent 或知识库检索链路。

上传处理在一次请求内完成，避免引入本期不需要的任务队列。处理过程中数据库状态为 `PROCESSING`，成功变为 `READY`；解析、嵌入或 Elasticsearch 写入失败则变为 `FAILED` 并保留可读错误信息。失败时不留下可检索的半成品子文档。

## 前端设计

- 扩展会话类型，增加 `mode`。
- 新建会话时提供 `普通对话` 和 `知识库问答` 两个选项。
- 左侧 `SessionSidebar` 在标题下显示模式标签，并在当前会话变化时同步聊天模式。
- 右侧 Assistant panel 的顶层 tabs 为 `Skill` 和 `知识库`；知识库只渲染一个文档管理子页面。
- 文档管理页面提供上传按钮、文件名、状态、片段数量和失败原因。
- 知识库问答消息显示检索来源；普通对话保持现有消息渲染。
- 无命中时显示固定提示，不显示“普通 Agent”内部状态徽章。

## 错误处理

- 未认证请求沿用现有安全配置返回 401。
- 跨用户文档或会话访问统一返回 404，不泄露资源是否存在。
- 不支持的文件类型返回 415。
- 超过单文件大小限制返回 413；本期限制为 20 MB。
- EmbeddingModel 不可用、Elasticsearch 不可用或解析失败时，文档变为 `FAILED`，API 返回明确错误；不写入可检索的半成品数据。
- 知识库会话无命中时返回正常聊天流中的固定文本事件和 `done` 事件，不伪装成模型生成内容。

## 测试和验收标准

### 后端

- Spring AI 父文档组装和子文档切分测试：标题、段落、重叠、超长段落和 metadata 继承。
- 文档服务测试：上传成功计数、失败状态和临时文件清理。
- Elasticsearch 检索请求测试：同时包含 `standard`、`knn`、RRF 和 `userId` filter。
- 用户隔离测试：不同用户不能列表、检索或回取彼此的文档。
- 知识库 ChatService 测试：无命中不调用 Agent；有命中时把父文档上下文传给 Agent；普通模式不触发检索。
- 会话模式迁移、创建、列表和归属测试。
- Docker Compose 测试：Elasticsearch 服务、认证配置和持久化 volume 存在，Elasticsearch 不发布宿主机端口。

### 前端

- 会话 store 保存并展示 `mode`。
- 新建会话模式选择请求正确传递。
- 左侧列表分别显示“普通对话”和“知识库问答”。
- 知识库 tab 能加载文档列表并显示 `PROCESSING`、`READY`、`FAILED`。
- 知识库来源事件正确渲染，普通对话不显示来源区域。

### 验收场景

1. 用户 A 上传 PDF，列表出现文档，状态最终为 `READY`，父子片段数量大于 0。
2. 用户 A 创建知识库问答会话，询问文档中存在的问题，回答来自父文档上下文并显示来源。
3. 用户 A 询问文档中不存在的问题，系统返回“未在知识库中找到相关内容”，不调用 Agent 自由回答。
4. 用户 B 登录后看不到用户 A 的文档，也检索不到用户 A 的内容。
5. 普通对话会话不访问 Elasticsearch 知识库检索，行为保持现有 Agent 流程。

## 非目标

- 本期不做原文件下载、在线预览、文档删除和重新索引按钮。
- 本期不引入异步任务队列；上传处理为单请求同步流程。
- 本期不做 OCR、图片内容理解和表格结构化抽取。
- 本期不调整现有普通 Agent 的工具确认和权限机制。
