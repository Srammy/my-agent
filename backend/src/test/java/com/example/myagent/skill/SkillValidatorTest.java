package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SkillValidatorTest {

  @Test
  void validatePathAcceptsSupportedLocations() {
    assertThat(SkillValidator.validatePath("SKILL.md")).isEqualTo("SKILL.md");
    assertThat(SkillValidator.validatePath("references/checklist.md"))
        .isEqualTo("references/checklist.md");
    assertThat(SkillValidator.validatePath("references/guides/setup.md"))
        .isEqualTo("references/guides/setup.md");
    assertThat(SkillValidator.validatePath("scripts/analyze.java"))
        .isEqualTo("scripts/analyze.java");
  }

  @Test
  void validatePathRejectsTraversalAndAbsolutePaths() {
    assertBadRequestForPath("../secret");
    assertBadRequestForPath("/etc/passwd");
    assertBadRequestForPath("C:\\Users\\alice");
    assertBadRequestForPath("references\\notes.md");
    assertBadRequestForPath("");
    assertBadRequestForPath("   ");
    assertBadRequestForPath("notes.md");
  }

  @Test
  void validateSkillMarkdownAcceptsFrontMatterWithNameAndDescription() {
    SkillValidator.SkillMarkdownMetadata metadata =
        SkillValidator.validateSkillMarkdown(
            """
            ---
            name: mysql-helper
            description: Useful helper skill
            ---

            ## Usage
            hello
            """);

    assertThat(metadata.name()).isEqualTo("mysql-helper");
    assertThat(metadata.description()).isEqualTo("Useful helper skill");
  }

  @Test
  void validateSkillMarkdownRejectsMissingName() {
    assertBadRequestForMarkdown(
        """
        ---
        description: missing name
        ---
        """);
  }

  @Test
  void validateSkillMarkdownRejectsMissingDescription() {
    assertBadRequestForMarkdown(
        """
        ---
        name: missing-description
        ---
        """);
  }

  private static void assertBadRequestForPath(String path) {
    assertThatThrownBy(() -> SkillValidator.validatePath(path))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  private static void assertBadRequestForMarkdown(String markdown) {
    assertThatThrownBy(() -> SkillValidator.validateSkillMarkdown(markdown))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
