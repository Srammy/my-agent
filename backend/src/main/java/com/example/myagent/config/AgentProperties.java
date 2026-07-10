package com.example.myagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
    @DefaultValue AgentScope agentScope,
    @DefaultValue Workspace workspace,
    @DefaultValue Model model,
    @DefaultValue StateStore stateStore,
    @DefaultValue Skill skill,
    @DefaultValue Permission permission,
    @DefaultValue Tools tools) {

  public record AgentScope(@DefaultValue("false") boolean enabled) {}

  public record Workspace(@DefaultValue("./.agentscope/workspace") String path) {}

  public record Model(
      @DefaultValue("dashscope") String provider,
      @DefaultValue("dashscope:qwen-plus") String name,
      @DefaultValue("") String baseUrl,
      @DefaultValue("DASHSCOPE_API_KEY") String apiKeyEnv) {}

  public record StateStore(
      @DefaultValue("redis") String type, @DefaultValue Redis redis) {

    public record Redis(
        @DefaultValue("redis://localhost:6379") String uri,
        @DefaultValue("myagent:agent-state:") String keyPrefix) {}
  }

  public record Skill(
      @DefaultValue("agentscope") String storage,
      @DefaultValue("prod") String environment,
      @DefaultValue("10") int canaryPercent,
      @DefaultValue("true") boolean manageToolEnabled,
      @DefaultValue("true") boolean securityScanEnabled) {}

  public record Permission(@DefaultValue("DEFAULT") String defaultMode) {}

  public record Tools(
      @DefaultValue("false") boolean fileToolsEnabled,
      @DefaultValue("false") boolean shellEnabled,
      @DefaultValue("false") boolean httpFetchEnabled,
      @DefaultValue("false") boolean mcpEnabled) {}
}
