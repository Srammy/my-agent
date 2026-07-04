package com.example.myagent.auth;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  public Mono<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return Mono.fromSupplier(() -> authService.register(request));
  }

  @PostMapping("/login")
  public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return Mono.fromSupplier(() -> authService.login(request));
  }

  @GetMapping("/me")
  public Mono<CurrentUser> me(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.just(currentUser);
  }
}
