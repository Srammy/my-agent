# Task 8 实现报告：Elasticsearch RRF 检索与知识库 Agent 对话

## 完成内容

- 新增 `KnowledgeSearchService`，使用同一 EmbeddingModel 生成查询向量。
- Elasticsearch 子索引检索同时提交 BM25 `query.match` 和 `knn` 向量分支，并使用原生 `rank.rrf` 合并。
- BM25、knn 以及父文档回查均按 `userId`、`status=READY` 和可选 documentId 过滤。
- 子块结果按 parentId 去重后回查父文档，返回来源文件、页码和父文档上下文。
- 普通对话保持原有 ChatAgentGateway 链路。
- 知识库会话先检索，再将命中的父文档上下文注入同一个普通 Agent；命中与否不改变 Agent 能力。
- 零命中直接返回“未在知识库中找到相关内容，无法回答”，不调用 Agent。

## 验证

```text
mvn -q -f backend/pom.xml -Dtest=KnowledgeChatServiceTest,ChatServiceTest,KnowledgeSearchServiceTest test
git diff --check
```

结果：通过。
