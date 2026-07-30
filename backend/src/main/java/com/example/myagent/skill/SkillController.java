package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

  private final AgentScopeWorkspaceService workspaceService;

  public SkillController(AgentScopeWorkspaceService workspaceService) {
    this.workspaceService = workspaceService;
  }

  @GetMapping("/mine")
  public Mono<List<SkillDto>> listMine(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> workspaceService.listSkills(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(value = "/mine", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Mono<SkillDto> createMine(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestBody Flux<Part> body) {
    return body.take(AgentScopeWorkspaceService.MAX_FILE_COUNT + 1L)
        .collectList()
        .flatMap(parts -> {
          long fileCount = parts.stream().filter(FilePart.class::isInstance).count();
          if (fileCount > AgentScopeWorkspaceService.MAX_FILE_COUNT) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Skill upload contains too many files"));
          }
          return Mono.fromCallable(() -> workspaceService.createSkill(currentUser, parts))
              .subscribeOn(Schedulers.boundedElastic());
        })
        .onErrorMap(
            DataBufferLimitException.class,
            error -> new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Skill upload is too large",
                error));
  }

  @DeleteMapping("/mine/{skillName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteMine(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName) {
    return Mono.fromRunnable(() -> workspaceService.deleteSkill(currentUser, skillName))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }
}
