package com.example.myagent.memory;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDate;
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

@WebFluxTest(MemoryController.class)
@Import(MemoryControllerTest.TestSecurityConfig.class)
class MemoryControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");

  @Autowired private WebTestClient webTestClient;

  @MockBean private MemoryService memoryService;

  @Test
  void getSummaryReturnsContentWithoutUserId() {
    when(memoryService.getSummary(USER)).thenReturn("Alice summary");

    authenticatedClient()
        .get()
        .uri("/api/memory/summary")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.content")
        .isEqualTo("Alice summary")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(memoryService).getSummary(USER);
  }

  @Test
  void listDailyReturnsContentsWithoutUserId() {
    when(memoryService.listDaily(USER)).thenReturn(List.of("day one", "day two"));

    authenticatedClient()
        .get()
        .uri("/api/memory/daily")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.items[0]")
        .isEqualTo("day one")
        .jsonPath("$.items[1]")
        .isEqualTo("day two")
        .jsonPath("$.userId")
        .doesNotExist();
  }

  @Test
  void getDailyReturnsDateAndContent() {
    LocalDate date = LocalDate.parse("2026-07-05");
    when(memoryService.getDaily(USER, date)).thenReturn("today");

    authenticatedClient()
        .get()
        .uri("/api/memory/daily/2026-07-05")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.date")
        .isEqualTo("2026-07-05")
        .jsonPath("$.content")
        .isEqualTo("today");

    verify(memoryService).getDaily(USER, date);
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
