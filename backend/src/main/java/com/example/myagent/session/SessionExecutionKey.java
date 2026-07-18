package com.example.myagent.session;

public record SessionExecutionKey(Long userId, String sessionId) {
  String prefix() {
    return "myagent:session-execution:" + userId + ":" + sessionId;
  }

  String cancellationKey() {
    return prefix() + ":cancelled";
  }

  String activeKey(String executionId) {
    return prefix() + ":active:" + executionId;
  }
}
