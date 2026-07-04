package com.example.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.user.UserEntity;
import com.example.myagent.user.UserMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private UserMapper userMapper;
  @Mock private JwtService jwtService;

  private BCryptPasswordEncoder passwordEncoder;
  private AuthService authService;

  @BeforeEach
  void setUp() {
    passwordEncoder = new BCryptPasswordEncoder();
    authService = new AuthService(userMapper, jwtService, passwordEncoder);
  }

  @Test
  void registerStoresBCryptHashInsteadOfPlaintextPassword() {
    RegisterRequest request = new RegisterRequest("alice", "plain-secret", "Alice");
    when(userMapper.findByUsername("alice")).thenReturn(null);
    when(jwtService.createToken(any(UserEntity.class))).thenReturn("jwt-token");

    AuthResponse response = authService.register(request);

    ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
    verify(userMapper).insert(captor.capture());
    UserEntity savedUser = captor.getValue();
    assertThat(savedUser.getUsername()).isEqualTo("alice");
    assertThat(savedUser.getDisplayName()).isEqualTo("Alice");
    assertThat(savedUser.getRole()).isEqualTo("USER");
    assertThat(savedUser.getPasswordHash()).isNotEqualTo("plain-secret");
    assertThat(passwordEncoder.matches("plain-secret", savedUser.getPasswordHash())).isTrue();
    assertThat(response.token()).isEqualTo("jwt-token");
  }

  @Test
  void registerRejectsDuplicateUsername() {
    when(userMapper.findByUsername("alice"))
        .thenReturn(
            new UserEntity(
                1L,
                "alice",
                "$2a$10$abcdefghijklmnopqrstuv",
                "Alice",
                "USER",
                LocalDateTime.now(),
                LocalDateTime.now()));

    assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", "secret123", "A")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    verify(userMapper, never()).insert(any(UserEntity.class));
  }

  @Test
  void loginReturnsJwtForCorrectCredentials() {
    String hashedPassword = passwordEncoder.encode("secret123");
    UserEntity existingUser =
        new UserEntity(
            7L,
            "alice",
            hashedPassword,
            "Alice",
            "USER",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(userMapper.findByUsername("alice")).thenReturn(existingUser);
    when(jwtService.createToken(existingUser)).thenReturn("jwt-token");

    AuthResponse response = authService.login(new LoginRequest("alice", "secret123"));

    assertThat(response.token()).isEqualTo("jwt-token");
  }

  @Test
  void loginRejectsWrongPassword() {
    UserEntity existingUser =
        new UserEntity(
            7L,
            "alice",
            passwordEncoder.encode("secret123"),
            "Alice",
            "USER",
            LocalDateTime.now(),
            LocalDateTime.now());
    when(userMapper.findByUsername("alice")).thenReturn(existingUser);

    assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }
}
