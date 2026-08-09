# Personal RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 MyAgent 中实现按用户隔离的个人知识库：用户上传文档后通过 Kafka 异步完成解析、OCR/图片理解、表格结构化抽取、父子文档切分、向量化和 Elasticsearch 8.x 双路检索；知识库问答复用普通 Agent，但只有检索到用户自己的文档内容时才允许调用 Agent。

**Architecture:** 上传事务写入 MySQL 文档记录和 Outbox 消息，Relay 将消息投递到 Kafka；ETL Consumer 使用 Spring AI 解析和切分文档，必要时使用多模态模型完成 OCR、图片理解和表格抽取，使用配置的 embedding 模型向量化 child chunks，最后幂等写入 Elasticsearch parent/child 两个索引。问答请求使用当前用户和文档状态过滤，在 Elasticsearch 中用 BM25 `standard` 检索与 `knn` 检索组成原生 RRF；无命中时直接返回固定拒答，不调用 Agent。

**Tech Stack:** Java 21, Spring Boot 3.3.5（先按当前版本验证 Spring AI 兼容性）, Spring AI, Spring Kafka, MySQL/MyBatis-Plus, Elasticsearch 8.x Java API Client, Kafka, Docker Compose, Vue 3, TypeScript, Vitest.

---

## Task 1: Add RAG dependencies, configuration, and local infrastructure

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/java/com/example/myagent/config/` (add configuration classes beside existing config)
- Modify: `backend/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Test: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeConfigurationTest.java`

- [ ] **Step 1: Write the failing configuration test**

  Add a Spring context test that binds `knowledge.embedding.model`, `knowledge.embedding.dimensions`, `knowledge.multimodal.model`, and the Kafka/Elasticsearch endpoints into a `KnowledgeProperties` bean. Assert that the embedding dimension is `1024` and that model names come from properties rather than hard-coded service constants.

- [ ] **Step 2: Run the focused test to verify it fails**

  Run `mvn -q -Dtest=KnowledgeConfigurationTest test` from `backend`. It must fail because the properties class and bindings do not exist yet.

- [ ] **Step 3: Add dependencies and configuration properties**

  Add the Spring AI BOM/modules required for document readers, model clients, and structured output, plus Spring Kafka and the Elasticsearch 8.x Java API client. Keep the current Spring Boot parent first; if dependency resolution or compilation shows that the selected Spring AI release is incompatible with Boot 3.3.5, make the smallest documented parent/version adjustment in this task and rerun the existing backend test suite.

  Add a `@ConfigurationProperties(prefix = "knowledge")` record with concrete nested values:

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

  Bind the default model selection as `text-embedding-v4` with dimension `1024` and `qwen3.7-plus` for multimodal extraction. Keep ordinary Agent chat configuration separate (`AGENT_MODEL_NAME=qwen-plus`). Read secrets from environment variables, never from source files.

- [ ] **Step 4: Add Docker services and health checks**

  Add persistent, private-network services for Elasticsearch 8.x and Kafka in KRaft single-node development mode. Use named volumes, do not expose either service to the host, set Kafka replication factors to `1` for local development, and add health checks. Keep MySQL/Redis behavior unchanged.

- [ ] **Step 5: Run tests and commit**

  Run `mvn -q -Dtest=KnowledgeConfigurationTest test` and then the existing backend tests. Commit with `feat: configure rag infrastructure`.

## Task 2: Add user-scoped document/session persistence

**Files:**
- Add: `backend/src/main/resources/db/migration/V5__knowledge_documents.sql`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentEntity.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentMapper.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/job/KnowledgeDocumentJobEntity.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/job/KnowledgeDocumentJobMapper.java`
- Modify: `backend/src/main/java/com/example/myagent/session/ChatSessionEntity.java`
- Modify: `backend/src/main/java/com/example/myagent/session/SessionService.java`
- Modify: `backend/src/main/java/com/example/myagent/session/ChatSessionDto.java`
- Test: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentMapperTest.java`
- Test: existing session tests under `backend/src/test/java/com/example/myagent/session/`

- [ ] **Step 1: Write failing persistence tests**

  Test that document reads require both `id` and the authenticated `userId`, job rows are unique by `document_id`, and new session DTOs expose a fixed `mode` (`NORMAL` or `KNOWLEDGE`). Verify legacy sessions are treated as `NORMAL`.

- [ ] **Step 2: Run focused tests and observe the failure**

  Run the knowledge persistence tests and session tests. They must fail because the migration/entities/mode field are absent.

- [ ] **Step 3: Add the Flyway migration**

  Create `knowledge_documents` with `id`, `user_id`, filename/content type/size, `storage_key`, `status`, parent/child counts, error message, and timestamps. Create `knowledge_document_jobs` with a unique `document_id`, `user_id`, `status`, attempts, last error, and timestamps. Add `chat_sessions.mode` with default `NORMAL` and a check constraint for `NORMAL`/`KNOWLEDGE`. Add indexes beginning with `user_id` for all user-owned records.

- [ ] **Step 4: Add entities, mappers, and mode propagation**

  Add status/mode enums and MyBatis-Plus entities/mappers. Update session create/list DTOs and service methods so mode is persisted and returned. For every document/job query, require the user ID obtained from the existing authentication context; do not accept a client-supplied user ID as authorization.

- [ ] **Step 5: Run tests and commit**

  Run the focused tests and the full existing session test set. Commit with `feat: persist user scoped knowledge documents`.

## Task 3: Implement upload, listing, and durable source storage

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentController.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentService.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentDto.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentStorage.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/document/KnowledgeDocumentJobService.java`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentControllerTest.java`

- [ ] **Step 1: Write failing API tests**

  Test `POST /api/knowledge/documents` with a multipart file returns `202`, creates a `PROCESSING` document and one `PENDING` job, and returns no internal storage path. Test `GET /api/knowledge/documents` only returns the authenticated user’s documents. Test an empty/unsupported upload returns a validation error without creating a job.

- [ ] **Step 2: Run the API tests and verify failure**

  Run `mvn -q -Dtest=KnowledgeDocumentControllerTest test`; the endpoints should not exist yet.

- [ ] **Step 3: Implement safe storage and the upload transaction**

  Store the original file under `<root>/<userId>/<documentId>/source/<sanitized filename>`, where the path is constructed from validated IDs and a generated server-side document ID. In one transaction insert the document and Outbox job; write the file before commit and delete the newly written file if the transaction fails. Return a document DTO with `PROCESSING` status and counts set to zero.

- [ ] **Step 4: Implement user-scoped listing**

  Add a paged or bounded list endpoint ordered by creation time descending. Map statuses `PROCESSING`, `READY`, and `FAILED`, include error text only for the owner, and never return the source storage key.

- [ ] **Step 5: Run tests and commit**

  Run the focused controller tests plus the existing web/security tests. Commit with `feat: add asynchronous knowledge document upload`.

## Task 4: Implement Kafka Outbox relay, retry, and DLT

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeDocumentProcessMessage.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeDocumentOutboxRelay.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/messaging/KnowledgeKafkaConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentOutboxRelayTest.java`

