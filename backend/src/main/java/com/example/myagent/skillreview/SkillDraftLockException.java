package com.example.myagent.skillreview;

public final class SkillDraftLockException extends RuntimeException {

  public SkillDraftLockException(String message) {
    super(message);
  }

  public SkillDraftLockException(String message, Throwable cause) {
    super(message, cause);
  }
}
