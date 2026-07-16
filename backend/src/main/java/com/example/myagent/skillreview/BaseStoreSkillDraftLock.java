package com.example.myagent.skillreview;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public final class BaseStoreSkillDraftLock implements SkillDraftLock {

  private static final String LOCK_NAMESPACE = "_skill-draft-lock";
  private static final String LOCK_KEY = "mutation";
  private static final String OWNER_TOKEN = "ownerToken";
  private static final String EXPIRES_AT = "expiresAtEpochMilli";
  private static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(2);
  private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(10);

  private final BaseStore store;
  private final Duration acquireTimeout;
  private final Duration leaseDuration;
  private final Duration retryDelay;

  public BaseStoreSkillDraftLock(BaseStore store) {
    this(store, DEFAULT_ACQUIRE_TIMEOUT, DEFAULT_LEASE_DURATION, DEFAULT_RETRY_DELAY);
  }

  BaseStoreSkillDraftLock(
      BaseStore store,
      Duration acquireTimeout,
      Duration leaseDuration,
      Duration retryDelay) {
    if (store == null) {
      throw new IllegalArgumentException("store is required");
    }
    requirePositive(acquireTimeout, "acquireTimeout");
    requirePositive(leaseDuration, "leaseDuration");
    requirePositive(retryDelay, "retryDelay");
    this.store = store;
    this.acquireTimeout = acquireTimeout;
    this.leaseDuration = leaseDuration;
    this.retryDelay = retryDelay;
  }

  @Override
  public Handle acquire(String userId) {
    if (userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("userId is required");
    }
    List<String> namespace = List.of(userId, LOCK_NAMESPACE);
    String token = UUID.randomUUID().toString();
    long deadline = System.nanoTime() + acquireTimeout.toNanos();

    while (true) {
      long now = System.currentTimeMillis();
      StoreItem current = store.get(namespace, LOCK_KEY);
      if (isAvailable(current, now)) {
        long expectedVersion = current == null ? 0L : current.version();
        if (store.putIfVersion(
            namespace,
            LOCK_KEY,
            lockValue(token, now + leaseDuration.toMillis()),
            expectedVersion)) {
          return new StoreHandle(namespace, token);
        }
      }
      if (System.nanoTime() >= deadline) {
        throw new SkillDraftLockException(
            "Timed out acquiring skill draft lock for user " + userId);
      }
      pauseBeforeRetry(userId);
    }
  }

  private final class StoreHandle implements Handle {

    private final List<String> namespace;
    private final String token;
    private final AtomicBoolean closed = new AtomicBoolean();

    private StoreHandle(List<String> namespace, String token) {
      this.namespace = namespace;
      this.token = token;
    }

    @Override
    public boolean renew() {
      if (closed.get()) {
        return false;
      }
      StoreItem current = store.get(namespace, LOCK_KEY);
      long now = System.currentTimeMillis();
      if (!isOwned(current, token, now)) {
        return false;
      }
      return store.putIfVersion(
          namespace,
          LOCK_KEY,
          lockValue(token, now + leaseDuration.toMillis()),
          current.version());
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      StoreItem current = store.get(namespace, LOCK_KEY);
      if (current == null || !token.equals(ownerToken(current))) {
        return;
      }
      store.putIfVersion(
          namespace,
          LOCK_KEY,
          lockValue("", 0L),
          current.version());
    }
  }

  private void pauseBeforeRetry(String userId) {
    LockSupport.parkNanos(retryDelay.toNanos());
    if (Thread.currentThread().isInterrupted()) {
      Thread.currentThread().interrupt();
      throw new SkillDraftLockException(
          "Interrupted acquiring skill draft lock for user " + userId);
    }
  }

  private static boolean isAvailable(StoreItem item, long now) {
    return item == null
        || ownerToken(item).isBlank()
        || expiresAt(item) <= now;
  }

  private static boolean isOwned(StoreItem item, String token, long now) {
    return item != null
        && token.equals(ownerToken(item))
        && expiresAt(item) > now;
  }

  private static String ownerToken(StoreItem item) {
    Object value = item.value().get(OWNER_TOKEN);
    return value instanceof String token ? token : "";
  }

  private static long expiresAt(StoreItem item) {
    Object value = item.value().get(EXPIRES_AT);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static Map<String, Object> lockValue(String token, long expiresAt) {
    return Map.of(OWNER_TOKEN, token, EXPIRES_AT, expiresAt);
  }

  private static void requirePositive(Duration duration, String name) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
