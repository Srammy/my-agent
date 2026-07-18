package com.example.myagent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
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
  static final String CANCELLATION_CHANNEL = "myagent:session-execution:cancel";

  private static final Duration DEFAULT_ACTIVE_TTL = Duration.ofSeconds(30);
  private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(10);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);
  private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);

  private final ReactiveStringRedisTemplate redisTemplate;
  private final ReactiveRedisMessageListenerContainer listenerContainer;
  private final ObjectMapper objectMapper;
  private final Duration activeTtl;
  private final Duration refreshInterval;
  private final Duration pollInterval;
  private final Duration waitTimeout;
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
        DEFAULT_WAIT_TIMEOUT);
  }

  RedisSessionExecutionCoordinator(
      ReactiveStringRedisTemplate redisTemplate,
      ReactiveRedisMessageListenerContainer listenerContainer,
      ObjectMapper objectMapper,
      Duration activeTtl,
      Duration refreshInterval,
      Duration pollInterval,
      Duration waitTimeout) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
    this.objectMapper = objectMapper;
    this.activeTtl = activeTtl;
    this.refreshInterval = refreshInterval;
    this.pollInterval = pollInterval;
    this.waitTimeout = waitTimeout;
    this.messageSubscription = Flux.defer(
            () -> listenerContainer.receive(ChannelTopic.of(CANCELLATION_CHANNEL)))
        .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, pollInterval))
        .subscribe(message -> handleCancellation(message.getMessage()), ignored -> {});
  }

  @Override
  public <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source) {
    return Flux.defer(() -> {
      if (destroyed.get()) {
        return Flux.error(new IllegalStateException("Session execution coordinator is closed"));
      }
      SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
      String executionId = UUID.randomUUID().toString();
      String activeKey = key.activeKey(executionId);
      return rejectIfCancelled(userId, sessionId)
          .then(redisTemplate.opsForValue().set(activeKey, "1", activeTtl))
          .flatMapMany(stored -> {
            if (!stored) {
              return Flux.error(new IllegalStateException("Failed to register session execution"));
            }
            LocalExecution execution = register(key, executionId);
            Disposable heartbeat = Flux.interval(refreshInterval)
                .concatMap(ignored -> heartbeat(key, activeKey, execution))
                .subscribe(ignored -> {}, ignored -> {});
            Disposable watchdog = Flux.interval(refreshInterval)
                .subscribe(ignored -> execution.cancelIfLeaseUnsafe(activeTtl, refreshInterval));
            execution.refreshWith(heartbeat, watchdog);
            return rejectIfCancelled(userId, sessionId)
                .thenMany(Flux.defer(source)
                    .doOnSubscribe(execution::attach))
                .takeUntilOther(execution.cancelled())
                .doFinally(ignored -> cleanup(key, executionId, activeKey, execution));
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
      return redisTemplate.opsForValue().set(key.cancellationKey(), "1")
          .switchIfEmpty(Mono.error(
              new IllegalStateException("Failed to record session cancellation")))
          .flatMap(stored -> {
            if (!stored) {
              return Mono.error(new IllegalStateException("Failed to record session cancellation"));
            }
            cancelLocal(key);
            return redisTemplate.convertAndSend(CANCELLATION_CHANNEL, payload);
          })
          .then(waitForNoActiveExecutions(key));
    }).timeout(waitTimeout, Mono.error(cancellationTimeout()));
  }

  @Override
  public Mono<Void> rejectIfCancelled(Long userId, String sessionId) {
    SessionExecutionKey key = new SessionExecutionKey(userId, sessionId);
    return redisTemplate.opsForValue().get(key.cancellationKey())
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
        executions -> executions.values().forEach(LocalExecution::cancel));
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

  private void cleanup(
      SessionExecutionKey key, String executionId, String activeKey, LocalExecution execution) {
    execution.disposeRefresh();
    localExecutions.computeIfPresent(key, (ignored, executions) -> {
      executions.remove(executionId, execution);
      return executions.isEmpty() ? null : executions;
    });
    redisTemplate.delete(activeKey).subscribe(ignored -> {}, ignored -> {});
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
      SessionExecutionKey key, String activeKey, LocalExecution execution) {
    Mono<Void> operation = redisTemplate.opsForValue().get(key.cancellationKey())
        .hasElement()
        .flatMap(cancelled -> {
          if (cancelled) {
            execution.cancel();
            return Mono.empty();
          }
          return redisTemplate.opsForValue().set(activeKey, "1", activeTtl)
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
        .concatMap(ignored -> redisTemplate.keys(key.prefix() + ":active:*").hasElements())
        .filter(hasActive -> !hasActive)
        .next()
        .then();
  }

  private ResponseStatusException cancellationTimeout() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Session cancellation is still in progress");
  }

  private record CancellationMessage(Long userId, String sessionId) {}

  private static final class LocalExecution {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    private final Disposable.Composite refresh = Disposables.composite();
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
    }

    void refreshWith(Disposable... disposables) {
      for (Disposable disposable : disposables) {
        refresh.add(disposable);
      }
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
  }
}
