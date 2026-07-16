package com.example.myagent.skillreview;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class SkillDraftFingerprint {

  private static final String DRAFTS_DIR = "skills/_drafts";

  private final AbstractFilesystem filesystem;

  public SkillDraftFingerprint(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public String computeDraftHash(RuntimeContext context, String skillName) {
    String root = DRAFTS_DIR + "/" + skillName;
    if (!filesystem.exists(context, root)) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND,
          "Skill draft not found: " + skillName);
    }
    boolean skillMarkdownExists = filesystem.exists(context, root + "/SKILL.md");

    List<String> paths = new ArrayList<>();
    collectFilePaths(context, root, root, paths);
    paths.sort(Comparator.naturalOrder());
    if (!paths.contains("SKILL.md")) {
      throw new SkillDraftFingerprintException(
          skillMarkdownExists
              ? SkillDraftFingerprintException.Reason.READ_FAILURE
              : SkillDraftFingerprintException.Reason.NOT_FOUND,
          "Skill draft changed while fingerprinting: " + skillName);
    }

    MessageDigest digest = sha256();
    for (String relativePath : paths) {
      String absolutePath = root + "/" + relativePath;
      ReadResult result = filesystem.read(context, absolutePath, 0, 0);
      if (!result.isSuccess()
          || result.fileData() == null
          || result.fileData().content() == null) {
        throw new SkillDraftFingerprintException(
            SkillDraftFingerprintException.Reason.READ_FAILURE,
            "Failed to read skill draft file: " + absolutePath);
      }
      updateLengthPrefixed(digest, relativePath.getBytes(StandardCharsets.UTF_8));
      updateLengthPrefixed(
          digest, result.fileData().content().getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void collectFilePaths(
      RuntimeContext context, String root, String directory, List<String> result) {
    String normalizedDirectory = stripTrailingSlash(
        stripLeadingSlash(directory.replace('\\', '/')));
    LsResult lsResult = filesystem.ls(context, "/" + normalizedDirectory);
    if (lsResult == null) {
      lsResult = filesystem.ls(context, normalizedDirectory);
    }
    if (!lsResult.isSuccess() || lsResult.entries() == null) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE,
          "Failed to list skill draft directory: " + normalizedDirectory);
    }
    for (FileInfo entry : lsResult.entries()) {
      String path = childPath(normalizedDirectory, entry.path());
      if (entry.isDirectory()) {
        collectFilePaths(context, root, path, result);
      } else {
        result.add(path.substring(root.length() + 1).replace('\\', '/'));
      }
    }
  }

  private static String childPath(String directory, String entryPath) {
    String normalizedDirectory = stripTrailingSlash(
        stripLeadingSlash(directory.replace('\\', '/')));
    String normalizedEntry = stripTrailingSlash(
        stripLeadingSlash(entryPath.replace('\\', '/')));
    return normalizedEntry.startsWith(normalizedDirectory + "/")
        ? normalizedEntry
        : normalizedDirectory + "/" + normalizedEntry;
  }

  private static String stripLeadingSlash(String path) {
    String normalized = path;
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  private static String stripTrailingSlash(String path) {
    String normalized = path;
    while (!normalized.isEmpty() && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
