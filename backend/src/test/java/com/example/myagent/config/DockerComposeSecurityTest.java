package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerComposeSecurityTest {

  @Test
  void composeKeepsDatastoresPrivateAndRequiresCredentials() throws IOException {
    String compose = Files.readString(projectRoot().resolve("docker-compose.yml"));

    assertThat(compose)
        .doesNotContain("3306:3306")
        .doesNotContain("6379:6379")
        .doesNotContain(":-change-me")
        .contains("MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?")
        .contains("REDIS_PASSWORD: ${REDIS_PASSWORD:?")
        .contains("redis-server --requirepass")
        .contains("AGENT_STATE_STORE_REDIS_URI: redis://:${REDIS_PASSWORD:?");
  }

  @Test
  void exampleEnvironmentDoesNotProvideUsableSecrets() throws IOException {
    Path examplePath = projectRoot().resolve(".env.example");
    List<String> lines = Files.readAllLines(examplePath);
    String example = Files.readString(examplePath);

    assertThat(lines)
        .contains("MYSQL_ROOT_PASSWORD=", "MYSQL_PASSWORD=", "REDIS_PASSWORD=");
    assertThat(example).doesNotContain("change-me");
  }

  @Test
  void applicationConfigDoesNotProvideAMySqlPassword() throws IOException {
    String applicationConfig = Files.readString(
        projectRoot().resolve("backend/src/main/resources/application.yml"));

    assertThat(applicationConfig)
        .contains("password: ${MYSQL_PASSWORD:}")
        .doesNotContain("MYSQL_PASSWORD:change-me");
  }

  private static Path projectRoot() {
    Path workingDirectory = Path.of("").toAbsolutePath().normalize();
    return Files.exists(workingDirectory.resolve("docker-compose.yml"))
        ? workingDirectory
        : workingDirectory.getParent();
  }
}
