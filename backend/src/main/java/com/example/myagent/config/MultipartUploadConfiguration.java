package com.example.myagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.http.codec.multipart.MultipartHttpMessageReader;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class MultipartUploadConfiguration implements WebFluxConfigurer {

  private static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024;

  @Override
  public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
    configurer.defaultCodecs().configureDefaultCodec(codec -> {
      if (codec instanceof DefaultPartHttpMessageReader reader) {
        reader.setMaxDiskUsagePerPart(MAX_UPLOAD_BYTES);
      }
      if (codec instanceof MultipartHttpMessageReader reader
          && reader.getPartReader() instanceof DefaultPartHttpMessageReader partReader) {
        partReader.setMaxDiskUsagePerPart(MAX_UPLOAD_BYTES);
      }
    });
  }
}
