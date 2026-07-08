package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.skill.curator.SkillUsageRecord;
import io.agentscope.harness.agent.skill.curator.SkillUsageStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillReviewServiceTest {

  private AbstractFilesystem filesystem;
  private SkillReviewDecisionStore decisionStore;
  private SkillUsageStore usageStore;
  private SkillReviewService service;

  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    decisionStore = mock(SkillReviewDecisionStore.class);
    usageStore = mock(SkillUsageStore.class);
    service = new SkillReviewService(filesystem, decisionStore, usageStore);
  }

  @Test
  void listReturnsPendingSkillsFromDraftsDirectory() {
    when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts"))).thenReturn(true);
    when(filesystem.ls(any(RuntimeContext.class), eq("skills/_drafts")))
        .thenReturn(
            LsResult.success(
                List.of(FileInfo.ofDir("my-skill", "2026-07-08T09:00:00"))));

    String skillMd = "---\nname: \"my-skill\"\ndescription: \"My skill description\"\n---\n";
    when(filesystem.exists(any(RuntimeContext.class), eq("skills/_drafts/my-skill/SKILL.md")))
        .thenReturn(true);
    when(filesystem.read(any(RuntimeContext.class), eq("skills/_drafts/my-skill/SKILL.md"), eq(0), anyInt()))
        .thenReturn(ReadResult.success(new FileData(skillMd, "utf-8", "2026-07-08T09:00:00", "2026-07-08T09:00:00")));

    when(decisionStore.find("my-skill")).thenReturn(Optional.empty());
    when(usageStore.get("my-skill")).thenReturn(Optional.empty());

    List<SkillReviewDto> result = service.list();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).skillName()).isEqualTo("my-skill");
    assertThat(result.get(0).description()).isEqualTo("My skill description");
    assertThat(result.get(0).status()).isEqualTo("PENDING");
  }

  @Test
  void approveStoresDecisionAndReturnsApprovedStatus() {
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision("my-skill", "APPROVED", "admin", null, List.of("prod"), now);
    when(decisionStore.approve("my-skill", "admin", List.of("prod"))).thenReturn(decision);
    when(usageStore.get("my-skill")).thenReturn(Optional.empty());

    SkillReviewDto result =
        service.approve("my-skill", new ApproveSkillReviewRequest("admin", List.of("prod")));

    assertThat(result.status()).isEqualTo("APPROVED");
    assertThat(result.skillName()).isEqualTo("my-skill");
    assertThat(result.environments()).containsExactly("prod");
  }

  @Test
  void rejectStoresDecisionAndReturnsRejectedStatus() {
    Instant now = Instant.now();
    SkillReviewDecision decision =
        new SkillReviewDecision("my-skill", "REJECTED", "admin", "Too risky", List.of(), now);
    when(decisionStore.reject("my-skill", "admin", "Too risky")).thenReturn(decision);
    when(usageStore.get("my-skill")).thenReturn(Optional.empty());

    SkillReviewDto result =
        service.reject("my-skill", new RejectSkillReviewRequest("admin", "Too risky"));

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.skillName()).isEqualTo("my-skill");
  }
}
