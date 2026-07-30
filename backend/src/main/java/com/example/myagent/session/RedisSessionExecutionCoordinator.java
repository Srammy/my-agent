package com.example.myagent.session;

import com.example.myagent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

@Service
public class RedisSessionExecutionCoordinator implements SessionExecutionCoordinator, DisposableBean {
  private static final Logger log =
      LoggerFactory.getLogger(RedisSessionExecutionCoordinator.class);
  private static final String CANCELLATION_CHANNEL_SUFFIX = "session-execution:cancel";

  private static final Duration DEFAULT_ACTIVE_TTL = Duration.ofSeconds(30);
  private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
  private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_CANCELLATION_TTL = Duration.ofSeconds(30);
  private static final String DEFAULT_KEY_PREFIX = "myagent:agent-state:";
  static final String CANCELLATION_CHANNEL = DEFAULT_KEY_PREFIX + CANCELLATION_CHANNEL_SUFFIX;
  private static final RedisScript<Long> INCREMENT_ACTIVE_COUNT = RedisScript.of("""
      if redis.call('EXISTS', KEYS[1]) == 1 then
        return -1
      end
      if redis.call('SCARD', KEYS[3]) > 0 and redis.call('EXISTS', KEYS[2]) == 0 then
        return -2
      end
      if redis.call('SADD', KEYS[3], ARGV[2]) == 0 then
        return -3
      end
      local value = redis.call('INCR', KEYS[2])
      redis.call('PEXPIRE', KEYS[2], ARGV[1])
      return value
      """, Long.class);
  private static final RedisScript<Long> DECREMENT_ACTIVE_COUNT = RedisScript.of("""
      local removed = redis.call('SREM', KEYS[2], ARGV[2])
      local current = tonumber(redis.call('GET', KEYS[1]) or '0')
      local pending = redis.call('SCARD', KEYS[2])
      if removed == 0 then
        if pending == 0 and current == 0 then
          return 0
        end
        if current == 0 then
          return -pending
        end
        return math.max(current, pending)
      end
      if current > 0 then
        current = redis.call('DECR', KEYS[1])
      end
      if pending == 0 then
        redis.call('DEL', KEYS[1])
        redis.call('DEL', KEYS[2])
        return 0
      end
      if current <= 0 then
        redis.call('DEL', KEYS[1])
        return -pending
      end
      redis.call('PEXPIRE', KEYS[1], ARGV[1])
      return math.max(current, pending)
      """, Long.class);
  private static final RedisScript<Long> READ_ACTIVE_COUNT = RedisScript.of("""
      local pending = redis.call('SCARD', KEYS[2])
      local current = tonumber(redis.call('GET', KEYS[1]) or '0')
      if pending == 0 and current == 0 then
        return 0
      end
      if pending > 0 and current == 0 then
        return -pending
      end
      return math.max(current, pending)
      """, Long.class);
  private static final RedisScript<Long> REFRESH_EXECUTION_LEASE = RedisScript.of("""
      if redis.call('EXISTS', KEYS[1]) == 1 then
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return -1
      end
      return redis.call('PEXPIRE', KEYS[2], ARGV[1])
      """, Long.class);

  private final ReactiveStringRedisTemplate redisTemplate;
  private final ReactiveRedisMessageListenerContainer listenerContainer;
  private final ObjectMapper objectMapper;
  private final Duration activeTtl;
  private final Duration refreshInterval;
  private final Duration pollInterval;
  private final Duration waitTimeout;
  private final String keyPrefix;
  private final Duration cancellationTtl;
  private final String cancellationChannel;
  private final ConcurrentMap<SessionExecutionKey, ConcurrentMap<String, LocalExecution>> localExecutions =
      new ConcurrentHashMap<>();
  private final ConcurrentMap<CleanupKey, CleanupTask> pendingCleanups = new ConcurrentHashMap<>();
  private final AtomicBoolean destroyed = new AtomicBoolean();
  private final Disposable messageSubscription;
  private final Disposable cleanupRetrySubscription;

  public RedisSessionExecutionCoordinator(
      ReactiveStringRedisTemplate redisTemplate,
      ObjectMapper objectMapper) {
    this(
        redisTemplate,
        new ReactiveRedisMessageListenerContainer(redisTemplate.getConnectionFactory()),
        objectMapper,
        DEFAULT_ACTIVE_TTL,
        DEFAULT_REFRESH_INTERVAL,
        DEFAULT_POLL_INTERVAL,
        DEFAULT_WAIT_TIMEOUT,
        DEFAULT_KEY_PREFIX,
        DEFAULT_CANCELLATION_TTL);
  }

