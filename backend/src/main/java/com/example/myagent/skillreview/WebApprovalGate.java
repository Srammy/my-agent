package com.example.myagent.skillreview;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.skill.curator.SkillCandidate;
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope skill 自学习闭环的 Web 审核闸门。
 *
 * <p>当 Agent 通过 SkillManageTool 生成新 skill 草稿后，AgentScope curator 会调用此闸门决定草稿是否
 * 可以晋升为正式 skill。本实现把晋升决定权交给人工 Web 审核，而不是自动批准：
 *
 * <ul>
 *   <li>审核员通过 {@code POST /api/skill-reviews/{skillName}/approve} 批准草稿后，决定持久化在
 *       {@link SkillReviewDecisionStore}（Redis 中的 {@code skill-reviews/<name>.json}）；
 *   <li>curator 下次运行时调用 {@link #review}，读到 APPROVED 决定，返回 {@link PromotionDecision.Approve}，
 *       草稿晋升为正式 skill；
 *   <li>若尚未有决定，返回 {@link PromotionDecision.Defer}，curator 5 分钟后重试；
 *   <li>若已被拒绝（REJECTED），返回 {@link PromotionDecision.Reject}，草稿不晋升。
 * </ul>
 *
 * <p>晋升完成后，已晋升的 skill 还需通过 {@code EnvironmentFilter} 和 {@code CanaryFilter}
 * 才对特定用户可见（见 {@code AgentScopeConfig#applySkillLearning}）。
 */
@Component
public class WebApprovalGate implements SkillPromotionGate {

  private static final Duration RETRY_AFTER = Duration.ofMinutes(5);

  private final SkillReviewDecisionStore decisionStore;
  private final SkillDraftFingerprint fingerprint;

  public WebApprovalGate(
      SkillReviewDecisionStore decisionStore, SkillDraftFingerprint fingerprint) {
    this.decisionStore = decisionStore;
    this.fingerprint = fingerprint;
  }

  @Override
  public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
    return Mono.fromCallable(() -> decisionStore.find(candidate.name(), ctx.getUserId()))
        .map(
            maybeDecision -> {
              if (maybeDecision.isEmpty()) {
                return new PromotionDecision.Defer(RETRY_AFTER, "Pending web review");
              }
              SkillReviewDecision decision = maybeDecision.get();
              if (decision.draftHash() == null) {
                return new PromotionDecision.Defer(
                    RETRY_AFTER, "Draft version requires review");
              }
              try {
                String currentHash = fingerprint.computeDraftHash(ctx, candidate.name());
                if (!currentHash.equals(decision.draftHash())) {
                  return new PromotionDecision.Defer(
                      RETRY_AFTER, "Draft changed after review");
                }
              } catch (SkillDraftFingerprintException exception) {
                return new PromotionDecision.Defer(
                    RETRY_AFTER, "Draft is unavailable for review validation");
              }
              return switch (decision.status()) {
                case "APPROVED" ->
                    new PromotionDecision.Defer(
                        RETRY_AFTER, "Promotion requires explicit human approval action");
                case "REJECTED" ->
                    new PromotionDecision.Reject(
                        decision.reason() != null ? decision.reason() : "",
                        decision.reviewerId());
                default -> new PromotionDecision.Defer(RETRY_AFTER, "Decision status unknown");
              };
            });
  }
}
