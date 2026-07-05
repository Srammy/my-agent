package com.example.myagent.memory;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MemoryService {

  private final UserMemoryMapper userMemoryMapper;

  public MemoryService(UserMemoryMapper userMemoryMapper) {
    this.userMemoryMapper = userMemoryMapper;
  }

  public String getSummary(CurrentUser currentUser) {
    String content = userMemoryMapper.findSummaryByUserId(currentUser.id());
    return content == null ? "" : content;
  }

  public List<String> listDaily(CurrentUser currentUser) {
    return userMemoryMapper.findDailyByUserId(currentUser.id());
  }

  public String getDaily(CurrentUser currentUser, LocalDate date) {
    String content = userMemoryMapper.findDailyByUserIdAndDate(currentUser.id(), date);
    return content == null ? "" : content;
  }
}
