package com.example.myagent.skill;

import com.example.myagent.auth.CurrentUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentScopeWorkspaceService {

  private static final String SKILLS_DIR = "skills";
  private static final String WORKSPACE_SOURCE = "workspace";
  static final int MAX_FILE_COUNT = 32;
  static final int MAX_FILE_SIZE = 1024 * 1024;
  private static final long MAX_TOTAL_SIZE = 5L * 1024 * 1024;

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

    String skillMdContent = decodeSkillMd(skillMdBytes);
    // Validate frontmatter for HTTP-friendly errors before passing to SkillUtil
    SkillValidator.SkillMarkdownMetadata meta = SkillValidator.validateSkillMarkdown(skillMdContent);
    String name = validateSkillName(meta.name());

    WorkspaceSkillRepository repo = repoFor(user);
    if (repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists: " + name);
    }

    List<Map.Entry<String, byte[]>> resources = new ArrayList<>();
    for (Map.Entry<String, byte[]> entry : files.entrySet()) {
      if ("SKILL.md".equals(entry.getKey())) {
        continue;
      }
      String validatedPath = validateFilePath(entry.getKey());
      if ("SKILL.md".equals(validatedPath)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md must use the exact file name");
      }
      resources.add(Map.entry(skillPath(name, validatedPath), entry.getValue()));
    }

    AgentSkill skill;
    try {
      skill = SkillUtil.createFrom(skillMdContent, Map.of(), WORKSPACE_SOURCE);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    RuntimeContext context = runtimeContext(user);
    requireSuccessfulUploads(resources, filesystem.uploadFiles(context, resources));
    List<Map.Entry<String, byte[]>> marker = List.of(Map.entry(skillPath(name, "SKILL.md"), skillMdBytes));
    requireSuccessfulUploads(marker, filesystem.uploadFiles(context, marker));
    return new SkillDto(skill.getName(), skill.getDescription());
  }

  public void deleteSkill(CurrentUser user, String skillName) {
    String name = validateSkillName(skillName);
    WorkspaceSkillRepository repo = repoFor(user);
    if (!repo.skillExists(name)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    repo.delete(name);
  }

  /**
   * Creates a writable WorkspaceSkillRepository scoped to the given user.
   * The 4-arg constructor sets writable=true, enabling save() and delete().
   * User isolation is provided by the workspaceFilesystem bean's NamespaceFactory,
   * which prefixes all paths with the userId from RuntimeContext.
   */
  private WorkspaceSkillRepository repoFor(CurrentUser user) {
    return new WorkspaceSkillRepository(
        filesystem, SKILLS_DIR, () -> runtimeContext(user), WORKSPACE_SOURCE);
  }

  private RuntimeContext runtimeContext(CurrentUser user) {
    return RuntimeContext.builder()
        .userId(user.id().toString())
        .sessionId("workspace-api")
        .build();
  }

  private static Map<String, byte[]> collectParts(List<Part> parts) {
    Map<String, byte[]> result = new LinkedHashMap<>();
    int fileCount = 0;
    long totalSize = 0;
    for (Part part : parts) {
      if (part instanceof FilePart filePart) {
        String filename = filePart.filename();
        if (StringUtils.hasText(filename)) {
          if (++fileCount > MAX_FILE_COUNT) {
            throw payloadTooLarge("Skill upload contains too many files");
          }
          byte[] content = readFile(filePart);
          totalSize += content.length;
          if (totalSize > MAX_TOTAL_SIZE) {
            throw payloadTooLarge("Skill upload is too large");
          }
          result.put(filename, content);
        }
      }
    }
    return result;
  }

  private static byte[] readFile(FilePart filePart) {
    DataBuffer joined;
    try {
      joined = DataBufferUtils.join(filePart.content(), MAX_FILE_SIZE).block();
    } catch (DataBufferLimitException e) {
      throw payloadTooLarge("Skill file is too large");
    }
    if (joined == null) {
      return new byte[0];
    }
    try {
      byte[] content = new byte[joined.readableByteCount()];
      joined.read(content);
      return content;
    } finally {
      DataBufferUtils.release(joined);
    }
  }

  private static String decodeSkillMd(byte[] skillMdBytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(skillMdBytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md must be valid UTF-8");
    }
  }

  private static String skillPath(String skillName, String relativePath) {
    return SKILLS_DIR + "/" + skillName + "/" + relativePath;
  }

  private static void requireSuccessfulUploads(
      List<Map.Entry<String, byte[]>> files, List<FileUploadResponse> responses) {
    if (responses == null || responses.size() != files.size()) {
      throw uploadFailed();
    }
    for (int index = 0; index < files.size(); index++) {
      FileUploadResponse response = responses.get(index);
      if (response == null
          || !response.isSuccess()
          || !files.get(index).getKey().equals(response.path())) {
        throw uploadFailed();
      }
    }
  }

  private static ResponseStatusException uploadFailed() {
    return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload skill files");
  }

  private static ResponseStatusException payloadTooLarge(String reason) {
    return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, reason);
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
