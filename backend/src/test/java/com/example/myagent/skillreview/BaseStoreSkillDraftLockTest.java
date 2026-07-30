package com.example.myagent.skillreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BaseStoreSkillDraftLockTest {

  @Test
  void serializesTheSameUserAcrossLockInstances() throws Exception {
    InMemoryStore store = new InMemoryStore();
    BaseStoreSkillDraftLock firstLock = lock(store, Duration.ofSeconds(2));
    BaseStoreSkillDraftLock secondLock = lock(store, Duration.ofSeconds(2));
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondEntered = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> first =
          executor.submit(
              () -> {
                try (SkillDraftLock.Handle ignored = firstLock.acquire("101")) {
                  firstEntered.countDown();
                  assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(exception);
                }
              });
      assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

      Future<?> second =
          executor.submit(
              () -> {
                try (SkillDraftLock.Handle ignored = secondLock.acquire("101")) {
                  secondEntered.countDown();
                }
              });

      assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
      releaseFirst.countDown();
      assertThat(secondEntered.await(1, TimeUnit.SECONDS)).isTrue();
      first.get(1, TimeUnit.SECONDS);
      second.get(1, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void doesNotBlockAnotherUser() throws Exception {
    InMemoryStore store = new InMemoryStore();
    BaseStoreSkillDraftLock aliceLock = lock(store, Duration.ofSeconds(2));
    BaseStoreSkillDraftLock bobLock = lock(store, Duration.ofSeconds(2));

    try (SkillDraftLock.Handle ignored = aliceLock.acquire("101")) {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<Boolean> bobEntered =
            executor.submit(
                () -> {
                  try (SkillDraftLock.Handle ignoredBob = bobLock.acquire("102")) {
                    return true;
                  }
                });

        assertThat(bobEntered.get(1, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }
    }
  }

  @Test
  void timesOutClosedWhenTheSameUserRemainsLocked() {
    InMemoryStore store = new InMemoryStore();
    BaseStoreSkillDraftLock owner = lock(store, Duration.ofSeconds(2));
    BaseStoreSkillDraftLock contender = lock(store, Duration.ofMillis(50));

    try (SkillDraftLock.Handle ignored = owner.acquire("101")) {
      assertThatThrownBy(() -> contender.acquire("101"))
          .isInstanceOf(SkillDraftLockException.class)
          .hasMessageContaining("101");
    }
  }

  @Test
  void staleOwnerCannotReleaseANewerOwnersLock() throws Exception {
    InMemoryStore store = new InMemoryStore();
    BaseStoreSkillDraftLock expiringOwner =
        new BaseStoreSkillDraftLock(
            store,
            Duration.ofSeconds(1),
            Duration.ofMillis(25),
            Duration.ofMillis(5));
    BaseStoreSkillDraftLock currentOwner = lock(store, Duration.ofSeconds(1));
    BaseStoreSkillDraftLock contender = lock(store, Duration.ofMillis(50));
    SkillDraftLock.Handle staleHandle = expiringOwner.acquire("101");

    Thread.sleep(50);
    try (SkillDraftLock.Handle ignored = currentOwner.acquire("101")) {
      staleHandle.close();

      assertThatThrownBy(() -> contender.acquire("101"))
          .isInstanceOf(SkillDraftLockException.class);
    } finally {
      staleHandle.close();
    }
  }

  private static BaseStoreSkillDraftLock lock(
      InMemoryStore store, Duration acquireTimeout) {
    return new BaseStoreSkillDraftLock(
        store, acquireTimeout, Duration.ofSeconds(5), Duration.ofMillis(5));
  }
}
