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

  private static final int MIN_SECRET_BYTES = 32;
  private final SecretKey signingKey;

  public JwtService(@Value("${security.jwt.secret}") String secret) {
    this.signingKey = Keys.hmacShaKeyFor(validateSecret(secret));
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

  private byte[] validateSecret(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException(
          "security.jwt.secret must be provided via SECURITY_JWT_SECRET");
    }

    byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < MIN_SECRET_BYTES) {
      throw new IllegalArgumentException(
          "security.jwt.secret must be at least 32 bytes for HS256 signing");
    }

    return secretBytes;
  }
}
