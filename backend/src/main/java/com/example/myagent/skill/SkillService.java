package com.example.myagent.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.myagent.auth.CurrentUser;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SkillService {

  public static final String OWNER_TYPE_SYSTEM = "SYSTEM";
  public static final String OWNER_TYPE_USER = "USER";
  private static final int NAME_MAX_LENGTH = 100;
  private static final int DESCRIPTION_MAX_LENGTH = 255;

  private final SkillMapper skillMapper;
  private final SkillFileMapper skillFileMapper;
  private final UserSkillSettingMapper userSkillSettingMapper;

  public SkillService(
      SkillMapper skillMapper,
      SkillFileMapper skillFileMapper,
      UserSkillSettingMapper userSkillSettingMapper) {
    this.skillMapper = skillMapper;
    this.skillFileMapper = skillFileMapper;
    this.userSkillSettingMapper = userSkillSettingMapper;
  }

  public List<SkillDto> listSystemSkills(CurrentUser currentUser) {
    List<SkillEntity> skills =
        skillMapper.selectList(
            Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getOwnerType, OWNER_TYPE_SYSTEM)
                .orderByAsc(SkillEntity::getName));
    Map<Long, UserSkillSettingEntity> settingsBySkillId = loadSettingsBySkillId(currentUser, skills);
    return skills.stream()
        .map(
            skill ->
                toDto(
                    skill,
                    isSystemSkillEnabled(skill, settingsBySkillId.get(skill.getId())),
                    false))
        .toList();
  }

  public List<SkillDto> listMySkills(CurrentUser currentUser) {
    return skillMapper.selectList(userSkillsQuery(currentUser.id())).stream()
        .map(skill -> toDto(skill, Boolean.TRUE.equals(skill.getEnabled()), true))
        .toList();
  }

  @Transactional
  public SkillDto createMySkill(CurrentUser currentUser, SkillCreateRequest request) {
    String name = normalizeName(request.name());
    String description = normalizeDescription(request.description());
    ensureNameAvailable(currentUser.id(), name);

    LocalDateTime now = LocalDateTime.now();
    SkillEntity skill = new SkillEntity();
    skill.setOwnerType(OWNER_TYPE_USER);
    skill.setOwnerUserId(currentUser.id());
    skill.setName(name);
    skill.setDescription(description);
    skill.setEnabled(true);
    skill.setCreatedAt(now);
    skill.setUpdatedAt(now);
    skillMapper.insert(skill);

    SkillFileEntity file = new SkillFileEntity();
    file.setSkillId(skill.getId());
    file.setPath("SKILL.md");
    file.setContent(buildSkillMarkdown(name, description, ""));
    file.setContentType("text/markdown");
    file.setExecutable(false);
    file.setCreatedAt(now);
    file.setUpdatedAt(now);
    skillFileMapper.insert(file);

    return toDto(skill, true, true);
  }

  @Transactional
  public SkillDto updateMySkill(CurrentUser currentUser, Long skillId, SkillCreateRequest request) {
    SkillEntity skill = requireOwnedUserSkill(currentUser, skillId);
    String name = normalizeName(request.name());
    String description = normalizeDescription(request.description());
    if (!skill.getName().equals(name)) {
      ensureNameAvailable(currentUser.id(), name);
    }

    skill.setName(name);
    skill.setDescription(description);
    skill.setUpdatedAt(LocalDateTime.now());
    skillMapper.updateById(skill);

    SkillFileEntity skillMarkdown = findSkillFile(skillId, "SKILL.md");
    String body = skillMarkdown == null ? "" : extractMarkdownBody(skillMarkdown.getContent());
    upsertSkillFileEntity(skillId, "SKILL.md", buildSkillMarkdown(name, description, body));
    return toDto(skill, Boolean.TRUE.equals(skill.getEnabled()), true);
  }

  @Transactional
  public void deleteMySkill(CurrentUser currentUser, Long skillId) {
    requireOwnedUserSkill(currentUser, skillId);
    skillFileMapper.delete(
        Wrappers.<SkillFileEntity>lambdaQuery().eq(SkillFileEntity::getSkillId, skillId));
    skillMapper.deleteById(skillId);
  }

  public List<SkillFileDto> listFiles(CurrentUser currentUser, Long skillId) {
    requireReadableSkill(currentUser, skillId);
    return skillFileMapper.selectList(filesBySkillIdQuery(skillId)).stream()
        .map(SkillService::toFileDto)
        .toList();
  }

  @Transactional
  public SkillFileDto upsertFile(CurrentUser currentUser, Long skillId, String path, String content) {
    SkillEntity skill = requireOwnedUserSkill(currentUser, skillId);
    String normalizedPath = SkillValidator.validatePath(path);
    if (content == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill file content is required");
    }

    if ("SKILL.md".equals(normalizedPath)) {
      SkillValidator.SkillMarkdownMetadata metadata = SkillValidator.validateSkillMarkdown(content);
      String name = normalizeName(metadata.name());
      String description = normalizeDescription(metadata.description());
      if (!skill.getName().equals(name)) {
        ensureNameAvailable(currentUser.id(), name);
      }

      SkillEntity updatedSkill = new SkillEntity();
      updatedSkill.setId(skillId);
      updatedSkill.setName(name);
      updatedSkill.setDescription(description);
      updatedSkill.setUpdatedAt(LocalDateTime.now());
      skillMapper.updateById(updatedSkill);
      skill.setName(name);
      skill.setDescription(description);
    }

    SkillFileEntity file = upsertSkillFileEntity(skillId, normalizedPath, content);
    return toFileDto(file);
  }

  @Transactional
  public void deleteFile(CurrentUser currentUser, Long skillId, String path) {
    requireOwnedUserSkill(currentUser, skillId);
    String normalizedPath = SkillValidator.validatePath(path);
    if ("SKILL.md".equals(normalizedPath)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKILL.md cannot be deleted");
    }
    skillFileMapper.delete(
        Wrappers.<SkillFileEntity>lambdaQuery()
            .eq(SkillFileEntity::getSkillId, skillId)
            .eq(SkillFileEntity::getPath, normalizedPath));
  }

  @Transactional
  public SkillDto setEnabled(CurrentUser currentUser, Long skillId, boolean enabled) {
    SkillEntity skill = skillMapper.selectById(skillId);
    if (skill == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }

    if (OWNER_TYPE_SYSTEM.equals(skill.getOwnerType())) {
      UserSkillSettingEntity setting =
          userSkillSettingMapper.selectOne(
              Wrappers.<UserSkillSettingEntity>lambdaQuery()
                  .eq(UserSkillSettingEntity::getUserId, currentUser.id())
                  .eq(UserSkillSettingEntity::getSkillId, skillId));
      if (setting == null) {
        setting = new UserSkillSettingEntity();
        setting.setUserId(currentUser.id());
        setting.setSkillId(skillId);
        setting.setEnabled(enabled);
        userSkillSettingMapper.insert(setting);
      } else {
        setting.setEnabled(enabled);
        userSkillSettingMapper.updateById(setting);
      }
      return toDto(skill, Boolean.TRUE.equals(skill.getEnabled()) && enabled, false);
    }

    SkillEntity ownedSkill = requireOwnedUserSkill(currentUser, skillId);
    ownedSkill.setEnabled(enabled);
    ownedSkill.setUpdatedAt(LocalDateTime.now());
    skillMapper.updateById(ownedSkill);
    return toDto(ownedSkill, enabled, true);
  }

  private SkillEntity requireOwnedUserSkill(CurrentUser currentUser, Long skillId) {
    SkillEntity skill = skillMapper.selectById(skillId);
    if (skill == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    if (OWNER_TYPE_SYSTEM.equals(skill.getOwnerType())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System skill is read-only");
    }
    if (!OWNER_TYPE_USER.equals(skill.getOwnerType())
        || !currentUser.id().equals(skill.getOwnerUserId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    return skill;
  }

  private SkillEntity requireReadableSkill(CurrentUser currentUser, Long skillId) {
    SkillEntity skill = skillMapper.selectById(skillId);
    if (skill == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
    }
    if (OWNER_TYPE_SYSTEM.equals(skill.getOwnerType())) {
      return skill;
    }
    if (OWNER_TYPE_USER.equals(skill.getOwnerType())
        && currentUser.id().equals(skill.getOwnerUserId())) {
      return skill;
    }
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
  }

  private Map<Long, UserSkillSettingEntity> loadSettingsBySkillId(
      CurrentUser currentUser, List<SkillEntity> skills) {
    Map<Long, UserSkillSettingEntity> settingsBySkillId = new HashMap<>();
    if (skills.isEmpty()) {
      return settingsBySkillId;
    }
    List<Long> skillIds = skills.stream().map(SkillEntity::getId).toList();
    userSkillSettingMapper
        .selectList(
            Wrappers.<UserSkillSettingEntity>lambdaQuery()
                .eq(UserSkillSettingEntity::getUserId, currentUser.id())
                .in(UserSkillSettingEntity::getSkillId, skillIds))
        .forEach(setting -> settingsBySkillId.put(setting.getSkillId(), setting));
    return settingsBySkillId;
  }

  private boolean isSystemSkillEnabled(SkillEntity skill, UserSkillSettingEntity setting) {
    boolean globallyEnabled = Boolean.TRUE.equals(skill.getEnabled());
    boolean locallyEnabled = setting == null || Boolean.TRUE.equals(setting.getEnabled());
    return globallyEnabled && locallyEnabled;
  }

  private void ensureNameAvailable(Long ownerUserId, String name) {
    Long count =
        skillMapper.selectCount(
            Wrappers.<SkillEntity>lambdaQuery()
                .eq(SkillEntity::getOwnerType, OWNER_TYPE_USER)
                .eq(SkillEntity::getOwnerUserId, ownerUserId)
                .eq(SkillEntity::getName, name));
    if (count != null && count > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill name already exists");
    }
  }

  private SkillFileEntity upsertSkillFileEntity(Long skillId, String path, String content) {
    LocalDateTime now = LocalDateTime.now();
    SkillFileEntity existing = findSkillFile(skillId, path);
    if (existing == null) {
      SkillFileEntity created = new SkillFileEntity();
      created.setSkillId(skillId);
      created.setPath(path);
      created.setContent(content);
      created.setContentType(detectContentType(path));
      created.setExecutable(false);
      created.setCreatedAt(now);
      created.setUpdatedAt(now);
      skillFileMapper.insert(created);
      return created;
    }

    existing.setContent(content);
    existing.setContentType(detectContentType(path));
    existing.setExecutable(false);
    existing.setUpdatedAt(now);
    skillFileMapper.updateById(existing);
    return existing;
  }

  private SkillFileEntity findSkillFile(Long skillId, String path) {
    return skillFileMapper.selectOne(
        Wrappers.<SkillFileEntity>lambdaQuery()
            .eq(SkillFileEntity::getSkillId, skillId)
            .eq(SkillFileEntity::getPath, path));
  }

  private static SkillDto toDto(SkillEntity skill, boolean enabled, boolean editable) {
    return new SkillDto(
        skill.getId(),
        skill.getName(),
        skill.getDescription(),
        skill.getOwnerType(),
        enabled,
        editable,
        skill.getUpdatedAt());
  }

  private static SkillFileDto toFileDto(SkillFileEntity file) {
    return new SkillFileDto(
        file.getPath(),
        file.getContent(),
        file.getContentType(),
        Boolean.TRUE.equals(file.getExecutable()),
        file.getUpdatedAt());
  }

  private static LambdaQueryWrapper<SkillEntity> userSkillsQuery(Long userId) {
    return Wrappers.<SkillEntity>lambdaQuery()
        .eq(SkillEntity::getOwnerType, OWNER_TYPE_USER)
        .eq(SkillEntity::getOwnerUserId, userId)
        .orderByAsc(SkillEntity::getName);
  }

  private static LambdaQueryWrapper<SkillFileEntity> filesBySkillIdQuery(Long skillId) {
    return Wrappers.<SkillFileEntity>lambdaQuery()
        .eq(SkillFileEntity::getSkillId, skillId)
        .orderByAsc(SkillFileEntity::getPath);
  }

  private static String normalizeName(String rawName) {
    if (!StringUtils.hasText(rawName)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
    }
    String name = rawName.trim();
    if (name.length() > NAME_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is too long");
    }
    return name;
  }

  private static String normalizeDescription(String rawDescription) {
    if (!StringUtils.hasText(rawDescription)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill description is required");
    }
    String description = rawDescription.trim();
    if (description.length() > DESCRIPTION_MAX_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill description is too long");
    }
    return description;
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
    if (bodyStart >= content.length()) {
      return "";
    }
    return content.substring(bodyStart).strip();
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
