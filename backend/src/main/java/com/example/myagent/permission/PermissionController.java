package com.example.myagent.permission;

import com.example.myagent.auth.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/permission-mode")
public class PermissionController {

  private final PermissionService permissionService;

  public PermissionController(PermissionService permissionService) {
    this.permissionService = permissionService;
  }

  @GetMapping
  public Mono<PermissionModeDto> getMode(
      @AuthenticationPrincipal CurrentUser currentUser, @PathVariable String sessionId) {
    return Mono.fromCallable(() -> permissionService.getMode(currentUser, sessionId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PutMapping
  public Mono<PermissionModeDto> setMode(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String sessionId,
      @Valid @RequestBody PermissionModeDto mode) {
    return Mono.fromCallable(() -> permissionService.setMode(currentUser, sessionId, mode))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
