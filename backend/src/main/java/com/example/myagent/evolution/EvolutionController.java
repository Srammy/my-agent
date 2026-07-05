package com.example.myagent.evolution;

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
@RequestMapping("/api/evolution/proposals")
public class EvolutionController {

  private final EvolutionService evolutionService;

  public EvolutionController(EvolutionService evolutionService) {
    this.evolutionService = evolutionService;
  }

  @GetMapping
  public Mono<List<EvolutionProposalDto>> listProposals(
      @AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> evolutionService.listProposals(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping
  public Mono<EvolutionProposalDto> createProposal(
      @AuthenticationPrincipal CurrentUser currentUser, @RequestBody EvolutionCreateRequest request) {
    return Mono.fromCallable(() -> evolutionService.createProposal(currentUser, request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/{id}/approve")
  public Mono<EvolutionProposalDto> approve(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return Mono.fromCallable(() -> evolutionService.approve(currentUser, id))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/{id}/reject")
  public Mono<EvolutionProposalDto> reject(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return Mono.fromCallable(() -> evolutionService.reject(currentUser, id))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/{id}/apply")
  public Mono<EvolutionProposalDto> apply(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable Long id) {
    return Mono.fromCallable(() -> evolutionService.apply(currentUser, id))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
