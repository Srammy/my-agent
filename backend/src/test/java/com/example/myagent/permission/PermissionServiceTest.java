package com.example.myagent.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");

  @Mock private PermissionModeMapper permissionModeMapper;
  @Mock private SessionService sessionService;

  private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    permissionService = new PermissionService(permissionModeMapper, sessionService);
  }

  @Test
  void getModeReturnsDefaultWhenSessionHasNoStoredMode() {
    when(permissionModeMapper.findBySessionId("s_123")).thenReturn(null);

    PermissionModeDto mode = permissionService.getMode(USER, "s_123");

    assertThat(mode.mode()).isEqualTo(PermissionMode.DEFAULT);
    verify(sessionService).requireOwnedSession(USER, "s_123");
  }

  @Test
  void setModePersistsValidModeAndReturnsIt() {
    PermissionModeDto mode = permissionService.setMode(USER, "s_123", new PermissionModeDto(PermissionMode.BYPASS));

    assertThat(mode.mode()).isEqualTo(PermissionMode.BYPASS);
    verify(sessionService).requireOwnedSession(USER, "s_123");
    verify(permissionModeMapper).upsert("s_123", PermissionMode.BYPASS.name());
  }
}
