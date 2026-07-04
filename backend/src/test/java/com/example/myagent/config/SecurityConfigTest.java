package com.example.myagent.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.AuthController;
import com.example.myagent.auth.AuthResponse;
import com.example.myagent.auth.AuthService;
import com.example.myagent.auth.JwtAuthenticationManager;
import com.example.myagent.auth.LoginRequest;
import com.example.myagent.auth.RegisterRequest;
import com.example.myagent.auth.ServerBearerTokenAuthenticationConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

  @Mock private AuthService authService;
  @Mock private JwtAuthenticationManager jwtAuthenticationManager;

  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    SecurityConfig securityConfig = new SecurityConfig();
    SecurityWebFilterChain securityWebFilterChain =
        securityConfig.springSecurityFilterChain(
            ServerHttpSecurity.http(),
            jwtAuthenticationManager,
            new ServerBearerTokenAuthenticationConverter());

    webTestClient =
        WebTestClient.bindToController(new AuthController(authService))
            .webFilter(new WebFilterChainProxy(securityWebFilterChain))
            .configureClient()
            .build();
  }

  @Test
  void registerAllowsMalformedBearerHeaderToReachService() {
    RegisterRequest request = new RegisterRequest("alice", "secret123", "Alice");
    when(authService.register(request)).thenReturn(new AuthResponse("jwt-token"));

    webTestClient
        .post()
        .uri("/api/auth/register")
        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.token")
        .isEqualTo("jwt-token");

    verify(authService).register(request);
    verify(jwtAuthenticationManager, never()).authenticate(any());
  }

  @Test
  void loginAllowsMalformedBearerHeaderToReachService() {
    LoginRequest request = new LoginRequest("alice", "secret123");
    when(authService.login(request)).thenReturn(new AuthResponse("jwt-token"));

    webTestClient
        .post()
        .uri("/api/auth/login")
        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.token")
        .isEqualTo("jwt-token");

    verify(authService).login(request);
    verify(jwtAuthenticationManager, never()).authenticate(any());
  }

  @Test
  void protectedApiRejectsMalformedBearerHeader() {
    when(jwtAuthenticationManager.authenticate(any()))
        .thenReturn(Mono.error(new BadCredentialsException("Invalid JWT token")));

    webTestClient
        .get()
        .uri("/api/auth/me")
        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        .exchange()
        .expectStatus()
        .isUnauthorized();

    verify(jwtAuthenticationManager).authenticate(any());
  }
}
