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
  private static final int MAX_DIRECTORY_DEPTH = 16;
  private static final int MAX_FILE_COUNT = 100;
  private static final long MAX_TOTAL_BYTES = 1_048_576L;

  private final AbstractFilesystem filesystem;

  public SkillDraftFingerprint(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public String computeDraftHash(RuntimeContext context, String skillName) {
    try {
      return computeDraftHashUnchecked(context, skillName);
    } catch (SkillDraftFingerprintException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.READ_FAILURE,
          "Failed to fingerprint skill draft: " + skillName,
          exception);
    }
  }

  private String computeDraftHashUnchecked(RuntimeContext context, String skillName) {
    String root = DRAFTS_DIR + "/" + skillName;
    if (!filesystem.exists(context, root)) {
      throw new SkillDraftFingerprintException(
          SkillDraftFingerprintException.Reason.NOT_FOUND,
          "Skill draft not found: " + skillName);
    }
    boolean skillMarkdownExists = filesystem.exists(context, root + "/SKILL.md");

    List<String> paths = new ArrayList<>();
    collectFilePaths(context, root, root, 0, new DraftBudget(), paths);
    paths.sort(Comparator.naturalOrder());
    if (!paths.contains("SKILL.md")) {
      throw new SkillDraftFingerprintException(
          skillMarkdownExists
              ? SkillDraftFingerprintException.Reason.READ_FAILURE
              : SkillDraftFingerprintException.Reason.NOT_FOUND,
          "Skill draft changed while fingerprinting: " + skillName);
    }

    MessageDigest digest = sha256();
    long totalContentBytes = 0L;
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
      byte[] content = result.fileData().content().getBytes(StandardCharsets.UTF_8);
      if (content.length > MAX_TOTAL_BYTES - totalContentBytes) {
        throw limitExceeded("maximum total bytes");
      }
      totalContentBytes += content.length;
      updateLengthPrefixed(digest, relativePath.getBytes(StandardCharsets.UTF_8));
      updateLengthPrefixed(digest, content);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private void collectFilePaths(
      RuntimeContext context,
      String root,
      String directory,
      int depth,
      DraftBudget budget,
      List<String> result) {
    if (depth > MAX_DIRECTORY_DEPTH) {
      throw limitExceeded("maximum depth");
    }
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
        collectFilePaths(context, root, path, depth + 1, budget, result);
      } else {
        if (++budget.fileCount > MAX_FILE_COUNT) {
          throw limitExceeded("maximum file count");
        }
        if (entry.size() < 0 || entry.size() > MAX_TOTAL_BYTES - budget.declaredBytes) {
          throw limitExceeded("maximum total bytes");
        }
        budget.declaredBytes += entry.size();
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

  private static SkillDraftFingerprintException limitExceeded(String limit) {
    return new SkillDraftFingerprintException(
        SkillDraftFingerprintException.Reason.READ_FAILURE,
        "Skill draft exceeds " + limit);
  }

  private static final class DraftBudget {
    private int fileCount;
    private long declaredBytes;
  }
}
