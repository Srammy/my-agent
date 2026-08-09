package com.example.myagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeElasticsearchConfiguration {

  @Bean(destroyMethod = "close")
  RestClient knowledgeRestClient(KnowledgeProperties properties) {
    var builder = RestClient.builder(HttpHost.create(properties.elasticsearch().url()));
    if (!properties.elasticsearch().username().isBlank()) {
      var credentialsProvider = new BasicCredentialsProvider();
      credentialsProvider.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(
              properties.elasticsearch().username(), properties.elasticsearch().password()));
      builder.setHttpClientConfigCallback(
          httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
    }
    return builder.build();
  }

  @Bean
  ElasticsearchClient knowledgeElasticsearchClient(RestClient restClient) {
    return new ElasticsearchClient(
        new RestClientTransport(restClient, new JacksonJsonpMapper()));
  }
}
