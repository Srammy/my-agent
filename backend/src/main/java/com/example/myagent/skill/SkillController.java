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

  private final SkillService skillService;

  public SkillController(SkillService skillService) {
    this.skillService = skillService;
  }

  @GetMapping("/system")
  public Mono<List<SkillDto>> listSystemSkills(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> skillService.listSystemSkills(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/mine")
  public Mono<List<SkillDto>> listMySkills(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> skillService.listMySkills(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/mine")
  public Mono<SkillDto> createMySkill(
      @AuthenticationPrincipal CurrentUser currentUser, @RequestBody SkillCreateRequest request) {
    return Mono.fromCallable(() -> skillService.createMySkill(currentUser, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PutMapping("/mine/{skillId}")
  public Mono<SkillDto> updateMySkill(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long skillId,
      @RequestBody SkillCreateRequest request) {
    return Mono.fromCallable(() -> skillService.updateMySkill(currentUser, skillId, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/mine/{skillId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteMySkill(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long skillId) {
    return Mono.fromRunnable(() -> skillService.deleteMySkill(currentUser, skillId))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @GetMapping("/{skillId}/files")
  public Mono<List<SkillFileDto>> listFiles(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long skillId) {
    return Mono.fromCallable(() -> skillService.listFiles(currentUser, skillId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PutMapping(path = "/{skillId}/files/{*path}", consumes = MediaType.TEXT_PLAIN_VALUE)
  public Mono<SkillFileDto> upsertFile(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long skillId,
      @PathVariable String path,
      @RequestBody String content) {
    return Mono.fromCallable(
            () -> skillService.upsertFile(currentUser, skillId, trimCapturedPath(path), content))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/{skillId}/files/{*path}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> deleteFile(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long skillId,
      @PathVariable String path) {
    return Mono.fromRunnable(
            () -> skillService.deleteFile(currentUser, skillId, trimCapturedPath(path)))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @PutMapping("/{skillId}/enabled")
  public Mono<SkillDto> setEnabled(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long skillId,
      @RequestBody SkillEnabledRequest request) {
    return Mono.fromCallable(
            () -> skillService.setEnabled(currentUser, skillId, Boolean.TRUE.equals(request.enabled())))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private static String trimCapturedPath(String path) {
    return path != null && path.startsWith("/") ? path.substring(1) : path;
  }
}
