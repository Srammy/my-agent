package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
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

  @PostMapping("/mine")
  public Mono<SkillDto> createMine(
      @AuthenticationPrincipal CurrentUser currentUser, @RequestBody SkillCreateRequest request) {
    return Mono.fromCallable(() -> workspaceService.createSkill(currentUser, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PutMapping("/mine/{skillName}")
  public Mono<SkillDto> updateMine(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName,
      @RequestBody SkillCreateRequest request) {
    return Mono.fromCallable(() -> workspaceService.updateSkill(currentUser, skillName, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/mine/{skillName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteMine(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable String skillName) {
    return Mono.fromRunnable(() -> workspaceService.deleteSkill(currentUser, skillName))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @GetMapping("/{skillName}/files")
  public Mono<List<SkillFileDto>> listFiles(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable String skillName) {
    return Mono.fromCallable(() -> workspaceService.listFiles(currentUser, skillName))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PutMapping(path = "/{skillName}/files/{*path}", consumes = MediaType.TEXT_PLAIN_VALUE)
  public Mono<SkillFileDto> upsertFile(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName,
      @PathVariable String path,
      @RequestBody String content) {
    return Mono.fromCallable(
            () -> workspaceService.upsertFile(currentUser, skillName, trimCapturedPath(path), content))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/{skillName}/files/{*path}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteFile(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName,
      @PathVariable String path) {
    return Mono.fromRunnable(
            () -> workspaceService.deleteFile(currentUser, skillName, trimCapturedPath(path)))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private static String trimCapturedPath(String path) {
    return path != null && path.startsWith("/") ? path.substring(1) : path;
  }
}
