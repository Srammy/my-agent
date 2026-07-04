package com.example.myagent.skill;

import java.time.LocalDateTime;

public record SkillDto(
    Long id,
    String name,
    String description,
    String ownerType,
    boolean enabled,
    boolean editable,
    LocalDateTime updatedAt) {}
