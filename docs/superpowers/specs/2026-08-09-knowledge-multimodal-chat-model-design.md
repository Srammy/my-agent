# 知识库多模态模型配置隔离设计

## 目标

让知识库 OCR、图片内容理解和表格抽取使用环境变量 `KNOWLEDGE_MULTIMODAL_MODEL` 指定的模型，不再注入或回退到普通 Agent 使用的默认 `ChatModel`。

## 方案

新增知识库专用的 Spring AI `ChatModel` Bean，由 `KnowledgeProperties.multimodal` 提供 provider、model 和 API Key 环境变量配置。`SpringAiKnowledgeDocumentReader` 通过限定名称注入该 Bean，普通 Agent 的模型装配保持不变。

当前 DashScope 配置使用 OpenAI 兼容协议：provider 为 `dashscope` 时，使用 DashScope 兼容接口地址、`KNOWLEDGE_MULTIMODAL_MODEL` 作为模型名，以及 `KNOWLEDGE_MULTIMODAL_API_KEY_ENV` 指定的密钥环境变量。未支持的 provider 或缺少密钥时，启动阶段抛出明确异常。

## 数据流

```text
KNOWLEDGE_MULTIMODAL_MODEL
        ↓
KnowledgeProperties.multimodal
        ↓
KnowledgeMultimodalChatModelConfiguration
        ↓
SpringAiKnowledgeDocumentReader
        ↓
OCR / 图片理解 / 表格抽取
```

## 测试与验收

- 配置绑定测试验证 `KNOWLEDGE_MULTIMODAL_MODEL` 对应的模型名被读取。
- 模型装配测试验证知识库专用 `ChatModel` 使用配置模型和 API Key 环境变量。
- 运行后端测试并重新构建容器。
- 查看 ETL 日志，确认 OCR 请求不再出现默认模型 `gpt-4o-mini`。

## 不在本次范围内

- 不修改普通 Agent 的模型选择逻辑。
- 不新增前端配置入口。
- 不改变 OCR 提示词、文档切分、向量化或 Kafka 重试策略。
