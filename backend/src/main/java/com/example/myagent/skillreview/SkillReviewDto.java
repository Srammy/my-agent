package com.example.myagent.skillreview;

import java.util.List;

public record SkillReviewDto(
    String skillName,
    String description,
    String status,
    String createdBy,
    String sourceSessionId,
    List<String> environments,
    long useCount,
    long viewCount,
    long patchCount) {}
