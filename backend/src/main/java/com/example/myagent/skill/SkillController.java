package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;
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
    return body.collectList()
        .flatMap(parts -> {
          long fileCount = parts.stream()
              .filter(part -> part instanceof FilePart filePart
                  && StringUtils.hasText(filePart.filename()))
              .count();
          if (fileCount > AgentScopeWorkspaceService.MAX_FILE_COUNT) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Skill upload contains too many files"));
          }
          return Mono.fromCallable(() -> workspaceService.createSkill(currentUser, parts))
              .subscribeOn(Schedulers.boundedElastic());
        })
        .onErrorMap(
            error -> error instanceof DataBufferLimitException
                || error instanceof DecodingException
                && error.getMessage() != null
                && error.getMessage().startsWith("Too many parts"),
            error -> new ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Skill upload is too large",
                error));
  }

  @ExceptionHandler(ServerWebInputException.class)
  @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
  public void handleUploadLimit(ServerWebInputException error) {
    if (!isUploadLimit(error)) {
      throw error;
    }
  }

  private static boolean isUploadLimit(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof DataBufferLimitException
          || current instanceof DecodingException
          && current.getMessage() != null
          && current.getMessage().startsWith("Too many parts")) {
        return true;
      }
    }
    return false;
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
