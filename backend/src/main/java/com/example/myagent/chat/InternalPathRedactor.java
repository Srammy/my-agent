package com.example.myagent.chat;

import java.util.regex.Pattern;

final class InternalPathRedactor {

  private static final Pattern DRAFT_PATH =
      Pattern.compile("`?/?skills/_drafts(?:/[A-Za-z0-9._-]+)*/*`?");
  private static final Pattern SKILL_PATH =
      Pattern.compile("`?/?skills/(?!_drafts(?:/|`|\\b))[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*/*`?");
  private static final Pattern WORKSPACE_PATH =
      Pattern.compile("`?/?\\.agentscope/workspace(?:/[A-Za-z0-9._-]+)*/*`?");

  private InternalPathRedactor() {}

  static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String redacted = DRAFT_PATH.matcher(text).replaceAll("草稿区域");
    redacted = SKILL_PATH.matcher(redacted).replaceAll("正式 Skill 区域");
    return WORKSPACE_PATH.matcher(redacted).replaceAll("工作区");
  }
}
