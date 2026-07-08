package com.example.myagent.skillreview;

import java.util.List;

public record ApproveSkillReviewRequest(String reviewerId, List<String> environments) {}
