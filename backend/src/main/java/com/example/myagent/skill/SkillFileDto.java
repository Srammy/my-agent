package com.example.myagent.skill;

public record SkillFileDto(
    String path, String content, String contentType, boolean executable, String updatedAt) {}
