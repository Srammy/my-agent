package com.example.myagent.chat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.permission.PermissionMode;
import com.example.myagent.permission.PermissionService;
import com.example.myagent.session.ChatSessionEntity;
import com.example.myagent.session.SessionService;
import com.example.myagent.skill.SkillMaterializer;
import java.time.LocalDateTime;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

@WebFluxTest(ChatController.class)
@Import({ChatService.class, StubChatAgentGateway.class, ChatControllerTest.TestSecurityConfig.class})
class ChatControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-04T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-04T09:45:00");

  @Autowired private WebTestClient webTestClient;

  @MockBean private SessionService sessionService;
  @MockBean private SkillMaterializer skillMaterializer;
  @MockBean private PermissionService permissionService;

  @Test
  void postStreamRejectsUnknownFields() {
    authenticatedClient()
        .post()
        .uri("/api/chat/sessions/s_123/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.parseMediaType("application/x-ndjson"))
        .bodyValue("{\"message\":\"hello\",\"userId\":999}")
        .exchange()
        .expectStatus()
        .isBadRequest();

    verify(sessionService, never()).requireOwnedSession(USER, "s_123");
  }

  @Test
  void postStreamRejectsArbitraryUnknownFields() {
    authenticatedClient()
        .post()
        .uri("/api/chat/sessions/s_123/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.parseMediaType("application/x-ndjson"))
        .bodyValue("{\"message\":\"hello\",\"traceId\":\"abc\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();

    verify(sessionService, never()).requireOwnedSession(USER, "s_123");
  }

  @Test
  void postStreamReturnsNdjsonEventsForOwnedSession() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));
    when(skillMaterializer.materializeForUser(USER.id()))
        .thenReturn(Path.of("/tmp/materialized-skills"));
    when(permissionService.getModeForOwnedSession("s_123")).thenReturn(PermissionMode.DEFAULT);

    authenticatedClient()
        .post()
        .uri("/api/chat/sessions/s_123/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.parseMediaType("application/x-ndjson"))
        .bodyValue("{\"message\":\"你好\"}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.parseMediaType("application/x-ndjson"))
        .expectBody(String.class)
        .isEqualTo(
            """
            {"type":"reply_start"}
            {"type":"text_delta","delta":"你好"}
            {"type":"done"}
            """);

    verify(sessionService).requireOwnedSession(USER, "s_123");
  }

  @Test
  void postStreamReturns404ForCrossUserSession() {
    when(sessionService.requireOwnedSession(USER, "s_other"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

    authenticatedClient()
        .post()
        .uri("/api/chat/sessions/s_other/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"message\":\"hi\"}")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(sessionService).requireOwnedSession(USER, "s_other");
  }

  private WebTestClient authenticatedClient() {
    return webTestClient.mutateWith(
        mockAuthentication(new TestingAuthenticationToken(USER, null, "ROLE_USER")));
  }

  @TestConfiguration
  static class TestSecurityConfig {

    @org.springframework.context.annotation.Bean
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
