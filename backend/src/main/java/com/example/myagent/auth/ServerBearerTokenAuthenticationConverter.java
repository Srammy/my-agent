package com.example.myagent.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ServerBearerTokenAuthenticationConverter implements ServerAuthenticationConverter {

  private static final String BEARER_PREFIX = "Bearer ";

  @Override
  public Mono<Authentication> convert(ServerWebExchange exchange) {
    String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
      return Mono.empty();
    }
    String token = authorization.substring(BEARER_PREFIX.length()).trim();
    if (!StringUtils.hasText(token)) {
      return Mono.empty();
    }
    return Mono.just(new UsernamePasswordAuthenticationToken(token, token));
  }
}
