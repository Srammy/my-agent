# Task 7 实现报告：Kafka ETL 消费与失败清理

## 完成内容

- 新增 Kafka 消费者，使用 `@RetryableTopic` 重试，耗尽后由 `@DltHandler` 处理 DLT。
- 消费时按消息中的 `userId + documentId` 查询数据库，并再次校验持久化所有权。
- ETL 顺序为：清理本用户本文件旧索引 → 读取源文件 → Spring AI 解析/父子切分 → 向量化并写入 ES → 更新 MySQL 为 READY → 删除源文件。
- READY 状态具备幂等短路；父子计数写入数据库。
- 解析、模型、索引或状态更新失败会清理精确 `userId + documentId` 的 ES 数据并抛出可重试异常。
- DLT 处理会清理精确 ES 数据、写入 FAILED 和截断后的错误信息，并保留源文件供人工重试。
- Task 6 索引重建改为先完成向量化，再删除旧数据；父/子批量写入任一失败时进行精确回滚；delete-by-query 检查超时、版本冲突和失败项。

## 验证

```text
mvn -q -f backend/pom.xml -Dtest=KnowledgeIndexServiceTest,KnowledgeDocumentEtlProcessorTest,KnowledgeDocumentReaderTest test
mvn -q -f backend/pom.xml -DskipTests compile
git diff --check
```

结果：通过。
