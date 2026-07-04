package com.example.myagent.auth;

import com.example.myagent.user.UserEntity;
import com.example.myagent.user.UserMapper;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

  private final UserMapper userMapper;
  private final JwtService jwtService;
  private final PasswordEncoder passwordEncoder;

  public AuthService(UserMapper userMapper, JwtService jwtService, PasswordEncoder passwordEncoder) {
    this.userMapper = userMapper;
    this.jwtService = jwtService;
    this.passwordEncoder = passwordEncoder;
  }

  public AuthResponse register(RegisterRequest request) {
    if (userMapper.findByUsername(request.username()) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
    }

    LocalDateTime now = LocalDateTime.now();
    UserEntity user = new UserEntity();
    user.setUsername(request.username());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setDisplayName(
        StringUtils.hasText(request.displayName()) ? request.displayName().trim() : request.username());
    user.setRole("USER");
    user.setCreatedAt(now);
    user.setUpdatedAt(now);
    userMapper.insert(user);
    return new AuthResponse(jwtService.createToken(user));
  }

  public AuthResponse login(LoginRequest request) {
    UserEntity user = userMapper.findByUsername(request.username());
    if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    return new AuthResponse(jwtService.createToken(user));
  }
}
