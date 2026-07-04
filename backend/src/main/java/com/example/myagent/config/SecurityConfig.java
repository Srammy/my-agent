package com.example.myagent.config;

import com.example.myagent.auth.JwtAuthenticationManager;
import com.example.myagent.auth.ServerBearerTokenAuthenticationConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  SecurityWebFilterChain springSecurityFilterChain(
      ServerHttpSecurity http,
      JwtAuthenticationManager jwtAuthenticationManager,
      ServerBearerTokenAuthenticationConverter authenticationConverter) {
    AuthenticationWebFilter authenticationWebFilter =
        new AuthenticationWebFilter(jwtAuthenticationManager);
    authenticationWebFilter.setServerAuthenticationConverter(authenticationConverter);
    authenticationWebFilter.setSecurityContextRepository(
        NoOpServerSecurityContextRepository.getInstance());

    return http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .addFilterAt(authenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .authorizeExchange(
            exchanges ->
                exchanges
                    .pathMatchers("/api/auth/register", "/api/auth/login").permitAll()
                    .pathMatchers("/api/**").authenticated()
                    .anyExchange().permitAll())
        .build();
  }
}
