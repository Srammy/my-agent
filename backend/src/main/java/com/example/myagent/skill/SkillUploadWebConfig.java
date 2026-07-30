package com.example.myagent.skill;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class SkillUploadWebConfig implements WebFluxConfigurer {

  @Override
  public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
    configurer.defaultCodecs().configureDefaultCodec(codec -> {
      if (codec instanceof DefaultPartHttpMessageReader partReader) {
        partReader.setMaxInMemorySize(AgentScopeWorkspaceService.MAX_IN_MEMORY_SIZE);
        partReader.setMaxDiskUsagePerPart(AgentScopeWorkspaceService.MAX_FILE_SIZE);
        partReader.setMaxParts(AgentScopeWorkspaceService.MAX_FILE_COUNT + 1);
      }
    });
  }
}
