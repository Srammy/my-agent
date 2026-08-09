package com.example.myagent.config;

import java.util.Locale;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KnowledgeMultimodalChatModelConfiguration {

  private final Function<String, String> environmentVariableResolver;

  public KnowledgeMultimodalChatModelConfiguration() {
    this(System::getenv);
  }

  KnowledgeMultimodalChatModelConfiguration(Function<String, String> environmentVariableResolver) {
    this.environmentVariableResolver = environmentVariableResolver;
  }

  @Bean(name = "knowledgeMultimodalChatModel")
  ChatModel knowledgeMultimodalChatModel(KnowledgeProperties properties) {
    KnowledgeProperties.Multimodal multimodal = properties.multimodal();
    String apiKey = resolveRequiredSecret(multimodal.apiKeyEnv());
    if (!"dashscope".equalsIgnoreCase(multimodal.provider())) {
      throw new IllegalArgumentException(
          "Unsupported knowledge.multimodal.provider: " + multimodal.provider());
    }
    OpenAiApi api =
        OpenAiApi.builder()
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
            .apiKey(apiKey)
            .build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(OpenAiChatOptions.builder().model(multimodal.model()).build())
        .build();
  }

  private String resolveRequiredSecret(String secretEnvName) {
    String value = environmentVariableResolver.apply(secretEnvName);
    if (value != null && !value.isBlank()) {
      return value;
    }
    throw new IllegalStateException(
        "Missing required API key in environment variable: " + secretEnvName);
  }
}
