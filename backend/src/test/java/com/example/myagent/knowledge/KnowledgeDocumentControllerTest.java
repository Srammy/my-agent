package com.example.myagent.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.knowledge.document.KnowledgeDocumentController;
import com.example.myagent.knowledge.document.KnowledgeDocumentDto;
import com.example.myagent.knowledge.document.KnowledgeDocumentService;
import com.example.myagent.knowledge.document.KnowledgeDocumentStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Mono;

@WebFluxTest(KnowledgeDocumentController.class)
@Import(KnowledgeDocumentControllerTest.TestSecurityConfig.class)
class KnowledgeDocumentControllerTest {

  private static final CurrentUser USER = new CurrentUser(7L, "alice", "USER");

  @Autowired private WebTestClient webTestClient;

  @MockBean private KnowledgeDocumentService service;

  @Test
  void uploadReturnsAcceptedProcessingDocumentWithoutStorageKey() {
    KnowledgeDocumentDto dto =
        new KnowledgeDocumentDto(
            "doc-1", "notes.txt", "text/plain", 5L, KnowledgeDocumentStatus.PROCESSING, 0, 0, null);
    when(service.upload(any(CurrentUser.class), any())).thenReturn(Mono.just(dto));

    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("file", new ByteArrayResource("hello".getBytes()) {
      @Override
      public String getFilename() {
        return "notes.txt";
      }
    });

    authenticatedClient()
        .post()
        .uri("/api/knowledge/documents")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus()
        .isAccepted()
        .expectBody()
        .jsonPath("$.id")
        .isEqualTo("doc-1")
        .jsonPath("$.status")
        .isEqualTo("PROCESSING")
        .jsonPath("$.storageKey")
        .doesNotExist();

    verify(service).upload(any(CurrentUser.class), any());
  }

  @Test
  void listReturnsOnlyServiceResultsForTheAuthenticatedUser() {
    KnowledgeDocumentDto dto =
        new KnowledgeDocumentDto(
            "doc-1", "notes.txt", "text/plain", 5L, KnowledgeDocumentStatus.READY, 1, 2, null);
    when(service.list(USER)).thenReturn(List.of(dto));

    authenticatedClient()
        .get()
        .uri("/api/knowledge/documents")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].id")
        .isEqualTo("doc-1")
        .jsonPath("$[0].status")
        .isEqualTo("READY")
        .jsonPath("$[0].storageKey")
        .doesNotExist();

    verify(service).list(USER);
  }

  private WebTestClient authenticatedClient() {
    return webTestClient.mutateWith(
        mockAuthentication(new TestingAuthenticationToken(USER, "password", "ROLE_USER")));
  }

  @TestConfiguration
  static class TestSecurityConfig {
    @Bean
    SecurityWebFilterChain testSecurityWebFilterChain(ServerHttpSecurity http) {
      return http.csrf(ServerHttpSecurity.CsrfSpec::disable).authorizeExchange(
              exchanges -> exchanges.anyExchange().authenticated())
          .build();
    }
  }
}
