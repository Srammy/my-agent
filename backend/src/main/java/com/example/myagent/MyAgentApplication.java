package com.example.myagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyAgentApplication {

  public static void main(String[] args) {
    SpringApplication.run(MyAgentApplication.class, args);
  }
}