- [ ] **Step 1: Write failing relay tests**

  Test that a `PENDING` job publishes a message keyed by `documentId` containing only `documentId` and `userId`, then becomes `SENT` only after the Kafka send succeeds. Test that a send failure leaves the job retryable and records the error.

- [ ] **Step 2: Run the relay tests and verify failure**

  Run `mvn -q -Dtest=KnowledgeDocumentOutboxRelayTest test`; the relay and producer configuration should be missing.

- [ ] **Step 3: Add topic and producer/consumer settings**

  Configure topic `myagent.knowledge.document.process`, consumer group `myagent-knowledge-etl`, JSON serialization, manual acknowledgment, bounded concurrency, retry backoff, and a DLT. Use `documentId` as the Kafka key to preserve per-document ordering.

- [ ] **Step 4: Implement the transactional relay**

  Poll pending jobs in small batches, publish with `KafkaTemplate`, and update status only in the success callback. Use row locking or an atomic claim state so multiple application instances cannot publish the same job concurrently. A duplicate message must remain safe because the ETL pipeline is idempotent.

- [ ] **Step 5: Run tests and commit**

  Run the focused tests and Kafka configuration tests. Commit with `feat: publish knowledge etl jobs through kafka`.

## Task 5: Implement Spring AI parsing, OCR, image understanding, and table extraction

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentReader.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/MultimodalExtraction.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/TableExtraction.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentContent.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReader.java`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentReaderTest.java`

