package com.example.myagent.auth;

import io.jsonwebtoken.JwtException;
import java.util.List;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

  private final JwtService jwtService;

  public JwtAuthenticationManager(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  public Mono<Authentication> authenticate(Authentication authentication) {
    Object credentials = authentication.getCredentials();
    if (!(credentials instanceof String token)) {
      return Mono.empty();
    }

    return Mono.<Authentication>fromSupplier(
            () -> {
              CurrentUser currentUser = jwtService.parseCurrentUser(token);
              return UsernamePasswordAuthenticationToken.authenticated(
                  currentUser,
                  token,
                  List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role())));
            })
        .onErrorMap(
            error -> error instanceof JwtException || error instanceof IllegalArgumentException,
            error -> new BadCredentialsException("Invalid JWT token", error));
  }
}
