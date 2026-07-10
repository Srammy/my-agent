package com.example.myagent.skillreview;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/skill-reviews")
public class SkillReviewController {

  private final SkillReviewService reviewService;

  public SkillReviewController(SkillReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @GetMapping
  public Mono<List<SkillReviewDto>> list(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> reviewService.list(currentUser.id().toString()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/{skillName}/approve")
  public Mono<SkillReviewDto> approve(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName,
      @RequestBody ApproveSkillReviewRequest request) {
    return Mono.fromCallable(() -> reviewService.approve(skillName, request, currentUser.id().toString()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/{skillName}/reject")
  public Mono<SkillReviewDto> reject(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String skillName,
      @RequestBody RejectSkillReviewRequest request) {
    return Mono.fromCallable(() -> reviewService.reject(skillName, request, currentUser.id().toString()))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