- [ ] **Step 1: Write failing reader tests**

  Test that a digital text/PDF fixture produces page-aware content, parent metadata, and normalized text. Test that a scan/image fixture sends image media to the configured multimodal model and stores OCR plus image description. Test that a table fixture produces both Markdown text and validated `TableExtraction` JSON with headers, rows, page, and confidence.

- [ ] **Step 2: Run reader tests and verify failure**

  Run `mvn -q -Dtest=KnowledgeDocumentReaderTest test`; the Spring AI reader and extraction schema should not exist yet.

- [ ] **Step 3: Implement text extraction with Spring AI readers**

  Select readers by content type, using Spring AI’s Tika/page/text readers where appropriate. Preserve page number, source filename, content type, and logical parent index in metadata. Normalize whitespace without flattening table rows.

- [ ] **Step 4: Implement multimodal extraction**

  Build a Spring AI multimodal `UserMessage` with text instructions and `Media` for scanned pages/images. Request structured output using `ChatModel` and schema validation. `MultimodalExtraction` must contain OCR text, image description, and table results; table results must serialize to Markdown for retrieval and structured JSON for display/audit. A vision failure throws a retryable ETL exception and does not mark the document ready.

- [ ] **Step 5: Implement parent-child splitting**

  Split structure-first into parent documents of about 1500–2000 tokens, then child chunks of about 300–500 tokens with 50–80 token overlap. Keep document/page/content type metadata on both levels, preserve table headers in every table child, and attach `parentId` to each child.

- [ ] **Step 6: Run tests and commit**

  Run reader/splitting tests and the full backend test suite. Commit with `feat: parse knowledge documents with spring ai`.

## Task 6: Add Elasticsearch mappings, embedding, and idempotent indexing

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeElasticsearchIndexManager.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeEmbeddingService.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeIndexService.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeParentDocument.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeChildDocument.java`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeIndexServiceTest.java`

- [ ] **Step 1: Write failing indexing tests**

  Test that a child chunk is embedded with the configured model and exactly `1024` dimensions, and that parent/child records carry the same authenticated `userId`, document ID, and status. Test that indexing the same document twice produces deterministic IDs and does not duplicate records.

- [ ] **Step 2: Run indexing tests and verify failure**

  Run `mvn -q -Dtest=KnowledgeIndexServiceTest test`; the ES mappings and embedding service should be absent.

- [ ] **Step 3: Create the two Elasticsearch indices**

  Create `myagent_knowledge_parents` and `myagent_knowledge_children` with explicit mappings. The child index has `content` as text, exact metadata fields as keyword/integer fields, and `embedding` as `dense_vector` with `dims: 1024` and cosine similarity. Use `parentId` linkage rather than an Elasticsearch join field.

- [ ] **Step 4: Implement embedding and bulk writes**

  Inject Spring AI’s `EmbeddingModel`, use the configured model for both document and query embeddings, and fail fast if the returned dimension is not `1024`. Generate deterministic IDs from `documentId + parentIndex/childIndex`, bulk index all records, and delete old records by `userId + documentId` before rebuilding. Do not write any record under a client-provided user ID.

- [ ] **Step 5: Run tests and commit**

  Run indexing tests and the full backend suite. Commit with `feat: index rag parent child documents in elasticsearch`.

## Task 7: Implement Kafka ETL consumer and failure cleanup

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentEtlProcessor.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentKafkaConsumer.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/etl/KnowledgeDocumentCleanupService.java`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeDocumentEtlProcessorTest.java`

