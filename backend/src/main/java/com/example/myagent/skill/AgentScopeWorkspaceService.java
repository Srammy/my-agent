package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentScopeWorkspaceService {

  private static final int READ_LIMIT = 200_000;

  private final AbstractFilesystem filesystem;

  public AgentScopeWorkspaceService(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public List<SkillDto> listSkills(CurrentUser user) {
    RuntimeContext ctx = runtimeContext(user);
    LsResult result = filesystem.ls(ctx, "/skills");
    if (!result.isSuccess()) {
      return List.of();
    }
    return result.entries().stream()
        .filter(FileInfo::isDirectory)
        .map(fi -> extractLastSegment(fi.path()))
        .filter(name -> !name.isBlank() && !"_drafts".equals(name))
        .sorted()
        .map(name -> readSkillDto(ctx, name))
        .toList();
  }

  public SkillDto createSkill(CurrentUser user, List<Part> parts) {
    Map<String, byte[]> files = collectParts(parts);

    byte[] skillMdBytes = files.get("SKILL.md");
    if (skillMdBytes == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md is required");
    }

    String skillMdContent = new String(skillMdBytes, StandardCharsets.UTF_8);
    SkillValidator.SkillMarkdownMetadata meta = SkillValidator.validateSkillMarkdown(skillMdContent);

    String name = validateSkillName(meta.name());
    RuntimeContext ctx = runtimeContext(user);
    if (filesystem.exists(ctx, skillRoot(name))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists: " + name);
    }

    requireSuccess(filesystem.write(ctx, skillRoot(name) + "/SKILL.md", skillMdContent));

    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      if ("SKILL.md".equals(entry.getKey())) {
        continue;
      }
      String validatedPath = validateFilePath(entry.getKey());
      requireSuccess(filesystem.write(
          ctx, skillRoot(name) + "/" + validatedPath,
          new String(entry.getValue(), StandardCharsets.UTF_8)));
    }

    return new SkillDto(name, meta.description());
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    String name = validateSkillName(skillName);
    RuntimeContext ctx = runtimeContext(user);
    if (!filesystem.exists(ctx, skillRoot(name))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    requireSuccess(filesystem.delete(ctx, "/" + skillRoot(name)));
  }

  private SkillDto readSkillDto(RuntimeContext ctx, String skillName) {
    ReadResult result = filesystem.read(ctx, skillRoot(skillName) + "/SKILL.md", 0, READ_LIMIT);
    if (!result.isSuccess()) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read skill: " + skillName);
    }
    SkillValidator.SkillMarkdownMetadata meta =
        SkillValidator.validateSkillMarkdown(result.fileData().content());
    return new SkillDto(meta.name(), meta.description());
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder()
        .userId(user.id().toString())
        .sessionId("workspace-api")
        .build();
  }

  private void requireSuccess(WriteResult result) {
    if (!result.isSuccess()) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          StringUtils.hasText(result.error()) ? result.error() : "Workspace operation failed");
    }
  }

  private static String skillRoot(String skillName) {
    return "skills/" + skillName;
  }

  private static Map<String, byte[]> collectParts(List<Part> parts) {
    Map<String, byte[]> result = new LinkedHashMap<>();
    for (Part part : parts) {
      if (part instanceof FilePart filePart) {
        String filename = filePart.filename();
        if (StringUtils.hasText(filename)) {
          byte[] content = filePart.content()
              .map(buffer -> {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
              })
              .reduce(new byte[0], (a, b) -> {
                byte[] merged = new byte[a.length + b.length];
                System.arraycopy(a, 0, merged, 0, a.length);
                System.arraycopy(b, 0, merged, a.length, b.length);
                return merged;
              })
              .block();
          result.put(filename, content != null ? content : new byte[0]);
        }
      }
    }
    return result;
  }

  private static String validateSkillName(String name) {
    try {
      return SkillPathValidator.validateSkillName(name);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  private static String validateFilePath(String path) {
    try {
      return SkillPathValidator.validateFilePath(path);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** Extracts the last non-empty path segment, e.g. "/skills/java-helper/" → "java-helper". */
  private static String extractLastSegment(String path) {
    if (path == null) {
      return "";
    }
    String normalized = path.replace('\\', '/');
    // Remove trailing slash
    if (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    int lastSlash = normalized.lastIndexOf('/');
    return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
  }
}
