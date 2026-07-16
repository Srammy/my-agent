package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillReviewDecisionStoreTest {

  private AbstractFilesystem filesystem;
  private SkillReviewDecisionStore store;

  @BeforeEach
  void setUp() {
    filesystem = mock(AbstractFilesystem.class);
    store = new SkillReviewDecisionStore(filesystem);
  }

  @Test
  void approvePersistsAndReadsDraftHash() {
    when(filesystem.write(
            any(RuntimeContext.class), eq("skill-reviews/my-skill.json"), anyString()))
        .thenReturn(WriteResult.ok("skill-reviews/my-skill.json"));

    SkillReviewDecision decision =
        store.approve("my-skill", "admin", List.of("prod"), "abc123", "1");

    assertThat(decision.draftHash()).isEqualTo("abc123");
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<RuntimeContext> context =
        ArgumentCaptor.forClass(RuntimeContext.class);
    verify(filesystem)
        .write(context.capture(), eq("skill-reviews/my-skill.json"), json.capture());
    assertThat(context.getValue().getUserId()).isEqualTo("1");
    assertThat(context.getValue().getSessionId()).isEqualTo("skill-review");
    assertThat(json.getValue()).contains("\"draftHash\":\"abc123\"");
  }

  @Test
  void findReadsLegacyJsonWithoutDraftHash() {
    String legacy =
        """
        {"skillName":"my-skill","status":"APPROVED","reviewerId":"admin",
         "reason":null,"environments":["prod"],"decidedAt":"2026-07-16T00:00:00Z"}
        """;
    when(filesystem.exists(
            any(RuntimeContext.class), eq("skill-reviews/my-skill.json")))
        .thenReturn(true);
    when(filesystem.read(
            any(RuntimeContext.class),
            eq("skill-reviews/my-skill.json"),
            eq(0),
            anyInt()))
        .thenReturn(
            ReadResult.success(new FileData(legacy, "utf-8", "now", "now")));

    SkillReviewDecision decision = store.find("my-skill", "1").orElseThrow();

    assertThat(decision.draftHash()).isNull();
  }
}