- [ ] **Step 1: Write failing processor tests**

  Test successful processing transitions the MySQL document to `READY`, records parent/child counts, deletes Elasticsearch data from any previous attempt before indexing, and deletes the original source file. Test parsing/indexing failure deletes all ES parent/child records for the exact `userId + documentId`, transitions to `FAILED` after retry exhaustion, and retains the original source file.

- [ ] **Step 2: Run the processor tests and verify failure**

  Run `mvn -q -Dtest=KnowledgeDocumentEtlProcessorTest test`; the consumer/orchestration should be absent.

- [ ] **Step 3: Implement the processor**

  Load the document by message `documentId` and persisted `userId`, verify ownership, load the source from the server-side storage key, parse/split/extract, embed, and bulk index. Update MySQL status only after the complete ES write succeeds. The source is deleted only after the READY update succeeds.

- [ ] **Step 4: Implement retry and DLT cleanup**

  Throw retryable exceptions from the listener for transient model, Kafka, or ES failures. On every failed attempt clean partial ES data before retrying; on DLT mark the document `FAILED`, persist a concise error, leave the source for reprocessing, and make cleanup idempotent. Never delete another document or user’s data.

- [ ] **Step 5: Run tests and commit**

  Run focused ETL tests and all backend tests. Commit with `feat: process rag documents asynchronously`.

## Task 8: Implement Elasticsearch RRF retrieval and Agent-backed knowledge chat

