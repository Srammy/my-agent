package com.example.myagent.skill;

import java.time.LocalDateTime;

public record SkillFileDto(
    String path, String content, String contentType, boolean executable, LocalDateTime updatedAt) {}
