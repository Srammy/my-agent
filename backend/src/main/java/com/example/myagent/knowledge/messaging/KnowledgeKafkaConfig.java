package com.example.myagent.knowledge.messaging;

import com.example.myagent.config.KnowledgeProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableKafka
@EnableScheduling
public class KnowledgeKafkaConfig {

  @Bean
  NewTopic knowledgeDocumentProcessTopic(KnowledgeProperties properties) {
    return new NewTopic(properties.kafka().topic(), 1, (short) 1);
  }

  @Bean
  NewTopic knowledgeDocumentProcessDlt(KnowledgeProperties properties) {
    return new NewTopic(properties.kafka().topic() + ".DLT", 1, (short) 1);
  }
}
