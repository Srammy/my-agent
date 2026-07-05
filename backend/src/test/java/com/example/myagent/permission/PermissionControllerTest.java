package com.example.myagent.permission;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

@WebFluxTest(PermissionController.class)
@Import(PermissionControllerTest.TestSecurityConfig.class)
class PermissionControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");

  @Autowired private WebTestClient webTestClient;

  @MockBean private PermissionService permissionService;

  @Test
  void getPermissionModeReturnsModeDto() {
    when(permissionService.getMode(USER, "s_123"))
        .thenReturn(new PermissionModeDto(PermissionMode.DEFAULT));

    authenticatedClient()
        .get()
        .uri("/api/chat/sessions/s_123/permission-mode")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.mode")
        .isEqualTo("DEFAULT")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(permissionService).getMode(USER, "s_123");
  }

  @Test
  void putPermissionModePersistsValidMode() {
    when(permissionService.setMode(USER, "s_123", new PermissionModeDto(PermissionMode.ACCEPT_EDITS)))
        .thenReturn(new PermissionModeDto(PermissionMode.ACCEPT_EDITS));

    authenticatedClient()
        .put()
        .uri("/api/chat/sessions/s_123/permission-mode")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"mode\":\"ACCEPT_EDITS\"}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.mode")
        .isEqualTo("ACCEPT_EDITS");

    verify(permissionService).setMode(USER, "s_123", new PermissionModeDto(PermissionMode.ACCEPT_EDITS));
  }

  @Test
  void putPermissionModeRejectsInvalidMode() {
    authenticatedClient()
        .put()
        .uri("/api/chat/sessions/s_123/permission-mode")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"mode\":\"ROOT\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void getPermissionModeMapsOtherUsersSessionTo404() {
    doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"))
        .when(permissionService)
        .getMode(USER, "other");

    authenticatedClient()
        .get()
        .uri("/api/chat/sessions/other/permission-mode")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(permissionService).getMode(USER, "other");
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
