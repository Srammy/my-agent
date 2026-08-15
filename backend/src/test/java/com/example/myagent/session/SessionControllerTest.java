package com.example.myagent.session;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDateTime;
import java.util.List;
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
import reactor.core.publisher.Mono;

@WebFluxTest(SessionController.class)
@Import(SessionControllerTest.TestSecurityConfig.class)
class SessionControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");
  private static final LocalDateTime CREATED_AT = LocalDateTime.parse("2026-07-04T09:30:00");
  private static final LocalDateTime UPDATED_AT = LocalDateTime.parse("2026-07-04T09:45:00");

  @Autowired private WebTestClient webTestClient;

  @MockBean private SessionService sessionService;

  @Test
  void postSessionsCreatesSessionForAuthenticatedUserAndDoesNotExposeUserId() {
    ChatSessionEntity session =
        new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT);
    when(sessionService.createSession(USER, "Sprint planning")).thenReturn(session);

    authenticatedClient()
        .post()
        .uri("/api/chat/sessions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Sprint planning\",\"userId\":999}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo("s_123")
        .jsonPath("$.title")
        .isEqualTo("Sprint planning")
        .jsonPath("$.mode")
        .isEqualTo("NORMAL")
        .jsonPath("$.createdAt")
        .isEqualTo("2026-07-04T09:30:00")
        .jsonPath("$.updatedAt")
        .isEqualTo("2026-07-04T09:45:00")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(sessionService).createSession(eq(USER), eq("Sprint planning"));
  }

  @Test
  void postSessionsAcceptsKnowledgeModeWithoutAcceptingClientUserId() {
    ChatSessionEntity session =
        new ChatSessionEntity(
            "s_knowledge", USER.id(), "Knowledge", SessionMode.KNOWLEDGE, CREATED_AT, UPDATED_AT);
    when(sessionService.createSession(USER, "Knowledge", SessionMode.KNOWLEDGE)).thenReturn(session);

    authenticatedClient()
        .post()
        .uri("/api/chat/sessions")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Knowledge\",\"mode\":\"KNOWLEDGE\",\"userId\":999}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.mode")
        .isEqualTo("KNOWLEDGE")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(sessionService).createSession(eq(USER), eq("Knowledge"), eq(SessionMode.KNOWLEDGE));
  }

  @Test
  void getSessionsReturnsOnlyCurrentUsersDtosWithoutUserId() {
    when(sessionService.listSessions(USER))
        .thenReturn(
            List.of(
                new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT),
                new ChatSessionEntity("s_456", USER.id(), "Retro", CREATED_AT, UPDATED_AT)));

    authenticatedClient()
        .get()
        .uri("/api/chat/sessions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$[0].id")
        .isEqualTo("s_123")
        .jsonPath("$[0].title")
        .isEqualTo("Sprint planning")
        .jsonPath("$[0].userId")
        .doesNotExist()
        .jsonPath("$[1].id")
        .isEqualTo("s_456")
        .jsonPath("$[1].title")
        .isEqualTo("Retro")
        .jsonPath("$[1].userId")
        .doesNotExist();

    verify(sessionService).listSessions(USER);
  }

  @Test
  void getSessionMapsNotFoundTo404() {
    when(sessionService.requireOwnedSession(USER, "missing"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

    authenticatedClient()
        .get()
        .uri("/api/chat/sessions/missing")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(sessionService).requireOwnedSession(USER, "missing");
  }

  @Test
  void getSessionReturnsDtoShapeWithoutUserId() {
    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenReturn(new ChatSessionEntity("s_123", USER.id(), "Sprint planning", CREATED_AT, UPDATED_AT));

    authenticatedClient()
        .get()
        .uri("/api/chat/sessions/s_123")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo("s_123")
        .jsonPath("$.title")
        .isEqualTo("Sprint planning")
        .jsonPath("$.createdAt")
        .isEqualTo("2026-07-04T09:30:00")
        .jsonPath("$.updatedAt")
        .isEqualTo("2026-07-04T09:45:00")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(sessionService).requireOwnedSession(USER, "s_123");
  }

  @Test
  void deleteSessionMapsMissingTo404() {
    when(sessionService.deleteSession(USER, "missing"))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")));

    authenticatedClient()
        .delete()
        .uri("/api/chat/sessions/missing")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(sessionService).deleteSession(USER, "missing");
  }

  @Test
  void deleteSessionMapsCrossUserInvisibleTo404() {
    when(sessionService.deleteSession(USER, "other-users-session"))
        .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")));

    authenticatedClient()
        .delete()
        .uri("/api/chat/sessions/other-users-session")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(sessionService).deleteSession(USER, "other-users-session");
  }

  @Test
  void deleteSessionReturnsNoContentForCurrentContract() {
    when(sessionService.deleteSession(USER, "s_123")).thenReturn(Mono.empty());

    authenticatedClient()
        .delete()
        .uri("/api/chat/sessions/s_123")
        .exchange()
        .expectStatus()
        .isNoContent()
        .expectBody()
        .isEmpty();

    verify(sessionService).deleteSession(USER, "s_123");
  }

  @Test
  void putSessionRenamesCurrentUsersSessionAndReturnsDto() {
    ChatSessionEntity renamed =
        new ChatSessionEntity("s_123", USER.id(), "Renamed", CREATED_AT, UPDATED_AT.plusMinutes(1));
    when(sessionService.renameSession(USER, "s_123", "Renamed")).thenReturn(renamed);

    authenticatedClient()
        .put()
        .uri("/api/chat/sessions/s_123")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Renamed\",\"userId\":999}")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo("s_123")
        .jsonPath("$.title")
        .isEqualTo("Renamed")
        .jsonPath("$.userId")
        .doesNotExist();

    verify(sessionService).renameSession(USER, "s_123", "Renamed");
  }

  @Test
  void putSessionMapsMissingTo404() {
    when(sessionService.renameSession(USER, "missing", "Renamed"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

    authenticatedClient()
        .put()
        .uri("/api/chat/sessions/missing")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"title\":\"Renamed\"}")
        .exchange()
        .expectStatus()
        .isNotFound();

    verify(sessionService).renameSession(USER, "missing", "Renamed");
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
