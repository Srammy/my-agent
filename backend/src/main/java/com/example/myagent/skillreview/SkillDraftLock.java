package com.example.myagent.skillreview;

public interface SkillDraftLock {

  Handle acquire(String userId);

  interface Handle extends AutoCloseable {

    boolean renew();

    @Override
    void close();
  }
}
