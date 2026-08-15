# 知识库多模态模型配置隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让知识库 OCR 使用 `KNOWLEDGE_MULTIMODAL_MODEL` 指定的 Spring AI 多模态模型，避免回退到 `gpt-4o-mini`。

**Architecture:** 在知识库配置模块新增专用 `ChatModel` Bean，使用 `KnowledgeProperties.multimodal` 创建模型；OCR Reader 通过限定名称注入该 Bean，普通 Agent 模型保持不变。

**Tech Stack:** Java 21、Spring Boot、Spring AI 1.0、DashScope OpenAI-compatible API、JUnit 5、AssertJ。

## Global Constraints

- 多模态模型名称必须来自 `KNOWLEDGE_MULTIMODAL_MODEL`。
- 多模态 API Key 必须来自 `KNOWLEDGE_MULTIMODAL_API_KEY_ENV` 指定的环境变量。
- 不修改普通 Agent 的模型配置和运行逻辑。
- 不修改 OCR 提示词、Kafka ETL、文档切分和向量化逻辑。

---

### Task 1: 新增知识库专用多模态 ChatModel 装配

**Files:**
- Create: `backend/src/main/java/com/example/myagent/config/KnowledgeMultimodalChatModelConfiguration.java`
- Test: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeMultimodalChatModelConfigurationTest.java`

**Interfaces:**
- Consumes: `KnowledgeProperties.multimodal()`。
- Produces: 名称为 `knowledgeMultimodalChatModel` 的 `ChatModel` Bean。

- [ ] **Step 1: 写失败测试**

测试 Spring 配置在 `knowledge.multimodal.provider=dashscope`、模型为 `qwen-vl-test`、API Key 环境变量已设置时创建名为 `knowledgeMultimodalChatModel` 的 Bean，并验证 Bean 类型为 Spring AI OpenAI-compatible ChatModel。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -q -Dtest=KnowledgeMultimodalChatModelConfigurationTest test`

预期：因配置类和 Bean 尚不存在而失败。

- [ ] **Step 3: 实现最小配置**

使用 `OpenAiChatModel.builder()` 创建模型：DashScope provider 使用 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型名使用 `properties.multimodal().model()`，API Key 使用 `System.getenv(properties.multimodal().apiKeyEnv())`。对不支持的 provider 和空 API Key 抛出 `IllegalStateException`。

- [ ] **Step 4: 运行测试确认通过**

运行：`mvn -q -Dtest=KnowledgeMultimodalChatModelConfigurationTest test`

预期：PASS。

- [ ] **Step 5: 提交**

```text
git add backend/src/main/java/com/example/myagent/config/KnowledgeMultimodalChatModelConfiguration.java backend/src/test/java/com/example/myagent/knowledge/KnowledgeMultimodalChatModelConfigurationTest.java
git commit -m "feat: configure knowledge multimodal chat model"
```

### Task 2: 将 OCR Reader 绑定到专用模型

**Files:**
- Modify: `backend/src/main/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReader.java`
- Test: `backend/src/test/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReaderTest.java`（若现有测试结构允许，扩展现有 Reader 测试）

**Interfaces:**
- Consumes: `@Qualifier("knowledgeMultimodalChatModel") ChatModel`。
- Produces: OCR 调用使用知识库多模态模型。

- [ ] **Step 1: 写失败测试**

为 Reader 构造注入一个可识别的专用 `ChatModel`，调用图片抽取路径，验证调用的是该 ChatModel；测试不得依赖普通 Agent 的默认模型。

- [ ] **Step 2: 运行测试确认失败**

运行：`mvn -q -Dtest=SpringAiKnowledgeDocumentReaderTest test`

预期：当前构造器只接受默认类型注入，测试无法证明专用 Bean 被使用。

- [ ] **Step 3: 最小修改 Reader 构造器**

在 `ChatModel` 参数上增加 `@Qualifier("knowledgeMultimodalChatModel")`，不修改 `extractMultimodal` 的提示词和解析逻辑。

- [ ] **Step 4: 运行测试确认通过**

运行：`mvn -q -Dtest=SpringAiKnowledgeDocumentReaderTest test`

预期：PASS。

- [ ] **Step 5: 提交**

```text
git add backend/src/main/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReader.java backend/src/test/java/com/example/myagent/knowledge/etl/SpringAiKnowledgeDocumentReaderTest.java
git commit -m "fix: bind OCR to knowledge multimodal model"
```

### Task 3: 全量验证并重新启动服务

**Files:**
- Modify: `.env` 或 Docker Compose 使用的环境配置文件（仅在当前配置缺少 `KNOWLEDGE_MULTIMODAL_MODEL` 时补齐）

- [ ] **Step 1: 运行后端相关测试**

运行：`mvn -q test`

预期：全部测试通过。

- [ ] **Step 2: 检查环境配置**

确认容器环境中存在 `KNOWLEDGE_MULTIMODAL_MODEL`、`DASHSCOPE_API_KEY`，且模型值是当前账号实际可用的多模态模型。

- [ ] **Step 3: 使用新代码重建服务**

运行：`docker compose up -d --build backend frontend`

- [ ] **Step 4: 验证运行日志**

上传或重试包含图片/扫描页的文档，确认日志不再请求 `gpt-4o-mini`，而是请求 `KNOWLEDGE_MULTIMODAL_MODEL` 配置的模型。
