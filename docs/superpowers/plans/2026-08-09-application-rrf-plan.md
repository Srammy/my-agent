# 应用层 RRF 混合检索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 Elasticsearch 内置 `rank.rrf` 请求，由应用层合并 BM25 和 kNN 结果，实现不依赖许可证的标准 RRF 混合检索。

**Architecture:** `KnowledgeSearchService` 对子文档索引分别执行关键词检索和向量检索，各返回最多 `topK` 条结果。应用层按子文档 ID 使用 `1 / (60 + rank)` 合并分数并排序，再沿用现有父文档查询和用户隔离过滤返回知识库上下文。

**Tech Stack:** Java 21、Spring Boot 3.3、Elasticsearch Java API Client 8.x、JUnit 5、Mockito、AssertJ。

---

## 文件职责

- Modify: `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java`
  - 拆分 BM25 和 kNN 查询，并在应用层计算 RRF。
  - 保留父文档查询、用户隔离和 READY 过滤。
- Modify: `backend/src/test/java/com/example/myagent/knowledge/KnowledgeSearchServiceTest.java`
  - 验证两路召回、RRF 排序、空结果和过滤条件。
- No change: `docker-compose.yml`
  - 不启用 Elasticsearch Trial，不依赖许可证配置。

## Task 1: 编写失败测试

**Files:** `backend/src/test/java/com/example/myagent/knowledge/KnowledgeSearchServiceTest.java`

- [ ] **Step 1: 将原生 RRF 测试改为三次检索响应**

将测试名改为 `mergesKeywordAndVectorBranchesWithApplicationRrf`。按以下顺序 mock `client.search`：BM25 子文档返回 `child-1(parent-1)`、`child-2(parent-2)`；kNN 子文档返回 `child-2(parent-2)`、`child-3(parent-3)`；父文档查询返回三个父文档。

增加断言：

```java
verify(client, times(3)).search(requestCaptor.capture(), eq(Map.class));
assertThat(requestCaptor.getAllValues().get(0).rank()).isNull();
assertThat(requestCaptor.getAllValues().get(0).knn()).isEmpty();
assertThat(requestCaptor.getAllValues().get(1).rank()).isNull();
assertThat(requestCaptor.getAllValues().get(1).knn()).hasSize(1);
assertThat(results).extracting(KnowledgeSearchHit::parentId)
    .containsExactly("parent-2", "parent-1", "parent-3");
```

`parent-2` 同时出现在 BM25 第 2 位和向量第 1 位，因此必须排在只命中一路的父文档之前。

- [ ] **Step 2: 修改空结果测试**

让 BM25 和 kNN 两次查询都返回空 hits，断言结果为空且 `client.search` 只调用两次，不发起父文档查询。

- [ ] **Step 3: 运行 RED 测试**

运行 `mvn -q -Dtest=KnowledgeSearchServiceTest test`。预期测试失败，因为当前代码只发送一个带 Elasticsearch 原生 RRF 的子文档请求。

## Task 2: 实现应用层 RRF

**Files:** `backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java`

- [ ] **Step 1: 拆分请求构造方法**

使用以下包可见方法替换现有 `buildChildRequest`：

```java
SearchRequest buildKeywordRequest(
    Long userId, String question, int limit, Collection<String> documentIds)

SearchRequest buildVectorRequest(
    Long userId, float[] vector, int limit, Collection<String> documentIds)
```

关键词请求只包含 child index、`size(limit)`、`content` 的 `match` 和 ownership filter；向量请求只包含现有 embedding、`queryVector`、`k`、`numCandidates` 和 ownership filter。两个请求都不能调用 `.rank(...)`。

- [ ] **Step 2: 在 `search(...)` 中执行两路查询**

保留一次 `embeddingService.embed(question)`，然后依次调用：

```java
SearchResponse<Map> keywordResponse =
    client.search(buildKeywordRequest(userId, question, limit, documentIds), Map.class);
SearchResponse<Map> vectorResponse =
    client.search(buildVectorRequest(userId, vector, limit, documentIds), Map.class);
```

两路 hits 都为空时返回空列表；否则进入 RRF 合并和现有父文档查询。

- [ ] **Step 3: 添加 RRF 合并方法**

增加包可见方法：

```java
List<Hit<Map>> mergeChildHits(
    List<Hit<Map>> keywordHits, List<Hit<Map>> vectorHits, int limit)
```

按子文档 `Hit.id()` 合并，两个列表的排名从 1 开始，每一路贡献 `1.0 / (60.0 + rank)`。保存原始 hit，按总分降序、首次出现顺序稳定排序，最多返回 `limit` 个结果。核心逻辑必须等价于：

```java
scores.merge(childId, 1.0 / (60.0 + rank), Double::sum);
```

- [ ] **Step 4: 接回现有父文档流程**

用 `mergeChildHits(...)` 的结果替代原先的 `childResponse.hits().hits()`。继续按 `parentId` 去重，调用现有 `buildParentRequest(...)`，并保留 `userId`、`status=READY` 和可选 `documentId` 过滤。

- [ ] **Step 5: 运行 GREEN 测试**

运行 `mvn -q -Dtest=KnowledgeSearchServiceTest test`。预期全部通过，并确认捕获的请求没有 `rank.rrf`。

## Task 3: 回归验证和提交

- [ ] **Step 1: 运行相关后端测试**

运行 `mvn -q -Dtest=KnowledgeSearchServiceTest,KnowledgeChatServiceTest,ChatServiceTest test`，预期全部通过。

- [ ] **Step 2: 构建后端镜像**

运行 `docker compose build backend`，预期构建成功且不需要 Elasticsearch 许可证配置。

- [ ] **Step 3: 检查差异**

运行 `git diff --check`，并确认生产代码不再包含 `rank.rrf` 或 `.rank(`，ownership filter 仍存在。

- [ ] **Step 4: 提交实现**

运行：

```powershell
git add backend/src/main/java/com/example/myagent/knowledge/search/KnowledgeSearchService.java backend/src/test/java/com/example/myagent/knowledge/KnowledgeSearchServiceTest.java
git commit -m "fix: implement application-level rrf search"
```
