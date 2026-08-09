package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.knowledge.document.KnowledgeDocumentEntity;
import com.example.myagent.knowledge.document.KnowledgeDocumentStatus;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobEntity;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobStatus;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentEntityTest {

  @Test
  void storesUserScopedDocumentAndJobState() {
    KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
    document.setId("doc-1");
    document.setUserId(7L);
    document.setStatus(KnowledgeDocumentStatus.PROCESSING);

    KnowledgeDocumentJobEntity job = new KnowledgeDocumentJobEntity();
    job.setDocumentId(document.getId());
    job.setUserId(document.getUserId());
    job.setStatus(KnowledgeDocumentJobStatus.PENDING);

    assertThat(document.getUserId()).isEqualTo(7L);
    assertThat(document.getStatus()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);
    assertThat(job.getDocumentId()).isEqualTo("doc-1");
    assertThat(job.getUserId()).isEqualTo(7L);
    assertThat(job.getStatus()).isEqualTo(KnowledgeDocumentJobStatus.PENDING);
  }
}
