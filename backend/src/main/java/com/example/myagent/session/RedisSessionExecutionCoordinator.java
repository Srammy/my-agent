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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
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
      if redis.call('EXISTS', KEYS[3]) == 1 and redis.call('EXISTS', KEYS[2]) == 0 then
        return -2
      end
      local value = redis.call('INCR', KEYS[2])
      redis.call('SET', KEYS[3], '1')
      redis.call('PEXPIRE', KEYS[2], ARGV[1])
      return value
      """, Long.class);
  private static final RedisScript<Long> DECREMENT_ACTIVE_COUNT = RedisScript.of("""
      local current = tonumber(redis.call('GET', KEYS[1]) or '0')
      if current <= 1 then
        redis.call('DEL', KEYS[1])
        redis.call('DEL', KEYS[2])
        return 0
      end
      local value = redis.call('DECR', KEYS[1])
      redis.call('PEXPIRE', KEYS[1], ARGV[1])
      return value
      """, Long.class);
  private static final RedisScript<Long> READ_ACTIVE_COUNT = RedisScript.of("""
      if redis.call('EXISTS', KEYS[2]) == 0 then
        return 0
      end
      local value = redis.call('GET', KEYS[1])
      if not value then
        return -1
      end
      return tonumber(value)
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
  private final AtomicBoolean destroyed = new AtomicBoolean();
  private final Disposable messageSubscription;

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
  }

  @Override
  public <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source) {
    Sinks.Empty<Void> completion = Sinks.empty();
    Supplier<Flux<T>> completionAwareSource = () -> Flux.defer(source)
        .doOnComplete(() -> completion.tryEmitEmpty())
        .doOnError(ignored -> completion.tryEmitEmpty());
    return track(userId, sessionId, completionAwareSource, completion::asMono);
  }

  @Override
  public <T> Flux<T> track(
      Long userId,
      String sessionId,
      Supplier<Flux<T>> source,
      Supplier<Mono<Void>> completion) {
    return Flux.defer(() -> {
      if (destroyed.get()) {
        return Flux.error(new IllegalStateException("Session execution coordinator is closed"));
      }
      SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
      String executionId = UUID.randomUUID().toString();
      long registrationDeadline = System.nanoTime() + registrationTimeoutNanos();
      return withinRegistrationWindow(incrementActiveCount(key), registrationDeadline)
          .flatMapMany(activeCount -> {
            if (activeCount < 0L) {
              return Flux.error(sessionCancelled());
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
            return withinRegistrationWindow(
                    rejectIfCancelled(userId, sessionId), registrationDeadline)
                .thenMany(Flux.defer(source)
                    .doOnSubscribe(execution::attach))
                .doOnError(error -> completeIfNotStarted(key, executionId, execution))
                .doOnCancel(() -> completeIfNotStarted(key, executionId, execution))
                .takeUntilOther(execution.cancelled());
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
      return redisTemplate.opsForValue().set(cancellationKey(key), "1", cancellationTtl)
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
        .flatMap(ignored -> Mono.error(new ResponseStatusException(
            HttpStatus.CONFLICT, "Session is being cancelled")))
        .then();
  }

  @Override
  public void destroy() {
    if (!destroyed.compareAndSet(false, true)) {
      return;
    }
    messageSubscription.dispose();
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
    decrementActiveCount(key).subscribe(ignored -> {}, ignored -> {});
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
      executions.values().forEach(LocalExecution::cancel);
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
    Mono<Void> operation = redisTemplate.opsForValue().get(cancellationKey(key))
        .hasElement()
        .flatMap(cancelled -> {
          if (cancelled) {
            execution.cancel();
          }
          Mono<Boolean> cancellationLease = cancelled || execution.isCancelled()
              ? redisTemplate.opsForValue().set(cancellationKey(key), "1", cancellationTtl)
              : Mono.just(true);
          return cancellationLease
              .filter(Boolean::booleanValue)
              .switchIfEmpty(Mono.error(
                  new IllegalStateException("Failed to renew session cancellation")))
              .then(redisTemplate.expire(activeCountKey(key), activeTtl))
              .doOnNext(stored -> {
                if (stored) {
                  execution.markRenewed();
                } else {
                  execution.cancel();
                }
              })
              .switchIfEmpty(Mono.fromSupplier(() -> {
                execution.cancel();
                return false;
              }))
              .then();
        });
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

  private Mono<Long> incrementActiveCount(SessionExecutionKey key) {
    return redisTemplate.execute(
            INCREMENT_ACTIVE_COUNT,
            List.of(cancellationKey(key), activeCountKey(key), pendingCompletionKey(key)),
            List.of(Long.toString(activeTtl.toMillis())))
        .next()
        .switchIfEmpty(Mono.error(
            new IllegalStateException("Failed to register session execution")));
  }

  private Mono<Long> decrementActiveCount(SessionExecutionKey key) {
    return redisTemplate.execute(
            DECREMENT_ACTIVE_COUNT,
            List.of(activeCountKey(key), pendingCompletionKey(key)),
            List.of(Long.toString(activeTtl.toMillis())))
        .next()
        .defaultIfEmpty(0L);
  }

  private Mono<Long> activeCount(SessionExecutionKey key) {
    return redisTemplate.execute(
            READ_ACTIVE_COUNT,
            List.of(activeCountKey(key), pendingCompletionKey(key)),
            List.of())
        .next()
        .defaultIfEmpty(0L);
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
    return new ResponseStatusException(HttpStatus.CONFLICT, "Session is being cancelled");
  }

  private ResponseStatusException cancellationTimeout() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Session cancellation is still in progress");
  }

  private record CancellationMessage(Long userId, String sessionId) {}

  private static final class LocalExecution {
    private final AtomicBoolean cancelled = new AtomicBoolean();
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

    Mono<Void> cancelled() {
      return cancellationSignal.asMono();
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
