# Task 6 实现报告：Elasticsearch 映射、向量化和批量索引

## 完成内容

- 新增 Elasticsearch 8.x Java Client 配置，按 `knowledge.elasticsearch.*` 建立客户端。
- 创建父索引和子索引，子索引 `embedding` 显式使用配置维度、`dense_vector`、cosine 相似度。
- 父子索引均保存 `userId`、`documentId`、来源文件、内容类型、页码、状态等字段。
- 新增 `KnowledgeEmbeddingService`，统一使用 Spring AI `EmbeddingModel`，并严格校验返回向量维度。
- 新增 `KnowledgeIndexService`，只对 child chunk 向量化，按稳定的文档/父块/子块 ID 批量写入父子索引。
- 重建索引前按精确的 `userId + documentId` 删除父子记录，避免跨用户清理。

## 验证

```text
mvn -q -f backend/pom.xml -Dtest=KnowledgeIndexServiceTest,KnowledgeDocumentReaderTest,KnowledgeDocumentOutboxRelayTest,KnowledgeDocumentControllerTest,KnowledgeConfigurationTest,KnowledgeDocumentMapperTest,SessionServiceTest,SessionControllerTest test
mvn -q -f backend/pom.xml -DskipTests compile
git diff --check
```

结果：全部通过。
