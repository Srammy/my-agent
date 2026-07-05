package com.example.myagent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");

  @Mock private UserMemoryMapper userMemoryMapper;

  private MemoryService memoryService;

  @BeforeEach
  void setUp() {
    memoryService = new MemoryService(userMemoryMapper);
  }

  @Test
  void getSummaryReturnsCurrentUsersSummaryContent() {
    when(userMemoryMapper.findSummaryByUserId(USER.id())).thenReturn("Alice summary");

    assertThat(memoryService.getSummary(USER)).isEqualTo("Alice summary");

    verify(userMemoryMapper).findSummaryByUserId(USER.id());
  }

  @Test
  void getSummaryReturnsEmptyStringWhenMissing() {
    when(userMemoryMapper.findSummaryByUserId(USER.id())).thenReturn(null);

    assertThat(memoryService.getSummary(USER)).isEmpty();
  }

  @Test
  void listDailyReturnsCurrentUsersDailyContents() {
    when(userMemoryMapper.findDailyByUserId(USER.id())).thenReturn(List.of("day one", "day two"));

    assertThat(memoryService.listDaily(USER)).containsExactly("day one", "day two");
  }

  @Test
  void getDailyReturnsEmptyStringWhenMissing() {
    LocalDate date = LocalDate.parse("2026-07-05");
    when(userMemoryMapper.findDailyByUserIdAndDate(USER.id(), date)).thenReturn(null);

    assertThat(memoryService.getDaily(USER, date)).isEmpty();
  }
}
