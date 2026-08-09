# Task 5 实现报告：Spring AI 文档解析与父子切分

## 完成内容

- 新增 `KnowledgeDocumentReader` 解析接口及 Spring AI 实现。
- Markdown 使用 `MarkdownDocumentReader`，其他文本、PDF、Office 文档使用 `TikaDocumentReader`。
- 图片使用 Spring AI `Media` + `UserMessage` + `ChatModel`，要求模型返回 OCR、图片描述和表格 JSON。
- 表格抽取结果转换为 Markdown，进入统一文本切分流程。
- 按父文档约 1600 token、子文档约 400 token、子文档重叠 60 token 切分。
- 父子文档生成稳定 ID，并在父、子文档元数据中写入 `userId`、`documentId`、源文件名、内容类型和切片序号。
- 解析异常统一包装为 `KnowledgeDocumentEtlException`，供后续 Kafka 消费失败处理。

## 验证

执行：

```text
mvn -q -f backend/pom.xml -Dtest=KnowledgeDocumentReaderTest test
git diff --check
```

结果：通过。

## 说明

Markdown reader 会将标题作为 reader 元数据处理，正文切片使用其正文输出。PDF/Office 的页级元数据保留能力将在后续 ETL 消费与索引任务中继续接入。
