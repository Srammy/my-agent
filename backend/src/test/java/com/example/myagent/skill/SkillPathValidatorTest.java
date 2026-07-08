package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkillPathValidatorTest {

  @Test
  void acceptsSafeSkillNamesAndFiles() {
    assertThat(SkillPathValidator.validateSkillName("java-helper")).isEqualTo("java-helper");
    assertThat(SkillPathValidator.validateFilePath("SKILL.md")).isEqualTo("SKILL.md");
    assertThat(SkillPathValidator.validateFilePath("references/checklist.md"))
        .isEqualTo("references/checklist.md");
    assertThat(SkillPathValidator.validateFilePath("scripts/analyze.java"))
        .isEqualTo("scripts/analyze.java");
  }

  @Test
  void rejectsUnsafeSkillNamesAndFiles() {
    assertThatThrownBy(() -> SkillPathValidator.validateSkillName("../secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateSkillName("C:\\Users\\a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("../secret"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("/etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SkillPathValidator.validateFilePath("C:\\Users\\a"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
