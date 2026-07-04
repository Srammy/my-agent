package com.example.myagent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

  private static final CurrentUser USER = new CurrentUser(7L, "alice", "USER");

  @Mock private SkillMapper skillMapper;
  @Mock private SkillFileMapper skillFileMapper;
  @Mock private UserSkillSettingMapper userSkillSettingMapper;

  private SkillService skillService;

  @BeforeEach
  void setUp() {
    skillService = new SkillService(skillMapper, skillFileMapper, userSkillSettingMapper);
  }

  @Test
  void createMySkillUsesCurrentUserAndCreatesSkillMarkdownFile() {
    when(skillMapper.selectCount(any())).thenReturn(0L);
    when(skillMapper.insert(any(SkillEntity.class)))
        .thenAnswer(
            invocation -> {
              SkillEntity entity = invocation.getArgument(0);
              entity.setId(42L);
              return 1;
            });
    when(skillFileMapper.insert(any(SkillFileEntity.class))).thenReturn(1);

    SkillDto created =
        skillService.createMySkill(USER, new SkillCreateRequest("mysql-helper", "Useful helper"));

    ArgumentCaptor<SkillEntity> skillCaptor = ArgumentCaptor.forClass(SkillEntity.class);
    verify(skillMapper).insert(skillCaptor.capture());
    SkillEntity savedSkill = skillCaptor.getValue();
    assertThat(savedSkill.getOwnerType()).isEqualTo(SkillService.OWNER_TYPE_USER);
    assertThat(savedSkill.getOwnerUserId()).isEqualTo(USER.id());
    assertThat(savedSkill.getName()).isEqualTo("mysql-helper");
    assertThat(savedSkill.getDescription()).isEqualTo("Useful helper");
    assertThat(savedSkill.getEnabled()).isTrue();

    ArgumentCaptor<SkillFileEntity> fileCaptor = ArgumentCaptor.forClass(SkillFileEntity.class);
    verify(skillFileMapper).insert(fileCaptor.capture());
    SkillFileEntity savedFile = fileCaptor.getValue();
    assertThat(savedFile.getSkillId()).isEqualTo(42L);
    assertThat(savedFile.getPath()).isEqualTo("SKILL.md");
    assertThat(savedFile.getContent()).contains("name: \"mysql-helper\"");
    assertThat(savedFile.getContent()).contains("description: \"Useful helper\"");

    assertThat(created.id()).isEqualTo(42L);
    assertThat(created.name()).isEqualTo("mysql-helper");
    assertThat(created.description()).isEqualTo("Useful helper");
    assertThat(created.ownerType()).isEqualTo(SkillService.OWNER_TYPE_USER);
    assertThat(created.enabled()).isTrue();
  }

  @Test
  void listSystemSkillsAppliesCurrentUsersEnablementOverride() {
    SkillEntity systemSkill = new SkillEntity();
    systemSkill.setId(10L);
    systemSkill.setOwnerType(SkillService.OWNER_TYPE_SYSTEM);
    systemSkill.setName("shared");
    systemSkill.setDescription("Shared skill");
    systemSkill.setEnabled(true);

    UserSkillSettingEntity setting = new UserSkillSettingEntity();
    setting.setSkillId(10L);
    setting.setUserId(USER.id());
    setting.setEnabled(false);

    when(skillMapper.selectList(any())).thenReturn(List.of(systemSkill));
    when(userSkillSettingMapper.selectList(any())).thenReturn(List.of(setting));

    List<SkillDto> skills = skillService.listSystemSkills(USER);

    assertThat(skills).singleElement().satisfies(skill -> assertThat(skill.enabled()).isFalse());
  }

  @Test
  void upsertSkillMarkdownUpdatesSkillMetadataForOwnedSkill() {
    SkillEntity ownedSkill = new SkillEntity();
    ownedSkill.setId(42L);
    ownedSkill.setOwnerType(SkillService.OWNER_TYPE_USER);
    ownedSkill.setOwnerUserId(USER.id());
    ownedSkill.setName("old-name");
    ownedSkill.setDescription("Old description");
    ownedSkill.setEnabled(true);

    when(skillMapper.selectById(42L)).thenReturn(ownedSkill);
    when(skillFileMapper.selectOne(any())).thenReturn(null);
    when(skillMapper.updateById(any(SkillEntity.class))).thenReturn(1);
    when(skillFileMapper.insert(any(SkillFileEntity.class))).thenReturn(1);

    SkillFileDto file =
        skillService.upsertFile(
            USER,
            42L,
            "SKILL.md",
            """
            ---
            name: fresh-name
            description: Fresh description
            ---

            body
            """);

    ArgumentCaptor<SkillEntity> skillCaptor = ArgumentCaptor.forClass(SkillEntity.class);
    verify(skillMapper).updateById(skillCaptor.capture());
    SkillEntity updatedSkill = skillCaptor.getValue();
    assertThat(updatedSkill.getId()).isEqualTo(42L);
    assertThat(updatedSkill.getName()).isEqualTo("fresh-name");
    assertThat(updatedSkill.getDescription()).isEqualTo("Fresh description");

    assertThat(file.path()).isEqualTo("SKILL.md");
    assertThat(file.content()).contains("fresh-name");
  }

  @Test
  void upsertFileRejectsSystemSkillModification() {
    SkillEntity systemSkill = new SkillEntity();
    systemSkill.setId(8L);
    systemSkill.setOwnerType(SkillService.OWNER_TYPE_SYSTEM);
    systemSkill.setName("shared");
    systemSkill.setDescription("Shared");
    systemSkill.setEnabled(true);

    when(skillMapper.selectById(8L)).thenReturn(systemSkill);

    assertThatThrownBy(() -> skillService.upsertFile(USER, 8L, "scripts/run.sh", "echo hi"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }
}
