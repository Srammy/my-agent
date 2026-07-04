package com.example.myagent.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

  @Test
  void constructorRejectsMissingSecret() {
    assertThatThrownBy(() -> new JwtService(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("security.jwt.secret");
  }

  @Test
  void constructorRejectsTooShortSecret() {
    assertThatThrownBy(() -> new JwtService("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes");
  }
}
