package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobEntity;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobMapper;
import com.example.myagent.knowledge.messaging.KnowledgeDocumentOutboxRelay;
import com.example.myagent.knowledge.messaging.KnowledgeDocumentProcessMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KnowledgeDocumentOutboxRelayTest {

  private static final KnowledgeProperties PROPERTIES =
      new KnowledgeProperties(
          new KnowledgeProperties.Embedding("dashscope", "text-embedding-v4", 1024, "KEY"),
          new KnowledgeProperties.Multimodal("dashscope", "qwen3.7-plus", "KEY"),
          new KnowledgeProperties.Elasticsearch(
              "http://es", "elastic", "", "parents", "children"),
          new KnowledgeProperties.Kafka(
              "myagent.knowledge.document.process", "myagent-knowledge-etl", "kafka:9092"),
          new KnowledgeProperties.Storage("./storage"));

  @Test
  void marksPendingJobSentOnlyAfterKafkaSuccess() {
    KnowledgeDocumentJobMapper mapper = mock(KnowledgeDocumentJobMapper.class);
    KafkaTemplate<String, KnowledgeDocumentProcessMessage> template = mock(KafkaTemplate.class);
    KnowledgeDocumentJobEntity job = pendingJob();
    whenClaimable(mapper, job);
    doReturn(CompletableFuture.completedFuture((SendResult<String, KnowledgeDocumentProcessMessage>) null))
        .when(template)
        .send(eq(PROPERTIES.kafka().topic()), eq("doc-1"), any(KnowledgeDocumentProcessMessage.class));

    KnowledgeDocumentOutboxRelay relay = new KnowledgeDocumentOutboxRelay(mapper, template, PROPERTIES, 5);
    relay.relayPendingJobs();

    ArgumentCaptor<KnowledgeDocumentProcessMessage> message =
        ArgumentCaptor.forClass(KnowledgeDocumentProcessMessage.class);
    verify(template).send(eq(PROPERTIES.kafka().topic()), eq("doc-1"), message.capture());
    verify(mapper).markSent(eq("job-1"), eq(7L), any(String.class), any(LocalDateTime.class));
    assertThat(message.getValue().documentId()).isEqualTo("doc-1");
    assertThat(message.getValue().userId()).isEqualTo(7L);
  }

  @Test
  void recordsRetryableFailureWhenKafkaSendFails() {
    KnowledgeDocumentJobMapper mapper = mock(KnowledgeDocumentJobMapper.class);
    KafkaTemplate<String, KnowledgeDocumentProcessMessage> template = mock(KafkaTemplate.class);
    KnowledgeDocumentJobEntity job = pendingJob();
    whenClaimable(mapper, job);
    doReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")))
        .when(template)
        .send(eq(PROPERTIES.kafka().topic()), eq("doc-1"), any(KnowledgeDocumentProcessMessage.class));

    KnowledgeDocumentOutboxRelay relay = new KnowledgeDocumentOutboxRelay(mapper, template, PROPERTIES, 5);
    relay.relayPendingJobs();

    verify(mapper)
        .markFailure(
            eq("job-1"),
            eq(7L),
            any(String.class),
            eq(1),
            eq("kafka down"),
            eq(false),
            any(LocalDateTime.class));
  }

  private static KnowledgeDocumentJobEntity pendingJob() {
    KnowledgeDocumentJobEntity job = new KnowledgeDocumentJobEntity();
    job.setId("job-1");
    job.setDocumentId("doc-1");
    job.setUserId(7L);
    job.setAttempts(0);
    return job;
  }

  private static void whenClaimable(KnowledgeDocumentJobMapper mapper, KnowledgeDocumentJobEntity job) {
    doReturn(List.of(job)).when(mapper).findClaimable(any(LocalDateTime.class), eq(20));
    doReturn(1)
        .when(mapper)
        .claim(eq("job-1"), eq(7L), any(String.class), any(LocalDateTime.class));
  }
}
