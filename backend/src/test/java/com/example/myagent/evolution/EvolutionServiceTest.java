package com.example.myagent.evolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.skill.SkillCreateRequest;
import com.example.myagent.skill.SkillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EvolutionServiceTest {

  private static final CurrentUser USER = new CurrentUser(7L, "alice", "USER");
  private static final CurrentUser OTHER_USER = new CurrentUser(8L, "bob", "USER");
  private static final CurrentUser ADMIN = new CurrentUser(1L, "root", "ADMIN");

  @Mock private EvolutionProposalMapper proposalMapper;
  @Mock private SkillService skillService;

  private EvolutionService evolutionService;

  @BeforeEach
  void setUp() {
    evolutionService = new EvolutionService(proposalMapper, skillService, new ObjectMapper());
  }

  @Test
  void createProposalCreatesDraftForCurrentUser() {
    when(proposalMapper.insert(any(EvolutionProposalEntity.class)))
        .thenAnswer(
            invocation -> {
              EvolutionProposalEntity entity = invocation.getArgument(0);
              entity.setId(42L);
              return 1;
            });

    EvolutionProposalDto created =
        evolutionService.createProposal(
            USER,
            new EvolutionCreateRequest(
                "s_1", EvolutionProposalType.SKILL, "Add helper", "summary", "{}"));

    ArgumentCaptor<EvolutionProposalEntity> captor =
        ArgumentCaptor.forClass(EvolutionProposalEntity.class);
    verify(proposalMapper).insert(captor.capture());
    EvolutionProposalEntity saved = captor.getValue();
    assertThat(saved.getUserId()).isEqualTo(USER.id());
    assertThat(saved.getSessionId()).isEqualTo("s_1");
    assertThat(saved.getType()).isEqualTo(EvolutionProposalType.SKILL);
    assertThat(saved.getStatus()).isEqualTo(EvolutionProposalStatus.DRAFT);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    assertThat(saved.getAppliedAt()).isNull();
    assertThat(created.id()).isEqualTo(42L);
    assertThat(created.status()).isEqualTo(EvolutionProposalStatus.DRAFT);
  }

  @Test
  void createProposalRejectsMemoryProposalType() {
    assertThatThrownBy(
            () ->
                evolutionService.createProposal(
                    USER,
                    new EvolutionCreateRequest(
                        "s_1", EvolutionProposalType.MEMORY, "Add memory", "summary", "{}")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error -> {
              ResponseStatusException exception = (ResponseStatusException) error;
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getReason()).contains("AgentScope Harness");
            });
  }

  @Test
  void approveMovesDraftToApproved() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.DRAFT);
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    EvolutionProposalDto approved = evolutionService.approve(USER, 10L);

    assertThat(approved.status()).isEqualTo(EvolutionProposalStatus.APPROVED);
    verify(proposalMapper).updateById(proposal);
  }

  @Test
  void rejectMovesDraftToRejected() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.DRAFT);
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    EvolutionProposalDto rejected = evolutionService.reject(USER, 10L);

    assertThat(rejected.status()).isEqualTo(EvolutionProposalStatus.REJECTED);
    verify(proposalMapper).updateById(proposal);
  }

  @Test
  void applyMovesApprovedToApplied() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.APPROVED);
    proposal.setType(EvolutionProposalType.TOOL_POLICY);
    proposal.setContent("{\"risk\":\"LOW\"}");
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    EvolutionProposalDto applied = evolutionService.apply(USER, 10L);

    assertThat(applied.status()).isEqualTo(EvolutionProposalStatus.APPLIED);
    assertThat(proposal.getAppliedAt()).isNotNull();
    verify(proposalMapper).updateById(proposal);
  }

  @Test
  void applyRejectsRejectedProposal() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.REJECTED);
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    assertThatThrownBy(() -> evolutionService.apply(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void rejectRejectsAppliedProposal() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.APPLIED);
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    assertThatThrownBy(() -> evolutionService.reject(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void otherUsersProposalReturnsNotFound() {
    EvolutionProposalEntity proposal = proposal(10L, OTHER_USER.id(), EvolutionProposalStatus.DRAFT);
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    assertThatThrownBy(() -> evolutionService.approve(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void nonAdminCannotApplyPromptOrCodePatch() {
    EvolutionProposalEntity prompt = proposal(10L, USER.id(), EvolutionProposalStatus.APPROVED);
    prompt.setType(EvolutionProposalType.PROMPT);
    when(proposalMapper.selectById(10L)).thenReturn(prompt);

    assertThatThrownBy(() -> evolutionService.apply(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void adminCanApplyCodePatchWithoutChangingCode() {
    EvolutionProposalEntity patch = proposal(10L, ADMIN.id(), EvolutionProposalStatus.APPROVED);
    patch.setType(EvolutionProposalType.CODE_PATCH);
    when(proposalMapper.selectById(10L)).thenReturn(patch);

    EvolutionProposalDto applied = evolutionService.apply(ADMIN, 10L);

    assertThat(applied.status()).isEqualTo(EvolutionProposalStatus.APPLIED);
    verify(proposalMapper).updateById(patch);
  }

  @Test
  void toolPolicyRejectsNonLowRiskAndAppliesLowRisk() {
    EvolutionProposalEntity highRisk = proposal(10L, USER.id(), EvolutionProposalStatus.APPROVED);
    highRisk.setType(EvolutionProposalType.TOOL_POLICY);
    highRisk.setContent("{\"risk\":\"HIGH\"}");
    when(proposalMapper.selectById(10L)).thenReturn(highRisk);

    assertThatThrownBy(() -> evolutionService.apply(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));

    EvolutionProposalEntity lowRisk = proposal(11L, USER.id(), EvolutionProposalStatus.APPROVED);
    lowRisk.setType(EvolutionProposalType.TOOL_POLICY);
    lowRisk.setContent("{\"risk\":\"low\"}");
    when(proposalMapper.selectById(11L)).thenReturn(lowRisk);

    EvolutionProposalDto applied = evolutionService.apply(USER, 11L);

    assertThat(applied.status()).isEqualTo(EvolutionProposalStatus.APPLIED);
  }

  @Test
  void applySkillCreatesOrUpdatesUserSkill() {
    EvolutionProposalEntity create = proposal(10L, USER.id(), EvolutionProposalStatus.APPROVED);
    create.setType(EvolutionProposalType.SKILL);
    create.setContent("{\"name\":\"helper\",\"description\":\"Useful\"}");
    when(proposalMapper.selectById(10L)).thenReturn(create);

    evolutionService.apply(USER, 10L);

    verify(skillService).createMySkill(USER, new SkillCreateRequest("helper", "Useful"));

    EvolutionProposalEntity update = proposal(11L, USER.id(), EvolutionProposalStatus.APPROVED);
    update.setType(EvolutionProposalType.SKILL);
    update.setContent("{\"skillId\":99,\"name\":\"helper2\",\"description\":\"Better\"}");
    when(proposalMapper.selectById(11L)).thenReturn(update);

    evolutionService.apply(USER, 11L);

    verify(skillService).updateMySkill(USER, 99L, new SkillCreateRequest("helper2", "Better"));
  }

  @Test
  void applyMemoryRejectsUnsupportedProposalType() {
    EvolutionProposalEntity proposal = proposal(10L, USER.id(), EvolutionProposalStatus.APPROVED);
    proposal.setType(EvolutionProposalType.MEMORY);
    proposal.setContent("{\"date\":\"2026-07-05\",\"content\":\"Remember this\"}");
    when(proposalMapper.selectById(10L)).thenReturn(proposal);

    assertThatThrownBy(() -> evolutionService.apply(USER, 10L))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  private static EvolutionProposalEntity proposal(
      Long id, Long userId, EvolutionProposalStatus status) {
    EvolutionProposalEntity proposal = new EvolutionProposalEntity();
    proposal.setId(id);
    proposal.setUserId(userId);
    proposal.setType(EvolutionProposalType.MEMORY);
    proposal.setTitle("Title");
    proposal.setSummary("Summary");
    proposal.setContent("Content");
    proposal.setStatus(status);
    proposal.setCreatedAt(LocalDateTime.now());
    proposal.setUpdatedAt(LocalDateTime.now());
    return proposal;
  }
}
