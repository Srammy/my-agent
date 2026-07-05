package com.example.myagent.permission;

import jakarta.validation.constraints.NotNull;

public record PermissionModeDto(@NotNull PermissionMode mode) {}