**Files:**
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java`
- Add: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchHit.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatService.java`
- Modify: `backend/src/main/java/com/example/myagent/chat/ChatController.java`
- Add/modify chat DTOs under `backend/src/main/java/com/example/myagent/chat/`
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeSearchServiceTest.java`
- Add/modify chat tests under `backend/src/test/java/com/example/myagent/chat/`

- [ ] **Step 1: Write failing retrieval/chat tests**

  Test the ES request contains a BM25 `standard` retriever and a vector `knn` retriever combined by native RRF, with filters for the current `userId` and `READY` status. Test a knowledge session with hits passes only retrieved parent context to the ordinary Agent gateway. Test zero hits returns the fixed no-answer message and does not call the Agent gateway.

- [ ] **Step 2: Run focused tests and verify failure**

  Run the retrieval and chat tests; the RRF service and knowledge mode branch should be absent.

- [ ] **Step 3: Implement native ES RRF retrieval**

  Embed the query with the same configured embedding model, build a `standard` retriever against child `content`, build a `knn` retriever against child `embedding`, and combine them with Elasticsearch 8.x native RRF. Apply `userId`, `documentId` scope if supplied, and `status = READY` to both branches. Collapse or deduplicate by parent ID, fetch parent records by `userId + parentId`, and return source filename/page/chunk metadata.

- [ ] **Step 4: Route knowledge sessions through the ordinary Agent**

  Add a fixed session mode to chat handling. `NORMAL` keeps the existing path. `KNOWLEDGE` searches first; if hits exist, construct a grounded prompt containing only retrieved context and source metadata, then invoke the same ordinary Agent gateway and stream the response. If no hits exist, return `未在知识库中找到相关内容。` without invoking the Agent.

- [ ] **Step 5: Run tests and commit**

  Run retrieval/chat tests and the complete backend test suite. Commit with `feat: add rrf grounded knowledge chat`.

## Task 9: Add frontend document APIs, session mode, and polling state

**Files:**
- Modify: `frontend/src/api/chat.ts`
- Add: `frontend/src/api/knowledge.ts`
- Modify: `frontend/src/stores/sessions.ts`
- Add: `frontend/src/stores/knowledge.ts`
- Modify: `frontend/src/types/`
- Test: `frontend/src/stores/sessions.spec.ts`
- Test: `frontend/src/stores/knowledge.spec.ts`

- [ ] **Step 1: Write failing frontend tests**

  Test session creation sends `mode`, session list retains the mode label, document upload uses multipart form data, and polling refreshes a `PROCESSING` document until `READY` or `FAILED`.

- [ ] **Step 2: Run focused frontend tests and verify failure**

  Run `npm run test -- sessions knowledge` from `frontend`; the new types, API, and store behavior should be missing.

- [ ] **Step 3: Add typed APIs and stores**

  Add `KnowledgeDocument`, `KnowledgeDocumentStatus`, and `ChatMode` types. Add document list/upload APIs and a bounded polling action. Update session store create/list behavior to persist `NORMAL`/`KNOWLEDGE` and avoid trusting mode from route query parameters.

- [ ] **Step 4: Run tests and commit**

  Run the focused frontend tests and existing frontend test suite. Commit with `feat: add knowledge frontend state`.

## Task 10: Implement the agreed UI interaction

**Files:**
- Modify: `frontend/src/views/ChatView.vue`
- Modify: `frontend/src/components/SessionSidebar.vue`
- Modify: `frontend/src/components/Composer.vue`
- Modify: `frontend/src/components/ChatTranscript.vue`
- Add: `frontend/src/components/KnowledgePanel.vue`
- Modify: `frontend/src/style.css`
- Test: `frontend/src/views/ChatView.spec.ts`
- Test: component tests under `frontend/src/components/`

- [ ] **Step 1: Write failing UI tests**

  Test the right side has top-level tabs `Skill` and `知识库`; the `知识库` tab has exactly one child tab named `知识库`. Test the new-session flow lets the user choose `普通对话` or `知识库问答`, and the left session list displays the selected mode. Test the knowledge panel shows upload/list/status/error states.

- [ ] **Step 2: Run UI tests and verify failure**

  Run the focused Vitest files; the agreed tab and mode interactions should not exist yet.

- [ ] **Step 3: Implement session mode selection and labels**

  Add a mode selector to new-session creation. Keep mode immutable after creation. Render a compact mode badge/text beside every session title: `普通对话` or `知识库问答`. Make the selected mode visible in the chat header and composer so the user can distinguish the two paths without relying on hidden state.

- [ ] **Step 4: Implement the right-side knowledge tab**

  Keep `Skill` and `知识库` at the same top level. Under `知识库`, render only the `知识库` child tab, containing upload control, document list, status, counts, retry/error display, and processing polling. Do not add a `知识库对话` child tab; knowledge Q&A starts from a session whose mode is `知识库问答`.

- [ ] **Step 5: Run tests and commit**

  Run the focused UI tests, full frontend test suite, lint, and build. Commit with `feat: add knowledge base interaction ui`.

## Task 11: End-to-end verification and handoff

**Files:**
- Modify: `README.md`
- Modify: `docker-compose.yml` if smoke-test configuration needs correction
- Add: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeRagIntegrationTest.java` when test containers/infrastructure are available

- [ ] **Step 1: Run all automated checks**

  Run the complete backend test suite, complete frontend test suite, frontend lint/build, and the existing Docker Compose security test. Fix only failures caused by this feature.

- [ ] **Step 2: Run the local infrastructure smoke test**

  Start MySQL, Redis, Elasticsearch, and Kafka with Docker Compose. Upload a text/PDF fixture, verify the API returns `PROCESSING`, consume the Kafka job, verify ES parent/child records are user-scoped and the document becomes `READY`, then ask a knowledge question and verify the response contains retrieved sources. Ask an unrelated question and verify the fixed no-answer response.

- [ ] **Step 3: Verify isolation and failure cleanup**

  Repeat retrieval as a second user and confirm zero hits. Force a parser/embedding/ES failure and confirm partial ES records are removed, MySQL becomes `FAILED` after DLT, and the original source remains available.

- [ ] **Step 4: Document operations and configuration**

  Update README with environment variables, Docker startup, topic/group names, model configuration, storage cleanup behavior, retry/DLT operations, and the fact that the same embedding model/dimension must be used for indexing and query vectors.

- [ ] **Step 5: Run final verification and commit**

  Re-run all checks after documentation changes and commit with `docs: document personal rag setup`.
