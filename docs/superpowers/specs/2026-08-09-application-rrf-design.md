# 应用层 RRF 混合检索设计

## 目标

移除 Elasticsearch 内置 RRF 依赖，由后端应用代码合并 BM25 关键词检索和 kNN 向量检索结果，实现标准 Reciprocal Rank Fusion（RRF），避免 Elasticsearch 许可证限制。

## 非目标

- 不更换 Elasticsearch 8.x。
- 不改变父子文档结构、用户隔离规则或向量模型。
- 不引入新的检索中间件。
- 不在应用层实现全文检索或向量索引，关键词和向量召回仍由 Elasticsearch 完成。

## 方案

`KnowledgeSearchService` 对子文档索引执行两次查询：

1. BM25 查询：对 `content` 执行 `match`，同时应用 `userId`、`status=READY` 和可选 `documentId` 过滤。
2. kNN 查询：对 `embedding` 执行向量检索，使用同样的所有权过滤。

两次查询各返回最多 `topK` 条结果。应用层按子文档 ID 合并结果，并使用固定的 RRF 常数 `k=60`：

```text
rrfScore(doc) = Σ 1 / (60 + rank_i(doc))
```

其中每一路结果的排名从 1 开始；文档未出现在某一路时，该路不贡献分数。合并后按 RRF 分数降序排列，保留子文档携带的 `parentId`，再按父文档 ID 查询父文档，最终返回父文档内容作为知识库上下文。

## 数据流

```text
用户问题
  -> 生成查询向量
  -> Elasticsearch BM25 子文档检索
  -> Elasticsearch kNN 子文档检索
  -> 应用层 RRF 合并子文档排名
  -> 按 userId/status/documentId 过滤父文档
  -> 返回知识库上下文
  -> Agent 生成回答
```

## 边界与错误处理

- 两路检索都为空时，返回空上下文，由现有知识库问答流程按“文档中没有检索到时不回答”处理。
- 只有一路检索成功时，使用该路结果计算 RRF，不因另一条结果为空而报错。
- Elasticsearch、Embedding 或查询异常继续向上抛出，由现有聊天流错误处理统一处理。
- 所有查询都必须保留 `userId` 和 `READY` 过滤，防止跨用户或未完成文档进入上下文。
- 不再发送 Elasticsearch `rank.rrf` 请求，也不依赖 Trial、Platinum 或其他许可证。

## 测试

新增或调整后端单元测试，覆盖：

- BM25 和 kNN 两路结果的 RRF 分数计算。
- 同一子文档出现在两路结果时分数累加。
- 仅出现在一路时仍能参与排序。
- RRF 排序结果稳定且按分数降序排列。
- 构造的 Elasticsearch 查询不包含内置 `rank.rrf`。
- 用户、状态和文档过滤条件仍存在。

## 验收标准

- Basic 许可证 Elasticsearch 可以执行知识库问答，不再出现 `non-compliant for Reciprocal Rank Fusion`。
- 关键词命中、向量命中和混合命中均能返回正确父文档上下文。
- 用户只能检索自己的 `READY` 文档。
- 现有后端测试和新增 RRF 测试通过。
