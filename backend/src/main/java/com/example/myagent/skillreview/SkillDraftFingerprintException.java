package com.example.myagent.skillreview;

public final class SkillDraftFingerprintException extends RuntimeException {

  public enum Reason {
    NOT_FOUND,
    READ_FAILURE
  }

  private final Reason reason;

  public SkillDraftFingerprintException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public SkillDraftFingerprintException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
