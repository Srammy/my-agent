package com.example.myagent.auth;

import com.example.myagent.user.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private final SecretKey signingKey;

  public JwtService(@Value("${security.jwt.secret}") String secret) {
    this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public String createToken(UserEntity user) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(String.valueOf(user.getId()))
        .claim("username", user.getUsername())
        .claim("role", user.getRole())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(7, ChronoUnit.DAYS)))
        .signWith(signingKey)
        .compact();
  }

  public Long parseUserId(String token) {
    return Long.valueOf(parseClaims(token).getSubject());
  }

  public CurrentUser parseCurrentUser(String token) {
    Claims claims = parseClaims(token);
    return new CurrentUser(
        Long.valueOf(claims.getSubject()),
        claims.get("username", String.class),
        claims.get("role", String.class));
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
  }
}
