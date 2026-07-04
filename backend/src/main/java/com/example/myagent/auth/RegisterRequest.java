package com.example.myagent.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(max = 64) String username,
    @NotBlank @Size(min = 8, max = 72) String password,
    @Size(max = 64) String displayName) {}
