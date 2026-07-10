package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AgentScopeWorkspaceServiceTest {

  private static final CurrentUser ALICE = new CurrentUser(1L, "alice", "USER");
  private static final CurrentUser BOB   = new CurrentUser(2L, "bob",   "USER");

  private AgentScopeWorkspaceService service;

  @BeforeEach
  void setUp() {
    AbstractFilesystem filesystem =
        new RemoteFilesystem(new InMemoryStore(), IsolationScope.USER.toNamespaceFactory());
    service = new AgentScopeWorkspaceService(filesystem);
  }

  @Test
  void listSkillsReturnsEmptyForNewUser() {
    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void createSkillAppearsInList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    List<SkillDto> skills = service.listSkills(ALICE);
    assertThat(skills).hasSize(1);
    assertThat(skills.get(0).name()).isEqualTo("java-helper");
    assertThat(skills.get(0).description()).isEqualTo("Java helper");
  }

  @Test
  void usersAreIsolated() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThat(service.listSkills(BOB)).isEmpty();
  }

  @Test
  void deleteSkillRemovesFromList() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));
    service.deleteSkill(ALICE, "java-helper");

    assertThat(service.listSkills(ALICE)).isEmpty();
  }

  @Test
  void deleteNonExistentSkillThrows404() {
    assertThatThrownBy(() -> service.deleteSkill(ALICE, "missing"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void createSkillRejectsMissingSkillMd() {
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createSkillRejectsInvalidSkillMd() {
    FilePart badPart = fakeFilePart("SKILL.md", "---\ndescription: no name here\n---\n");
    assertThatThrownBy(() -> service.createSkill(ALICE, List.of(badPart)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createDuplicateSkillThrows409() {
    service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper")));

    assertThatThrownBy(
            () -> service.createSkill(ALICE, List.of(skillMdPart("java-helper", "Java helper"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT));
  }

  private static FilePart skillMdPart(String name, String description) {
    String content = "---\nname: " + name + "\ndescription: " + description + "\n---\n";
    return fakeFilePart("SKILL.md", content);
  }

  private static FilePart fakeFilePart(String filename, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return new FilePart() {
      @Override
      public String name() {
        return filename;
      }

      @Override
      public HttpHeaders headers() {
        return new HttpHeaders();
      }

      @Override
      public Flux<DataBuffer> content() {
        DataBuffer buf = new DefaultDataBufferFactory().wrap(bytes);
        return Flux.just(buf);
      }

      @Override
      public String filename() {
        return filename;
      }

      @Override
      public Mono<Void> transferTo(Path dest) {
        return Mono.error(new UnsupportedOperationException());
      }
    };
  }
}
