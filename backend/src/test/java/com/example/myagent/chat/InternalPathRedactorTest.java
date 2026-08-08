package com.example.myagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InternalPathRedactorTest {

  @Test
  void redactsKnownInternalPathsAndKeepsBusinessText() {
    String text =
        "草稿目录（`skills/_drafts/`）为空；文件位于 skills/jp_drama_recommend/SKILL.md；工作区 .agentscope/workspace/tmp。";

    assertThat(InternalPathRedactor.redact(text))
        .isEqualTo("草稿目录（草稿区域）为空；文件位于 正式 Skill 区域；工作区 工作区。");
  }
}
