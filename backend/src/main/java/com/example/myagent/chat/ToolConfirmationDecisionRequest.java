package com.example.myagent.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ToolConfirmationDecisionRequest(
    @NotBlank String toolCallId,
    @NotNull Boolean confirmed) {}
