package com.example.myagent.skill;

import java.util.Set;
import org.springframework.util.StringUtils;

public final class SkillPathValidator {

  private static final Set<String> ALLOWED_ROOTS = Set.of("SKILL.md", "references", "scripts", "assets");

  private SkillPathValidator() {}

  public static String validateSkillName(String name) {
    if (!StringUtils.hasText(name)) {
      throw new IllegalArgumentException("Skill name is required");
    }
    String value = name.trim();
    if (value.contains("/") || value.contains("\\") || value.contains("..") || value.contains(":")) {
      throw new IllegalArgumentException("Invalid skill name");
    }
    return value;
  }

  public static String validateFilePath(String path) {
    if (!StringUtils.hasText(path)) {
      throw new IllegalArgumentException("Skill file path is required");
    }
    String value = path.trim().replace('\\', '/');
    if (value.startsWith("/") || value.endsWith("/") || value.contains("../") || value.contains("..")
        || value.contains(":") || value.contains("//")) {
      throw new IllegalArgumentException("Invalid skill file path");
    }
    String root = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
    if (!ALLOWED_ROOTS.contains(root)) {
      throw new IllegalArgumentException("Unsupported skill file root");
    }
    if (!"SKILL.md".equals(value) && !value.contains("/")) {
      throw new IllegalArgumentException("Skill file path must include a file name");
    }
    return value;
  }
}
