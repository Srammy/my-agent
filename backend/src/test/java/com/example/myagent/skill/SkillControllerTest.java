package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

@WebFluxTest(SkillController.class)
@Import({SkillControllerTest.TestSecurityConfig.class, SkillUploadWebConfig.class})
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

  @Test
  void postMineAcceptsMultipartAndCreatesSkill() {
    when(workspaceService.createSkill(eq(USER), any()))
        .thenReturn(new SkillDto("java-helper", "Java helper"));

    byte[] skillMdBytes =
        "---\nname: java-helper\ndescription: Java helper\n---\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("SKILL.md", new ByteArrayResource(skillMdBytes) {
      @Override
      public String getFilename() {
        return "SKILL.md";
      }
    });

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.name").isEqualTo("java-helper")
        .jsonPath("$.description").isEqualTo("Java helper");

    verify(workspaceService).createSkill(eq(USER), any());
  }

  @Test
  void postMineRejectsMoreThan32MultipartFiles() {
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    for (int index = 0; index < 33; index++) {
      int fileIndex = index;
      parts.add("file-" + index, new ByteArrayResource(new byte[0]) {
        @Override
        public String getFilename() {
          return "assets/file-" + fileIndex + ".txt";
        }
      });
    }

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isEqualTo(413);

    verifyNoInteractions(workspaceService);
  }

  @Test
  void postMineAccepts32MultipartFilesAndOneFormField() {
    when(workspaceService.createSkill(eq(USER), any())).thenReturn(SKILL);

    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    for (int index = 0; index < 32; index++) {
      int fileIndex = index;
      parts.add("file-" + index, new ByteArrayResource(new byte[0]) {
        @Override
        public String getFilename() {
          return "assets/file-" + fileIndex + ".txt";
        }
      });
    }
    parts.add("description", "ordinary form field");

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.name").isEqualTo("java-helper")
        .jsonPath("$.description").isEqualTo("Java helper");

    verify(workspaceService).createSkill(eq(USER), any());
  }

  @Test
  void postMineRejects33rdFileAfter32FilesAndFormField() {
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    for (int index = 0; index < 32; index++) {
      int fileIndex = index;
      parts.add("file-" + index, new ByteArrayResource(new byte[0]) {
        @Override
        public String getFilename() {
          return "assets/file-" + fileIndex + ".txt";
        }
      });
    }
    parts.add("description", "ordinary form field");
    parts.add("file-32", new ByteArrayResource(new byte[0]) {
      @Override
      public String getFilename() {
        return "assets/file-32.txt";
      }
    });

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isEqualTo(413);

    verifyNoInteractions(workspaceService);
  }

  @Test
  void postMineRejectsMultipartFileLargerThanOneMebibyte() {
    LinkedMultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
    parts.add("large", new ByteArrayResource(new byte[1024 * 1024 + 1]) {
      @Override
      public String getFilename() {
        return "assets/large.txt";
      }
    });

    authenticatedClient()
        .post()
        .uri("/api/skills/mine")
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(BodyInserters.fromMultipartData(parts))
        .exchange()
        .expectStatus().isEqualTo(413);

    verifyNoInteractions(workspaceService);
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
