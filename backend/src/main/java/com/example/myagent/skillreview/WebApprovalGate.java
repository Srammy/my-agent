package com.example.myagent.skillreview;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class WebApprovalGate implements SkillPromotionGate {

  private static final Duration RETRY_AFTER = Duration.ofMinutes(5);

  private final SkillReviewDecisionStore decisionStore;

  public WebApprovalGate(SkillReviewDecisionStore decisionStore) {
    this.decisionStore = decisionStore;
  }

  @Override
  public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
    return Mono.fromCallable(() -> decisionStore.find(candidate.name()))
        .map(
            maybeDecision -> {
              if (maybeDecision.isEmpty()) {
                return new PromotionDecision.Defer(RETRY_AFTER, "Pending web review");
              }
              SkillReviewDecision decision = maybeDecision.get();
              return switch (decision.status()) {
                case "APPROVED" ->
                    new PromotionDecision.Approve(
                        decision.reviewerId(),
                        decision.environments() != null ? decision.environments() : java.util.List.of(),
                        decision.decidedAt());
                case "REJECTED" ->
                    new PromotionDecision.Reject(
                        decision.reason() != null ? decision.reason() : "",
                        decision.reviewerId());
                default -> new PromotionDecision.Defer(RETRY_AFTER, "Decision status unknown");
              };
            });
  }
}
