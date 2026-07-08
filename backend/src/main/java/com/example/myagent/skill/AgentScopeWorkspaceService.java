package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    RuntimeContext runtimeContext = runtimeContext(user);
    if (!filesystem.exists(runtimeContext, "skills")) {
      return List.of();
    }
    LsResult result = filesystem.ls(runtimeContext, "skills");
    if (!result.isSuccess()) {
      throw workspaceFailure(result.error());
    }
    return result.entries().stream()
        .filter(FileInfo::isDirectory)
        .map(FileInfo::path)
        .filter(name -> !"_drafts".equals(name))
        .sorted()
        .map(skillName -> readSkillDto(runtimeContext, skillName))
        .toList();
  }

  public SkillDto createSkill(CurrentUser user, SkillCreateRequest request) {
    String skillName = normalizeName(request.name());
    String description = normalizeDescription(request.description());
    RuntimeContext runtimeContext = runtimeContext(user);
    if (filesystem.exists(runtimeContext, skillRoot(skillName))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill name already exists");
    }
    requireSuccess(
        filesystem.write(
            runtimeContext,
            skillFilePath(skillName, "SKILL.md"),
            buildSkillMarkdown(skillName, description, "")));
    return new SkillDto(skillName, description, true, LocalDateTime.now().toString());
  }

  public SkillDto updateSkill(CurrentUser user, String skillName, SkillCreateRequest request) {
    String currentSkillName = validateSkillName(skillName);
    String nextSkillName = normalizeName(request.name());
    String description = normalizeDescription(request.description());
    RuntimeContext runtimeContext = runtimeContext(user);
    requireSkillExists(runtimeContext, currentSkillName);

    String body = extractMarkdownBody(readFile(runtimeContext, skillFilePath(currentSkillName, "SKILL.md")).content());
    if (!currentSkillName.equals(nextSkillName)) {
      if (filesystem.exists(runtimeContext, skillRoot(nextSkillName))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill name already exists");
      }
      requireSuccess(filesystem.move(runtimeContext, skillRoot(currentSkillName), skillRoot(nextSkillName)));
    }

    requireSuccess(
        filesystem.write(
            runtimeContext,
            skillFilePath(nextSkillName, "SKILL.md"),
            buildSkillMarkdown(nextSkillName, description, body)));
    return new SkillDto(nextSkillName, description, true, LocalDateTime.now().toString());
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    RuntimeContext runtimeContext = runtimeContext(user);
    requireSkillExists(runtimeContext, skillName);
    requireSuccess(filesystem.delete(runtimeContext, skillRoot(skillName)));
  }

  public List<SkillFileDto> listFiles(CurrentUser user, String skillName) {
    RuntimeContext runtimeContext = runtimeContext(user);
    String validatedSkillName = validateSkillName(skillName);
    String skillRoot = skillRoot(validatedSkillName);
    requireSkillExists(runtimeContext, validatedSkillName);
    List<String> files = new ArrayList<>();
    collectFiles(runtimeContext, skillRoot, files);
    return files.stream()
        .sorted()
        .map(path -> path.substring(skillRoot.length() + 1))
        .map(path -> toFileDto(runtimeContext, validatedSkillName, path))
        .toList();
  }

  public SkillFileDto upsertFile(CurrentUser user, String skillName, String path, String content) {
    if (content == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill file content is required");
    }
    RuntimeContext runtimeContext = runtimeContext(user);
    String validatedSkillName = validateSkillName(skillName);
    String validatedPath = validateFilePath(path);
    requireSkillExists(runtimeContext, validatedSkillName);
    if ("SKILL.md".equals(validatedPath)) {
      SkillValidator.SkillMarkdownMetadata metadata = SkillValidator.validateSkillMarkdown(content);
      if (!validatedSkillName.equals(normalizeName(metadata.name()))) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "SKILL.md name must match the skill name");
      }
      normalizeDescription(metadata.description());
    }
    requireSuccess(filesystem.write(runtimeContext, skillFilePath(validatedSkillName, validatedPath), content));
    return toFileDto(runtimeContext, validatedSkillName, validatedPath);
  }

  public void deleteFile(CurrentUser user, String skillName, String path) {
    RuntimeContext runtimeContext = runtimeContext(user);
    String validatedSkillName = validateSkillName(skillName);
    String validatedPath = validateFilePath(path);
    if ("SKILL.md".equals(validatedPath)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md cannot be deleted");
    }
    requireSkillExists(runtimeContext, validatedSkillName);
    requireSuccess(filesystem.delete(runtimeContext, skillFilePath(validatedSkillName, validatedPath)));
  }

  private SkillDto readSkillDto(RuntimeContext runtimeContext, String skillName) {
    FileData fileData = readFile(runtimeContext, skillFilePath(skillName, "SKILL.md"));
    SkillValidator.SkillMarkdownMetadata metadata = SkillValidator.validateSkillMarkdown(fileData.content());
    return new SkillDto(metadata.name(), metadata.description(), true, fileData.modifiedAt());
  }

  private SkillFileDto toFileDto(RuntimeContext runtimeContext, String skillName, String path) {
    FileData fileData = readFile(runtimeContext, skillFilePath(skillName, path));
    return new SkillFileDto(path, fileData.content(), detectContentType(path), false, fileData.modifiedAt());
  }

  private void collectFiles(RuntimeContext runtimeContext, String directory, List<String> files) {
    LsResult result = filesystem.ls(runtimeContext, directory);
    if (!result.isSuccess()) {
      throw workspaceFailure(result.error());
    }
    result.entries().stream()
        .sorted(Comparator.comparing(FileInfo::path))
        .forEach(
            entry -> {
              String childPath = directory + "/" + entry.path();
              if (entry.isDirectory()) {
                collectFiles(runtimeContext, childPath, files);
                return;
              }
              files.add(childPath);
            });
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder().userId(user.id().toString()).sessionId("workspace-api").build();
  }

  private void requireSkillExists(RuntimeContext runtimeContext, String skillName) {
    String validatedSkillName = validateSkillName(skillName);
    if (!filesystem.exists(runtimeContext, skillRoot(validatedSkillName))) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
  }

  private FileData readFile(RuntimeContext runtimeContext, String path) {
    ReadResult result = filesystem.read(runtimeContext, path, 0, READ_LIMIT);
    if (!result.isSuccess()) {
      throw workspaceFailure(result.error());
    }
    return result.fileData();
  }

  private void requireSuccess(WriteResult result) {
    if (!result.isSuccess()) {
      throw workspaceFailure(result.error());
    }
  }

  private static ResponseStatusException workspaceFailure(String error) {
    return new ResponseStatusException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        StringUtils.hasText(error) ? error : "Workspace skill operation failed");
  }

  private static String skillRoot(String skillName) {
    return "skills/" + validateSkillName(skillName);
  }

  private static String skillFilePath(String skillName, String path) {
    return skillRoot(skillName) + "/" + validateFilePath(path);
  }

  private static String validateSkillName(String skillName) {
    try {
      return SkillPathValidator.validateSkillName(skillName);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  private static String validateFilePath(String path) {
    try {
      return SkillPathValidator.validateFilePath(path);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
  }

  private static String normalizeName(String rawName) {
    String name = validateSkillName(rawName);
    rejectLineBreaks(name, "Skill name");
    return name;
  }

  private static String normalizeDescription(String rawDescription) {
    if (!StringUtils.hasText(rawDescription)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill description is required");
    }
    rejectLineBreaks(rawDescription, "Skill description");
    return rawDescription.trim();
  }

  private static void rejectLineBreaks(String value, String fieldName) {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, fieldName + " must not contain line breaks");
    }
  }

  private static String buildSkillMarkdown(String name, String description, String body) {
    String normalizedBody = body == null ? "" : body.strip();
    StringBuilder builder =
        new StringBuilder()
            .append("---\n")
            .append("name: ")
            .append(quoteYaml(name))
            .append('\n')
            .append("description: ")
            .append(quoteYaml(description))
            .append("\n---\n");
    if (!normalizedBody.isEmpty()) {
      builder.append('\n').append(normalizedBody).append('\n');
    } else {
      builder.append('\n');
    }
    return builder.toString();
  }

  private static String extractMarkdownBody(String content) {
    if (!StringUtils.hasText(content) || !content.startsWith("---")) {
      return "";
    }
    int firstBreak = content.indexOf('\n');
    int secondBoundary = content.indexOf("\n---", firstBreak);
    if (firstBreak < 0 || secondBoundary < 0) {
      return "";
    }
    int bodyStart = secondBoundary + "\n---".length();
    return bodyStart >= content.length() ? "" : content.substring(bodyStart).strip();
  }

  private static String detectContentType(String path) {
    if (path.endsWith(".md")) {
      return "text/markdown";
    }
    return "text/plain";
  }

  private static String quoteYaml(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
