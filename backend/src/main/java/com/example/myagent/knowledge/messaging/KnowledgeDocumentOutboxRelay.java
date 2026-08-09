package com.example.myagent.knowledge.messaging;

import com.example.myagent.config.KnowledgeProperties;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobEntity;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeDocumentOutboxRelay {

  private final KnowledgeDocumentJobMapper jobMapper;
  private final KafkaTemplate<String, KnowledgeDocumentProcessMessage> kafkaTemplate;
  private final KnowledgeProperties properties;
  private final int maxAttempts;

  public KnowledgeDocumentOutboxRelay(
      KnowledgeDocumentJobMapper jobMapper,
      KafkaTemplate<String, KnowledgeDocumentProcessMessage> kafkaTemplate,
      KnowledgeProperties properties,
      @Value("${knowledge.kafka.max-attempts:5}") int maxAttempts) {
    this.jobMapper = jobMapper;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
    this.maxAttempts = maxAttempts;
  }

  @Scheduled(fixedDelayString = "${knowledge.kafka.relay-delay-ms:1000}")
  public void relayPendingJobs() {
    List<KnowledgeDocumentJobEntity> jobs = jobMapper.findClaimable(LocalDateTime.now(), 20);
    for (KnowledgeDocumentJobEntity job : jobs) {
      String claimToken = UUID.randomUUID().toString();
      if (jobMapper.claim(job.getId(), job.getUserId(), claimToken, LocalDateTime.now().plusMinutes(2)) == 0) {
        continue;
      }
      send(job, claimToken);
    }
  }

  private void send(KnowledgeDocumentJobEntity job, String claimToken) {
    KnowledgeDocumentProcessMessage message =
        new KnowledgeDocumentProcessMessage(job.getDocumentId(), job.getUserId());
    try {
      CompletableFuture<SendResult<String, KnowledgeDocumentProcessMessage>> future =
          kafkaTemplate.send(properties.kafka().topic(), job.getDocumentId(), message);
      future.whenComplete(
          (result, error) -> {
            if (error == null) {
              jobMapper.markSent(job.getId(), job.getUserId(), claimToken, LocalDateTime.now());
            } else {
              jobMapper.markFailure(
                  job.getId(),
                  job.getUserId(),
                  claimToken,
                  job.getAttempts() + 1,
                  error.getMessage(),
                  job.getAttempts() + 1 >= maxAttempts,
                  LocalDateTime.now());
            }
          });
    } catch (RuntimeException error) {
      jobMapper.markFailure(
          job.getId(),
          job.getUserId(),
          claimToken,
          job.getAttempts() + 1,
          error.getMessage(),
          job.getAttempts() + 1 >= maxAttempts,
          LocalDateTime.now());
    }
  }
}
