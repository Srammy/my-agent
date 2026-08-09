package com.example.myagent.knowledge;

import com.example.myagent.knowledge.messaging.KnowledgeDocumentProcessMessage;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeDocumentKafkaConsumer {

  private final KnowledgeDocumentEtlProcessor processor;

  public KnowledgeDocumentKafkaConsumer(KnowledgeDocumentEtlProcessor processor) {
    this.processor = processor;
  }

  @RetryableTopic(
      attempts = "${knowledge.kafka.max-attempts:5}",
      autoCreateTopics = "true",
      dltTopicSuffix = ".DLT")
  @KafkaListener(
      topics = "${knowledge.kafka.topic}",
      groupId = "${knowledge.kafka.group}")
  public void consume(KnowledgeDocumentProcessMessage message) {
    processor.process(message);
  }

  @DltHandler
  public void consumeDeadLetter(
      KnowledgeDocumentProcessMessage message, Exception exception) {
    processor.markFailed(message, exception);
  }
}
