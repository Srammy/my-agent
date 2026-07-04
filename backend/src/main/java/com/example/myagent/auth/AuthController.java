package com.example.myagent.auth;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  public Mono<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    return Mono.fromCallable(() -> authService.register(request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/login")
  public Mono<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    return Mono.fromCallable(() -> authService.login(request))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/me")
  public Mono<CurrentUser> me(@AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.just(currentUser);
  }
}
