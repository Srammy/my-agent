package com.example.myagent.knowledge.etl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpringAiKnowledgeDocumentReaderJsonTest {

  @Test
  void removesMarkdownJsonFenceBeforeParsing() {
    assertThat(SpringAiKnowledgeDocumentReader.normalizeJson("```json\n{\"ocrText\":\"内容\"}\n```"))
        .isEqualTo("{\"ocrText\":\"内容\"}");
  }
}
