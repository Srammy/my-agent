package com.example.myagent.evolution;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.myagent.auth.CurrentUser;
import com.example.myagent.memory.UserMemoryEntity;
import com.example.myagent.memory.UserMemoryMapper;
import com.example.myagent.skill.SkillCreateRequest;
import com.example.myagent.skill.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EvolutionService {

  private final EvolutionProposalMapper proposalMapper;
  private final SkillService skillService;
  private final UserMemoryMapper userMemoryMapper;
  private final ObjectMapper objectMapper;

  public EvolutionService(
      EvolutionProposalMapper proposalMapper,
      SkillService skillService,
      UserMemoryMapper userMemoryMapper,
      ObjectMapper objectMapper) {
    this.proposalMapper = proposalMapper;
    this.skillService = skillService;
    this.userMemoryMapper = userMemoryMapper;
    this.objectMapper = objectMapper;
  }

  public List<EvolutionProposalDto> listProposals(CurrentUser currentUser) {
    return proposalMapper
        .selectList(
            Wrappers.<EvolutionProposalEntity>lambdaQuery()
                .eq(EvolutionProposalEntity::getUserId, currentUser.id())
                .orderByDesc(EvolutionProposalEntity::getUpdatedAt)
                .orderByDesc(EvolutionProposalEntity::getId))
        .stream()
        .map(EvolutionProposalDto::fromEntity)
        .toList();
  }

  @Transactional
  public EvolutionProposalDto createProposal(CurrentUser currentUser, EvolutionCreateRequest request) {
    if (request == null || request.type() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposal type is required");
    }
    if (!StringUtils.hasText(request.title())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposal title is required");
    }
    if (!StringUtils.hasText(request.content())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposal content is required");
    }

    LocalDateTime now = LocalDateTime.now();
    EvolutionProposalEntity proposal = new EvolutionProposalEntity();
    proposal.setUserId(currentUser.id());
    proposal.setSessionId(request.sessionId());
    proposal.setType(request.type());
    proposal.setTitle(request.title().trim());
    proposal.setSummary(request.summary());
    proposal.setContent(request.content());
    proposal.setStatus(EvolutionProposalStatus.DRAFT);
    proposal.setCreatedAt(now);
    proposal.setUpdatedAt(now);
    proposalMapper.insert(proposal);
    return EvolutionProposalDto.fromEntity(proposal);
  }

  @Transactional
  public EvolutionProposalDto approve(CurrentUser currentUser, Long id) {
    EvolutionProposalEntity proposal = requireOwnedProposal(currentUser, id);
    requireStatus(proposal, EvolutionProposalStatus.DRAFT);
    proposal.setStatus(EvolutionProposalStatus.APPROVED);
    proposal.setUpdatedAt(LocalDateTime.now());
    proposalMapper.updateById(proposal);
    return EvolutionProposalDto.fromEntity(proposal);
  }

  @Transactional
  public EvolutionProposalDto reject(CurrentUser currentUser, Long id) {
    EvolutionProposalEntity proposal = requireOwnedProposal(currentUser, id);
    requireStatus(proposal, EvolutionProposalStatus.DRAFT);
    proposal.setStatus(EvolutionProposalStatus.REJECTED);
    proposal.setUpdatedAt(LocalDateTime.now());
    proposalMapper.updateById(proposal);
    return EvolutionProposalDto.fromEntity(proposal);
  }

  @Transactional
  public EvolutionProposalDto apply(CurrentUser currentUser, Long id) {
    EvolutionProposalEntity proposal = requireOwnedProposal(currentUser, id);
    requireStatus(proposal, EvolutionProposalStatus.APPROVED);

    switch (proposal.getType()) {
      case SKILL -> applySkill(currentUser, proposal);
      case MEMORY -> applyMemory(currentUser, proposal);
      case TOOL_POLICY -> applyToolPolicy(proposal);
      case PROMPT, CODE_PATCH -> requireAdmin(currentUser);
    }

    LocalDateTime now = LocalDateTime.now();
    proposal.setStatus(EvolutionProposalStatus.APPLIED);
    proposal.setUpdatedAt(now);
    proposal.setAppliedAt(now);
    proposalMapper.updateById(proposal);
    return EvolutionProposalDto.fromEntity(proposal);
  }

  private EvolutionProposalEntity requireOwnedProposal(CurrentUser currentUser, Long id) {
    EvolutionProposalEntity proposal = proposalMapper.selectById(id);
    if (proposal == null || !currentUser.id().equals(proposal.getUserId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Evolution proposal not found");
    }
    return proposal;
  }

  private static void requireStatus(
      EvolutionProposalEntity proposal, EvolutionProposalStatus expectedStatus) {
    if (proposal.getStatus() != expectedStatus) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid proposal status transition");
    }
  }

  private void applySkill(CurrentUser currentUser, EvolutionProposalEntity proposal) {
    JsonNode content = readJsonObject(proposal.getContent());
    String name = textField(content, "name");
    String description = textField(content, "description");
    if (content.hasNonNull("skillId")) {
      skillService.updateMySkill(
          currentUser, content.get("skillId").asLong(), new SkillCreateRequest(name, description));
      return;
    }
    skillService.createMySkill(currentUser, new SkillCreateRequest(name, description));
  }

  private void applyMemory(CurrentUser currentUser, EvolutionProposalEntity proposal) {
    JsonNode content = tryReadJsonObject(proposal.getContent());
    LocalDate memoryDate = LocalDate.now();
    String memoryContent = proposal.getContent();
    if (content != null) {
      if (content.hasNonNull("date")) {
        memoryDate = LocalDate.parse(content.get("date").asText());
      }
      if (content.hasNonNull("content")) {
        memoryContent = content.get("content").asText();
      }
    }

    UserMemoryEntity memory = new UserMemoryEntity();
    memory.setUserId(currentUser.id());
    memory.setMemoryDate(memoryDate);
    memory.setContent(memoryContent);
    memory.setUpdatedAt(LocalDateTime.now());
    userMemoryMapper.insert(memory);
  }

  private void applyToolPolicy(EvolutionProposalEntity proposal) {
    String risk = textField(readJsonObject(proposal.getContent()), "risk");
    if (!"LOW".equalsIgnoreCase(risk)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only LOW risk tool policy can be applied");
    }
  }

  private static void requireAdmin(CurrentUser currentUser) {
    if (!"ADMIN".equalsIgnoreCase(currentUser.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role is required");
    }
  }

  private JsonNode readJsonObject(String content) {
    JsonNode node = tryReadJsonObject(content);
    if (node == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposal content must be a JSON object");
    }
    return node;
  }

  private JsonNode tryReadJsonObject(String content) {
    try {
      JsonNode node = objectMapper.readTree(content);
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String textField(JsonNode content, String fieldName) {
    JsonNode field = content.get(fieldName);
    if (field == null || field.isNull() || !StringUtils.hasText(field.asText())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return field.asText();
  }
}
