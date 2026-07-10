package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
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

  private static final String SKILLS_DIR = "skills";

  private final AbstractFilesystem filesystem;

  public AgentScopeWorkspaceService(AbstractFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  public List<SkillDto> listSkills(CurrentUser user) {
    return repoFor(user).getAllSkills().stream()
        .map(skill -> new SkillDto(skill.getName(), skill.getDescription()))
        .sorted(Comparator.comparing(SkillDto::name))
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
    WorkspaceSkillRepository repo = repoFor(user);
    if (repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists: " + name);
    }

    Map<String, String> resources = new LinkedHashMap<>();
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      if ("SKILL.md".equals(entry.getKey())) {
        continue;
      }
      String validatedPath = validateFilePath(entry.getKey());
      resources.put(validatedPath, new String(entry.getValue(), StandardCharsets.UTF_8));
    }

    AgentSkill skill = AgentSkill.builder()
        .name(name)
        .description(meta.description())
        .skillContent(skillMdContent)
        .resources(resources)
        .build();
    repo.save(List.of(skill), false);
    return new SkillDto(name, meta.description());
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    String name = validateSkillName(skillName);
    WorkspaceSkillRepository repo = repoFor(user);
    if (!repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    repo.delete(name);
  }

  private WorkspaceSkillRepository repoFor(CurrentUser user) {
    return new WorkspaceSkillRepository(filesystem, SKILLS_DIR, () -> runtimeContext(user));
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder()
        .userId(user.id().toString())
        .sessionId("workspace-api")
        .build();
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
}
