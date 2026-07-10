package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.Test;
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

@WebFluxTest(SkillController.class)
@Import(SkillControllerTest.TestSecurityConfig.class)
class SkillControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final SkillDto SKILL = new SkillDto("java-helper", "Java helper");

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
        .isEqualTo("Java helper");

    verify(workspaceService).listSkills(USER);
  }

  private WebTestClient authenticatedClient() {
    return webTestClient.mutateWith(
        mockAuthentication(new TestingAuthenticationToken(USER, null, "ROLE_USER")));
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
