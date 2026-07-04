package com.example.myagent.skill;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

public final class SkillValidator {

  private SkillValidator() {}

  public static String validatePath(String path) {
    if (!StringUtils.hasText(path)) {
      throw badRequest("Skill file path is required");
    }

    String normalized = path.trim();
    if (normalized.startsWith("/") || normalized.startsWith("\\") || normalized.contains("\\")) {
      throw badRequest("Skill file path must be relative and use '/' separators");
    }
    if (normalized.matches("^[A-Za-z]:.*")) {
      throw badRequest("Skill file path must not use a drive letter");
    }
    if (normalized.endsWith("/")) {
      throw badRequest("Skill file path must point to a file");
    }

    String[] segments = normalized.split("/");
    for (String segment : segments) {
      if (!StringUtils.hasText(segment) || ".".equals(segment) || "..".equals(segment)) {
        throw badRequest("Skill file path contains an invalid segment");
      }
    }

    if ("SKILL.md".equals(normalized)) {
      return normalized;
    }

    String root = segments[0];
    if (!"references".equals(root) && !"scripts".equals(root) && !"assets".equals(root)) {
      throw badRequest("Skill file path root is not allowed");
    }
    if (segments.length < 2) {
      throw badRequest("Skill file path must include a file name");
    }
    return normalized;
  }

  public static SkillMarkdownMetadata validateSkillMarkdown(String content) {
    if (!StringUtils.hasText(content)) {
      throw badRequest("SKILL.md content is required");
    }
    if (!content.startsWith("---")) {
      throw badRequest("SKILL.md must start with front matter");
    }

    int boundaryStart = content.indexOf('\n');
    if (boundaryStart < 0) {
      throw badRequest("SKILL.md front matter is incomplete");
    }

    int boundaryEnd = content.indexOf("\n---", boundaryStart);
    if (boundaryEnd < 0) {
      throw badRequest("SKILL.md front matter must end with '---'");
    }

    String frontMatter = content.substring(boundaryStart + 1, boundaryEnd).trim();
    Map<String, String> values = new HashMap<>();
    for (String line : frontMatter.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int separatorIndex = trimmed.indexOf(':');
      if (separatorIndex <= 0) {
        continue;
      }
      String key = trimmed.substring(0, separatorIndex).trim();
      String value = trimmed.substring(separatorIndex + 1).trim();
      values.put(key, stripQuotes(value));
    }

    String name = values.get("name");
    String description = values.get("description");
    if (!StringUtils.hasText(name)) {
      throw badRequest("SKILL.md front matter must include name");
    }
    if (!StringUtils.hasText(description)) {
      throw badRequest("SKILL.md front matter must include description");
    }
    return new SkillMarkdownMetadata(name.trim(), description.trim());
  }

  private static String stripQuotes(String value) {
    if ((value.startsWith("\"") && value.endsWith("\""))
        || (value.startsWith("'") && value.endsWith("'"))) {
      String quotedValue = value.substring(1, value.length() - 1);
      if (value.startsWith("\"") && value.endsWith("\"")) {
        return unescapeDoubleQuotedValue(quotedValue);
      }
      return quotedValue;
    }
    return value;
  }

  private static String unescapeDoubleQuotedValue(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    boolean escaping = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (escaping) {
        builder.append(current);
        escaping = false;
      } else if (current == '\\') {
        escaping = true;
      } else {
        builder.append(current);
      }
    }
    if (escaping) {
      builder.append('\\');
    }
    return builder.toString();
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  public record SkillMarkdownMetadata(String name, String description) {}
}
