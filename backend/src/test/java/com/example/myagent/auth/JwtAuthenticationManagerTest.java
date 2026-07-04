package com.example.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.myagent.user.UserEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.test.StepVerifier;

class JwtAuthenticationManagerTest {

  private static final String VALID_SECRET = "01234567890123456789012345678901";

  @Test
  void authenticateRejectsMalformedJwtAsAuthenticationFailure() {
    JwtAuthenticationManager authenticationManager =
        new JwtAuthenticationManager(new JwtService(VALID_SECRET));

    StepVerifier.create(
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(null, "not-a-jwt")))
        .expectErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(BadCredentialsException.class);
              assertThat(error).hasMessageContaining("Invalid JWT");
            })
        .verify();
  }

  @Test
  void authenticateRejectsTamperedJwtAsAuthenticationFailure() {
    JwtService jwtService = new JwtService(VALID_SECRET);
    JwtAuthenticationManager authenticationManager = new JwtAuthenticationManager(jwtService);
    UserEntity user =
        new UserEntity(
            42L,
            "alice",
            "hash",
            "Alice",
            "USER",
            LocalDateTime.now(),
            LocalDateTime.now());
    String token = jwtService.createToken(user);
    String tamperedToken = token.substring(0, token.length() - 1) + "x";

    StepVerifier.create(
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(null, tamperedToken)))
        .expectErrorSatisfies(
            error -> {
              assertThat(error).isInstanceOf(BadCredentialsException.class);
              assertThat(error).hasMessageContaining("Invalid JWT");
            })
        .verify();
  }
}
