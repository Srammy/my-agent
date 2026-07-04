package com.example.myagent.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyBatisPlusConfig {

  @Bean
  MybatisPlusInterceptor mybatisPlusInterceptor() {
    return new MybatisPlusInterceptor();
  }
}
