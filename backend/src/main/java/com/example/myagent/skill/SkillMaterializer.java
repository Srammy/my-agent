package com.example.myagent.skill;

import com.example.myagent.config.AgentProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SkillMaterializer {

  private static final String MATERIALIZED_KEY_FILE = ".materialized-key";

  private final SkillService skillService;
  private final Path cacheRoot;

  public SkillMaterializer(SkillService skillService, AgentProperties agentProperties) {
    this.skillService = skillService;
    this.cacheRoot = Path.of(agentProperties.skill().cacheDir()).toAbsolutePath().normalize();
  }

  public Path materializeForUser(Long userId) {
    try {
      Files.createDirectories(cacheRoot);
      Path userRoot = resolveDirectory(cacheRoot, userId.toString());
      Files.createDirectories(userRoot);

      Set<String> activeDirectories = new HashSet<>();
      for (SkillService.MaterializedSkill skill : skillService.listEnabledSkillsForUser(userId)) {
        Path skillRoot = resolveDirectory(userRoot, sanitizeDirectoryName(skill));
        activeDirectories.add(skillRoot.getFileName().toString());
        materializeSkill(skillRoot, skill);
      }

      cleanupStaleDirectories(userRoot, activeDirectories);
      return userRoot;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to materialize skills for user " + userId, exception);
    }
  }

  private void materializeSkill(Path skillRoot, SkillService.MaterializedSkill skill) throws IOException {
    String materializedKey = buildMaterializedKey(skill);
    if (Files.isDirectory(skillRoot) && materializedKey.equals(readMaterializedKey(skillRoot))) {
      return;
    }

    deleteRecursively(skillRoot);
    Files.createDirectories(skillRoot);

    boolean hasSkillMarkdown = false;
    for (SkillFileEntity file : skill.files()) {
      String validatedPath = validateDatabasePath(file.getPath(), skill.skillId());
      Path targetFile = resolveFile(skillRoot, validatedPath);
      Files.createDirectories(targetFile.getParent());
      Files.writeString(
          targetFile, file.getContent() == null ? "" : file.getContent(), StandardCharsets.UTF_8);
      if ("SKILL.md".equals(validatedPath)) {
        hasSkillMarkdown = true;
      }
    }
    if (!hasSkillMarkdown) {
      throw new IllegalStateException("Skill " + skill.skillId() + " is missing SKILL.md");
    }

    Files.writeString(skillRoot.resolve(MATERIALIZED_KEY_FILE), materializedKey, StandardCharsets.UTF_8);
  }

  private void cleanupStaleDirectories(Path userRoot, Set<String> activeDirectories) throws IOException {
    try (var children = Files.list(userRoot)) {
      for (Path child : children.toList()) {
        if (Files.isDirectory(child) && !activeDirectories.contains(child.getFileName().toString())) {
          deleteRecursively(child);
        }
      }
    }
  }

  private String readMaterializedKey(Path skillRoot) throws IOException {
    Path keyFile = skillRoot.resolve(MATERIALIZED_KEY_FILE);
    if (!Files.isRegularFile(keyFile)) {
      return "";
    }
    return Files.readString(keyFile, StandardCharsets.UTF_8);
  }

  private String buildMaterializedKey(SkillService.MaterializedSkill skill) {
    long updatedAtMillis =
        skill.updatedAt() == null ? 0L : skill.updatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
    return skill.skillId() + "-" + updatedAtMillis + "-" + fingerprintFiles(skill.files());
  }

  private String fingerprintFiles(Iterable<SkillFileEntity> files) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      java.util.stream.StreamSupport.stream(files.spliterator(), false)
          .sorted(Comparator.comparing(SkillFileEntity::getPath))
          .forEach(
          file -> {
            updateDigest(digest, file.getPath());
            updateDigest(
                digest,
                file.getUpdatedAt() == null
                    ? "0"
                    : String.valueOf(
                        file.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
            updateDigest(digest, file.getContent() == null ? "" : file.getContent());
            digest.update((byte) 0);
          });
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Failed to compute skill materialization fingerprint", exception);
    }
  }

  private static void updateDigest(MessageDigest digest, String value) {
    digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  private String validateDatabasePath(String path, Long skillId) {
    try {
      return SkillValidator.validatePath(path);
    } catch (RuntimeException exception) {
      throw new IllegalStateException(
          "Skill " + skillId + " contains invalid file path: " + path, exception);
    }
  }

  private Path resolveDirectory(Path parent, String child) {
    if (!StringUtils.hasText(child)) {
      throw new IllegalStateException("Skill directory name is required");
    }
    Path resolved = parent.resolve(child).normalize();
    if (!resolved.startsWith(parent)) {
      throw new IllegalStateException("Resolved directory escapes cache root: " + child);
    }
    return resolved;
  }

  private Path resolveFile(Path skillRoot, String relativePath) {
    Path resolved = skillRoot.resolve(relativePath).normalize();
    if (!resolved.startsWith(skillRoot)) {
      throw new IllegalStateException("Resolved file escapes skill root: " + relativePath);
    }
    return resolved;
  }

  private String sanitizeDirectoryName(SkillService.MaterializedSkill skill) {
    String name = skill.name() == null ? "" : skill.name().trim();
    if (name.isEmpty()) {
      return fallbackDirectoryName(skill);
    }

    String sanitized =
        name.replace('/', '_').replace('\\', '_').replace(':', '_').replace('\u0000', '_');
    if (".".equals(sanitized) || "..".equals(sanitized) || sanitized.isBlank()) {
      return fallbackDirectoryName(skill);
    }
    if (!sanitized.equals(name)) {
      return sanitized + "--" + skill.skillId();
    }
    return sanitized;
  }

  private String fallbackDirectoryName(SkillService.MaterializedSkill skill) {
    return skill.ownerType().toLowerCase() + "-" + skill.skillId();
  }

  private void deleteRecursively(Path target) throws IOException {
    if (!Files.exists(target)) {
      return;
    }
    try (var paths = Files.walk(target)) {
      for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
