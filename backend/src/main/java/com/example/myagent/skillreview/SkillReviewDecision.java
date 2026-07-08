package com.example.myagent.skillreview;

import java.time.Instant;
import java.util.List;

public record SkillReviewDecision(
    String skillName,
    String status,
    String reviewerId,
    String reason,
    List<String> environments,
    Instant decidedAt) {}