  @Autowired
  RedisSessionExecutionCoordinator(
      ReactiveStringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      AgentProperties agentProperties) {
    this(
        redisTemplate,
        new ReactiveRedisMessageListenerContainer(redisTemplate.getConnectionFactory()),
        objectMapper,
        DEFAULT_ACTIVE_TTL,
        DEFAULT_REFRESH_INTERVAL,
        DEFAULT_POLL_INTERVAL,
        DEFAULT_WAIT_TIMEOUT,
        agentProperties.stateStore().redis().keyPrefix(),
        DEFAULT_CANCELLATION_TTL);
  }

  RedisSessionExecutionCoordinator(
      ReactiveStringRedisTemplate redisTemplate,
      ReactiveRedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper,
      Duration activeTtl,
      Duration refreshInterval,
      Duration pollInterval,
      Duration waitTimeout) {
    this(
        redisTemplate,
        listenerContainer,
        objectMapper,
        activeTtl,
        refreshInterval,
        pollInterval,
        waitTimeout,
        DEFAULT_KEY_PREFIX,
        DEFAULT_CANCELLATION_TTL);
  }

  RedisSessionExecutionCoordinator(
      ReactiveStringRedisTemplate redisTemplate,
      ReactiveRedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper,
      Duration activeTtl,
      Duration refreshInterval,
      Duration pollInterval,
      Duration waitTimeout,
      String keyPrefix,
      Duration cancellationTtl) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
    this.objectMapper = objectMapper;
    this.activeTtl = activeTtl;
    this.refreshInterval = refreshInterval;
    this.pollInterval = pollInterval;
    this.waitTimeout = waitTimeout;
    this.keyPrefix = keyPrefix;
    this.cancellationTtl = cancellationTtl;
    this.cancellationChannel = keyPrefix + CANCELLATION_CHANNEL_SUFFIX;
    this.messageSubscription = Flux.defer(
            () -> listenerContainer.receive(ChannelTopic.of(cancellationChannel)))
        .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, pollInterval))
        .subscribe(message -> handleCancellation(message.getMessage()), ignored -> {});
    this.cleanupRetrySubscription = Flux.interval(refreshInterval)
        .subscribe(ignored -> retryPendingCleanups(), error ->
            log.error("Session execution cleanup retry loop stopped", error));
  }

  @Override
  public <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source) {
    return Flux.defer(() -> {
      Sinks.Empty<Void> completion = Sinks.empty();
      Supplier<Flux<T>> completionAwareSource = () -> Flux.defer(source)
          .doFinally(ignored -> completion.tryEmitEmpty());
      return track(userId, sessionId, completionAwareSource, completion::asMono);
    });
  }

  @Override
  public <T> Flux<T> track(
      Long userId,
      String sessionId,
      Supplier<Flux<T>> source,
      Supplier<Mono<Void>> completion) {
    return track(userId, sessionId, Mono::empty, source, completion);
  }

  @Override
  public <T> Flux<T> track(
      Long userId,
      String sessionId,
      Supplier<Mono<Void>> preflight,
      Supplier<Flux<T>> source,
      Supplier<Mono<Void>> completion) {
    return Flux.defer(() -> {
      if (destroyed.get()) {
        return Flux.error(new IllegalStateException("Session execution coordinator is closed"));
      }
      SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
      String executionId = UUID.randomUUID().toString();
      long registrationDeadline = System.nanoTime() + registrationTimeoutNanos();
      RegistrationAttempt registration = new RegistrationAttempt(key, executionId);
      Mono<Long> registrationResult = incrementActiveCount(key, executionId)
          .doOnNext(registration::recordResult)
          .cache();
      return withinRegistrationWindow(
              registrationResult, registrationDeadline)
          .doOnCancel(registration::abandon)
          .doOnError(ignored -> registration.abandon())
          .flatMapMany(activeCount -> {
            if (activeCount < 0L) {
              return Flux.error(sessionCancelled());
            }
            if (!registration.claim()) {
              return Flux.error(registrationTimeout());
            }
            LocalExecution execution = register(key, executionId);
            Disposable completionSubscription = Mono.defer(completion)
                .doFinally(signal -> complete(key, executionId, execution))
                .subscribe(ignoredCompletion -> {}, ignoredError -> {});
            execution.completionWith(completionSubscription);
            if (execution.isComplete()) {
              return Flux.empty();
            }
            Disposable heartbeat = Flux.interval(refreshInterval)
                .concatMap(tick -> heartbeat(key, execution))
                .subscribe(ignored -> {}, ignored -> {});
            Disposable watchdog = Flux.interval(refreshInterval)
                .subscribe(ignored -> execution.cancelIfLeaseUnsafe(activeTtl, refreshInterval));
            execution.refreshWith(heartbeat, watchdog);
            Flux<T> executionEvents = withinRegistrationWindow(
                    rejectIfCancelled(userId, sessionId), registrationDeadline)
                .thenMany(Mono.defer(preflight)
                    .thenMany(Flux.defer(source)
                        .doOnSubscribe(execution::attach)))
                .doOnError(error -> completeIfNotStarted(key, executionId, execution))
                .doOnCancel(() -> completeIfNotStarted(key, executionId, execution));
            return executionEvents
                .takeUntilOther(execution.cancelled())
                .concatWith(Flux.defer(() ->
                    execution.isSessionCancelling() && !execution.hasStarted()
                        ? Flux.error(sessionCancelled())
                        : Flux.empty()));
          });
    });
  }

  @Override
  public Mono<Void> cancelAndAwait(Long userId, String sessionId) {
    SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
    return Mono.defer(() -> {
      final String payload;
      try {
        payload = objectMapper.writeValueAsString(new CancellationMessage(userId, sessionId));
      } catch (JsonProcessingException error) {
        return Mono.error(error);
      }
      return redisTemplate.opsForValue().set(cancellationKey(key), "1", cancellationLeaseTtl())
          .switchIfEmpty(Mono.error(
              new IllegalStateException("Failed to record session cancellation")))
          .flatMap(stored -> {
            if (!stored) {
              return Mono.error(new IllegalStateException("Failed to record session cancellation"));
            }
            cancelLocal(key);
            return redisTemplate.convertAndSend(cancellationChannel, payload);
          })
          .then(waitForNoActiveExecutions(key));
    }).timeout(waitTimeout, Mono.error(cancellationTimeout()));
  }

  @Override
  public Mono<Void> rejectIfCancelled(Long userId, String sessionId) {
    SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
    return redisTemplate.opsForValue().get(cancellationKey(key))
        .flatMap(ignored -> Mono.error(sessionCancelled()))
        .then();
  }

  @Override
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
    messageSubscription.dispose();
    cleanupRetrySubscription.dispose();
    localExecutions.values().forEach(
        executions -> executions.values().forEach(LocalExecution::shutdown));
    listenerContainer.destroy();
  }

  private LocalExecution register(SessionExecutionKey key, String executionId) {
    LocalExecution execution = new LocalExecution();
    localExecutions.compute(key, (ignored, executions) -> {
      ConcurrentMap<String, LocalExecution> updated =
          executions == null ? new ConcurrentHashMap<>() : executions;
      updated.put(executionId, execution);
      return updated;
    });
    if (destroyed.get()) {
      execution.cancel();
    }
    return execution;
  }

  private void complete(
      SessionExecutionKey key, String executionId, LocalExecution execution) {
    if (!execution.markComplete()) {
      return;
    }
    execution.disposeCompletion();
    execution.disposeRefresh();
    localExecutions.computeIfPresent(key, (ignored, executions) -> {
      executions.remove(executionId, execution);
      return executions.isEmpty() ? null : executions;
    });
    requestCleanup(key, executionId);
  }

  private void completeIfNotStarted(
      SessionExecutionKey key, String executionId, LocalExecution execution) {
    if (!execution.hasStarted()) {
      complete(key, executionId, execution);
    }
  }

  private void cancelLocal(SessionExecutionKey key) {
    Map<String, LocalExecution> executions = localExecutions.get(key);
    if (executions != null) {
      executions.values().forEach(LocalExecution::cancelForSession);
    }
  }

  private void handleCancellation(String payload) {
    try {
      CancellationMessage message = objectMapper.readValue(payload, CancellationMessage.class);
      cancelLocal(new SessionExecutionKey(message.userId(), message.sessionId()));
    } catch (JsonProcessingException ignored) {
      // Ignore malformed messages on the private coordination channel.
    }
  }

  private Mono<Void> heartbeat(
      SessionExecutionKey key, LocalExecution execution) {
    Mono<Void> operation = redisTemplate.execute(
            REFRESH_EXECUTION_LEASE,
            List.of(cancellationKey(key), activeCountKey(key)),
            List.of(
                Long.toString(activeTtl.toMillis()),
                Long.toString(cancellationLeaseTtl().toMillis())))
        .next()
        .switchIfEmpty(Mono.error(new IllegalStateException("Failed to renew execution lease")))
        .doOnNext(result -> {
          if (result > 0L) {
            execution.markRenewed();
          } else if (result == -1L) {
            execution.cancelForSession();
          } else {
            execution.cancel();
          }
        })
        .then();
    return operation.timeout(heartbeatTimeout())
        .onErrorResume(error -> {
          execution.cancel();
          return Mono.empty();
        });
  }

  private Duration heartbeatTimeout() {
    long safeWindowNanos = activeTtl.minus(refreshInterval).toNanos();
    long timeoutNanos = Math.max(
        1L, Math.min(refreshInterval.toNanos(), Math.max(1L, safeWindowNanos / 2L)));
    return Duration.ofNanos(timeoutNanos);
  }

  private Mono<Void> waitForNoActiveExecutions(SessionExecutionKey key) {
    return Flux.interval(Duration.ZERO, pollInterval)
        .concatMap(ignored -> activeCount(key))
        .filter(count -> count == 0L)
        .next()
        .then();
  }

  private Mono<Long> incrementActiveCount(SessionExecutionKey key, String executionId) {
    return redisTemplate.execute(
            INCREMENT_ACTIVE_COUNT,
            List.of(cancellationKey(key), activeCountKey(key), pendingCompletionKey(key)),
            List.of(Long.toString(activeTtl.toMillis()), executionId))
        .next()
        .switchIfEmpty(Mono.error(
            new IllegalStateException("Failed to register session execution")));
  }

  private Mono<Long> decrementActiveCount(SessionExecutionKey key, String executionId) {
    return redisTemplate.execute(
            DECREMENT_ACTIVE_COUNT,
            List.of(activeCountKey(key), pendingCompletionKey(key)),
            List.of(Long.toString(activeTtl.toMillis()), executionId))
        .next()
        .switchIfEmpty(Mono.error(
            new IllegalStateException("Failed to unregister session execution")));
  }

  private void requestCleanup(SessionExecutionKey key, String executionId) {
    CleanupKey cleanupKey = new CleanupKey(key, executionId);
    CleanupTask cleanup = pendingCleanups.computeIfAbsent(cleanupKey, ignored -> new CleanupTask());
    attemptCleanup(cleanupKey, cleanup);
  }

  private void retryPendingCleanups() {
    pendingCleanups.forEach(this::attemptCleanup);
  }

  private void attemptCleanup(CleanupKey cleanupKey, CleanupTask cleanup) {
    if (!cleanup.start()) {
      return;
    }
    decrementActiveCount(cleanupKey.key(), cleanupKey.executionId())
        .retryWhen(Retry.fixedDelay(2, pollInterval))
        .subscribe(
            ignored -> {
              pendingCleanups.remove(cleanupKey, cleanup);
              cleanup.finished();
            },
            error -> {
              cleanup.finished();
              log.warn(
                  "Failed to unregister session execution {}; queued for retry: {}",
                  cleanupKey.executionId(),
                  error.toString());
            });
  }

  private Mono<Long> activeCount(SessionExecutionKey key) {
    return redisTemplate.execute(
            READ_ACTIVE_COUNT,
            List.of(activeCountKey(key), pendingCompletionKey(key)),
            List.of())
        .next()
        .switchIfEmpty(Mono.error(
            new IllegalStateException("Failed to read active session executions")));
  }

  private String sessionPrefix(SessionExecutionKey key) {
    return keyPrefix + "session-execution:" + key.userId() + ":" + key.sessionId();
  }

  private String cancellationKey(SessionExecutionKey key) {
    return sessionPrefix(key) + ":cancelled";
  }

  private String activeCountKey(SessionExecutionKey key) {
    return sessionPrefix(key) + ":active-count";
  }

  private String pendingCompletionKey(SessionExecutionKey key) {
    return sessionPrefix(key) + ":pending-completion";
  }

  private long registrationTimeoutNanos() {
    return Math.max(
        1L, Math.min(waitTimeout.toNanos(), cancellationTtl.toNanos() / 2L));
  }

  private Duration cancellationLeaseTtl() {
    Duration minimum = activeTtl.plusNanos(registrationTimeoutNanos()).plusMillis(1L);
    return cancellationTtl.compareTo(minimum) >= 0 ? cancellationTtl : minimum;
  }

  private <T> Mono<T> withinRegistrationWindow(Mono<T> operation, long deadlineNanos) {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0L) {
      return Mono.error(registrationTimeout());
    }
    return operation.timeout(
        Duration.ofNanos(remaining), Mono.error(registrationTimeout()));
  }

  private ResponseStatusException registrationTimeout() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Session execution registration timed out");
  }

  private ResponseStatusException sessionCancelled() {
    return new SessionCancellingException();
  }

  private ResponseStatusException cancellationTimeout() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Session cancellation is still in progress");
  }

  private record CancellationMessage(Long userId, String sessionId) {}

  private record CleanupKey(SessionExecutionKey key, String executionId) {}

  private static final class SessionCancellingException extends ResponseStatusException {
    private final HttpHeaders headers = new HttpHeaders();

    private SessionCancellingException() {
      super(HttpStatus.CONFLICT, "Session is being cancelled");
      headers.set("X-Error-Code", "SESSION_CANCELLING");
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }
  }

  private static final class CleanupTask {
    private final AtomicBoolean inFlight = new AtomicBoolean();

    boolean start() {
      return inFlight.compareAndSet(false, true);
    }

    void finished() {
      inFlight.set(false);
    }
  }

  private enum RegistrationState {
    PENDING,
    REGISTERED,
    CLAIMED,
    ABANDONED
  }

  private final class RegistrationAttempt {
    private final SessionExecutionKey key;
    private final String executionId;
    private final AtomicReference<RegistrationState> state =
        new AtomicReference<>(RegistrationState.PENDING);
    private final AtomicBoolean compensationStarted = new AtomicBoolean();

    private RegistrationAttempt(SessionExecutionKey key, String executionId) {
      this.key = key;
      this.executionId = executionId;
    }

    void recordResult(long activeCount) {
      if (activeCount < 0L) {
        return;
      }
      if (!state.compareAndSet(RegistrationState.PENDING, RegistrationState.REGISTERED)
          && state.get() == RegistrationState.ABANDONED) {
        compensate();
      }
    }

    boolean claim() {
      return state.compareAndSet(RegistrationState.REGISTERED, RegistrationState.CLAIMED);
    }

    void abandon() {
      while (true) {
        RegistrationState current = state.get();
        if (current == RegistrationState.CLAIMED || current == RegistrationState.ABANDONED) {
          return;
        }
        if (state.compareAndSet(current, RegistrationState.ABANDONED)) {
          if (current == RegistrationState.REGISTERED) {
            compensate();
          }
          return;
        }
      }
    }

    private void compensate() {
      if (compensationStarted.compareAndSet(false, true)) {
        requestCleanup(key, executionId);
      }
    }
  }

  private static final class LocalExecution {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean sessionCancelling = new AtomicBoolean();
    private final AtomicBoolean complete = new AtomicBoolean();
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    private final Disposable.Composite refresh = Disposables.composite();
    private final AtomicReference<Disposable> completion = new AtomicReference<>();
    private final Sinks.Empty<Void> cancellationSignal = Sinks.empty();
    private volatile long lastRenewedAtNanos = System.nanoTime();

    void attach(Subscription attached) {
      if (!subscription.compareAndSet(null, attached)) {
        attached.cancel();
        return;
      }
      if (cancelled.get()) {
        attached.cancel();
      }
      if (complete.get()) {
        attached.cancel();
      }
    }

    void refreshWith(Disposable... disposables) {
      for (Disposable disposable : disposables) {
        refresh.add(disposable);
      }
    }

    void completionWith(Disposable disposable) {
      if (!completion.compareAndSet(null, disposable) || complete.get()) {
        disposable.dispose();
      }
    }

    boolean markComplete() {
      return complete.compareAndSet(false, true);
    }

    boolean isComplete() {
      return complete.get();
    }

    boolean hasStarted() {
      return subscription.get() != null;
    }

    boolean isCancelled() {
      return cancelled.get();
    }

    boolean isSessionCancelling() {
      return sessionCancelling.get();
    }

    Mono<Void> cancelled() {
      return cancellationSignal.asMono();
    }

    void cancelForSession() {
      sessionCancelling.set(true);
      cancel();
    }

    void cancel() {
      cancelled.set(true);
      Subscription attached = subscription.get();
      if (attached != null) {
        attached.cancel();
      }
      cancellationSignal.tryEmitEmpty();
    }

    void markRenewed() {
      lastRenewedAtNanos = System.nanoTime();
    }

    void cancelIfLeaseUnsafe(Duration activeTtl, Duration refreshInterval) {
      long safeWindowNanos = Math.max(
          0L, activeTtl.minus(refreshInterval).toNanos());
      if (System.nanoTime() - lastRenewedAtNanos >= safeWindowNanos) {
        cancel();
      }
    }

    void disposeRefresh() {
      refresh.dispose();
    }

    void disposeCompletion() {
      Disposable disposable = completion.get();
      if (disposable != null) {
        disposable.dispose();
      }
    }

    void shutdown() {
      cancel();
      disposeRefresh();
    }
  }
}
