package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

@WebFluxTest(SkillController.class)
@Import(SkillControllerTest.TestSecurityConfig.class)
class SkillControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final SkillDto SKILL =
      new SkillDto("java-helper", "Java helper", true, "2026-07-08T10:00:00");

  @Autowired private WebTestClient webTestClient;

  @MockBean private AgentScopeWorkspaceService workspaceService;

  @Test
  void getMineReturnsWorkspaceSkills() {
    when(workspaceService.listSkills(USER)).thenReturn(List.of(SKILL));

    authenticatedClient()
        .get()
        .uri("/api/skills/mine")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$[0].name")
        .isEqualTo("java-helper")
        .jsonPath("$[0].description")
        .isEqualTo("Java helper")
        .jsonPath("$[0].editable")
        .isEqualTo(true);

    verify(workspaceService).listSkills(USER);
  }

  @Test
  void putFileAcceptsNestedSkillPath() {
    SkillFileDto file =
        new SkillFileDto(
            "references/guides/setup.md",
            "hello",
            "text/markdown",
            false,
            "2026-07-08T10:00:00");
    when(workspaceService.upsertFile(USER, "java-helper", "references/guides/setup.md", "hello"))
        .thenReturn(file);

    authenticatedClient()
        .put()
        .uri("/api/skills/java-helper/files/references/guides/setup.md")
        .contentType(MediaType.TEXT_PLAIN)
        .bodyValue("hello")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.path")
        .isEqualTo("references/guides/setup.md");

    verify(workspaceService)
        .upsertFile(USER, "java-helper", "references/guides/setup.md", "hello");
  }

  @Test
  void listMineOffloadsBlockingServiceCallToBoundedElasticThread() {
    SkillController controller = new SkillController(workspaceService);
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    when(workspaceService.listSkills(USER))
        .thenAnswer(
            (Answer<List<SkillDto>>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return List.of(SKILL);
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.listMine(USER))
                  .expectNext(List.of(SKILL))
                  .verifyComplete();
            },
            "skill-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("skill-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
  }

  private WebTestClient authenticatedClient() {
    return webTestClient.mutateWith(
        mockAuthentication(new TestingAuthenticationToken(USER, null, "ROLE_USER")));
  }

  private static void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for test thread", exception);
    }
  }

  @TestConfiguration
  static class TestSecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
      return http
          .csrf(ServerHttpSecurity.CsrfSpec::disable)
          .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
          .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
          .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
          .build();
    }
  }
}
