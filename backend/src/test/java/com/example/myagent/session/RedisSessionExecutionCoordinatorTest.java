package com.example.myagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RedisSessionExecutionCoordinatorTest {
  private static final Duration ACTIVE_TTL = Duration.ofSeconds(30);
  private static final Duration CANCELLATION_TTL = Duration.ofSeconds(45);

  @Mock private ReactiveStringRedisTemplate redisTemplate;
  @Mock private ReactiveValueOperations<String, String> valueOperations;
  @Mock private ReactiveRedisMessageListenerContainer listenerContainer;

  private Sinks.Many<ReactiveSubscription.Message<String, String>> messages;
  private Map<String, AtomicLong> activeCounts;
  private RedisSessionExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    messages = Sinks.many().multicast().onBackpressureBuffer();
    activeCounts = new ConcurrentHashMap<>();
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    lenient().when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(true));
    when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(messages.asFlux());
    lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
        .thenAnswer(invocation -> {
          RedisScript<Long> script = invocation.getArgument(0);
          List<String> keys = invocation.getArgument(1);
          AtomicLong count = activeCounts.computeIfAbsent(keys.getFirst(), ignored -> new AtomicLong());
          long updated;
          if (script.getScriptAsString().contains("INCR")) {
            updated = count.incrementAndGet();
          } else if (script.getScriptAsString().contains("DECR")) {
            updated = count.updateAndGet(current -> Math.max(0L, current - 1L));
          } else {
            updated = count.get();
          }
          return Flux.just(updated);
        });
    lenient().when(redisTemplate.expire(anyString(), any(Duration.class)))
        .thenReturn(Mono.just(true));

    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        ACTIVE_TTL,
        Duration.ofSeconds(10),
        Duration.ofMillis(1),
        Duration.ofMillis(100),
        "myagent:agent-state:",
        CANCELLATION_TTL);
  }

  @AfterEach
  void tearDown() {
    coordinator.destroy();
  }

  @Test
  void cancelWaitsForExecutionCompletionInsteadOfSubscriptionCancellation() {
    AtomicLong activeCount = stubActiveCounter();
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    Sinks.Many<Integer> source = Sinks.many().unicast().onBackpressureBuffer();
    Sinks.Empty<Void> actualCompletion = Sinks.empty();
    Disposable execution = coordinator
        .track(1L, "s_1", source::asFlux, actualCompletion::asMono)
        .subscribe();

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .thenAwait(Duration.ofMillis(20))
        .expectNoEvent(Duration.ofMillis(20))
        .then(() -> {
          assertThat(execution.isDisposed()).isTrue();
          assertThat(activeCount).hasValue(1L);
          actualCompletion.tryEmitEmpty();
        })
        .verifyComplete();

    assertThat(activeCount).hasValue(0L);
    verify(redisTemplate).convertAndSend(eq("myagent:session-execution:cancel"), anyString());
    verify(redisTemplate, never()).keys(anyString());
  }

  @Test
  void cancellationMarkerExpiresAfterConfiguredTtl() {
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

    coordinator.cancelAndAwait(1L, "s_1").block();

    verify(valueOperations).set(
        "myagent:agent-state:session-execution:1:s_1:cancelled", "1", CANCELLATION_TTL);
  }

  @Test
  void activeExecutionRenewsCancellationMarkerUntilCompletion() {
    coordinator.destroy();
    Duration cancellationTtl = Duration.ofMillis(40);
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        Duration.ofMillis(100),
        Duration.ofMillis(5),
        Duration.ofMillis(1),
        Duration.ofMillis(80),
        "myagent:agent-state:",
        cancellationTtl);
    String cancellationKey = "myagent:agent-state:session-execution:1:s_1:cancelled";
    AtomicBoolean cancelled = new AtomicBoolean();
    when(valueOperations.get(cancellationKey))
        .thenAnswer(ignored -> cancelled.get() ? Mono.just("1") : Mono.empty());
    when(valueOperations.set(cancellationKey, "1", cancellationTtl))
        .thenAnswer(ignored -> {
          cancelled.set(true);
          return Mono.just(true);
        });
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    Sinks.Empty<Void> completion = Sinks.empty();
    coordinator.track(1L, "s_1", Flux::<Integer>never, completion::asMono).subscribe();

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .expectErrorSatisfies(this::assertCancellationTimeout)
        .verify(Duration.ofSeconds(1));

    verify(valueOperations, timeout(200).atLeast(2)).set(cancellationKey, "1", cancellationTtl);
    completion.tryEmitEmpty();
  }

  @Test
  void differentUsersWithSameSessionIdHaveIndependentCounters() {
    AtomicLong userOneCount = new AtomicLong();
    AtomicLong userTwoCount = new AtomicLong();
    stubActiveCountersByUser(userOneCount, userTwoCount);
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    Sinks.Empty<Void> userOneCompletion = Sinks.empty();
    Sinks.Empty<Void> userTwoCompletion = Sinks.empty();
    coordinator.track(1L, "s_1", Flux::<Integer>never, userOneCompletion::asMono).subscribe();
    Disposable userTwo = coordinator
        .track(2L, "s_1", Flux::<Integer>never, userTwoCompletion::asMono)
        .subscribe();

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .then(() -> userOneCompletion.tryEmitEmpty())
        .verifyComplete();

    assertThat(userOneCount).hasValue(0L);
    assertThat(userTwoCount).hasValue(1L);
    assertThat(userTwo.isDisposed()).isFalse();
    userTwo.dispose();
    userTwoCompletion.tryEmitEmpty();
  }

  @Test
  void trackRejectsExecutionAfterCancellationWasRecorded() {
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    coordinator.cancelAndAwait(1L, "s_1").block();
    when(valueOperations.get("myagent:agent-state:session-execution:1:s_1:cancelled"))
        .thenReturn(Mono.just("1"));

    StepVerifier.create(coordinator.track(1L, "s_1", () -> Flux.just("unexpected")))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOfSatisfying(ResponseStatusException.class,
                status -> assertThat(status.getStatusCode().value()).isEqualTo(409)))
        .verify();
  }

  @Test
  void cancellationMessageCancelsOnlyExactlyMatchingLocalExecution() {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    Disposable matching = coordinator.track(1L, "s_1", Flux::<Integer>never).subscribe();
    Disposable differentSession = coordinator.track(1L, "s_2", Flux::<Integer>never).subscribe();

    messages.tryEmitNext(message("{\"userId\":1,\"sessionId\":\"s_1\"}"));

    assertThat(matching.isDisposed()).isTrue();
    assertThat(differentSession.isDisposed()).isFalse();
    differentSession.dispose();
  }

  @Test
  void trackRenewsTheActiveExecutionTtl() {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        ACTIVE_TTL,
        Duration.ofMillis(1),
        Duration.ofMillis(1),
        Duration.ofMillis(100));
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());

    Disposable subscription = coordinator.track(1L, "s_1", Flux::<Integer>never).subscribe();

    verify(redisTemplate, timeout(500).atLeastOnce()).expire(
        "myagent:agent-state:session-execution:1:s_1:active-count", ACTIVE_TTL);
    subscription.dispose();
  }

  @Test
  void cancelAndAwaitFailsWithConflictWhenActiveExecutionDoesNotDisappear() {
    stubActiveCounter().set(1L);
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOfSatisfying(ResponseStatusException.class, status -> {
              assertThat(status.getStatusCode().value()).isEqualTo(409);
              assertThat(status.getReason()).isEqualTo("Session cancellation is still in progress");
            }))
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void publicConstructorUsesOnlyAutoConfiguredDependencies() {
    assertThatCode(() -> RedisSessionExecutionCoordinator.class.getConstructor(
        ReactiveStringRedisTemplate.class, ObjectMapper.class))
        .doesNotThrowAnyException();
  }

  @Test
  void runningExecutionStopsWhenCancellationMessageWasLostButMarkerExists() throws Exception {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        Duration.ofMillis(100),
        Duration.ofMillis(5),
        Duration.ofMillis(1),
        Duration.ofMillis(200));
    String cancellationKey = "myagent:agent-state:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey))
        .thenReturn(Mono.empty(), Mono.empty(), Mono.just("1"));
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(500, TimeUnit.MILLISECONDS)).isTrue();
  }

  @Test
  void listenerReconnectsAfterSubscriptionFailure() {
    coordinator.destroy();
    reset(listenerContainer);
    when(listenerContainer.receive(any(ChannelTopic.class)))
        .thenReturn(Flux.error(new IllegalStateException("disconnected")), messages.asFlux());

    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        ACTIVE_TTL,
        Duration.ofSeconds(10),
        Duration.ofMillis(1),
        Duration.ofMillis(100));

    verify(listenerContainer, timeout(500).atLeast(2)).receive(any(ChannelTopic.class));
  }

  @Test
  void executionStopsWhenLeaseCannotBeRenewedBeforeSafetyDeadline() throws Exception {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        Duration.ofMillis(50),
        Duration.ofMillis(5),
        Duration.ofMillis(1),
        Duration.ofMillis(200));
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(redisTemplate.expire(anyString(), any(Duration.class)))
        .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(500, TimeUnit.MILLISECONDS)).isTrue();
  }

  @Test
  void cancelAndAwaitTimesOutWhenCancellationMarkerWriteDoesNotComplete() {
    when(valueOperations.set(
        "myagent:agent-state:session-execution:1:s_1:cancelled", "1", CANCELLATION_TTL))
        .thenReturn(Mono.never());

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .expectErrorSatisfies(this::assertCancellationTimeout)
        .verify(Duration.ofSeconds(1));
  }

  @Test
  void cancelAndAwaitFailsWhenCancellationMarkerWriteCompletesEmpty() {
    when(valueOperations.set(
        "myagent:agent-state:session-execution:1:s_1:cancelled", "1", CANCELLATION_TTL))
        .thenReturn(Mono.empty());

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Failed to record session cancellation"))
        .verify(Duration.ofSeconds(1));

    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    verify(redisTemplate, never()).keys(anyString());
  }

  @Test
  void cancelAndAwaitCancelsLocalExecutionBeforePublishCompletes() throws Exception {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        ACTIVE_TTL,
        Duration.ofSeconds(10),
        Duration.ofMillis(1),
        Duration.ofMillis(500));
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.never());
    CountDownLatch stopped = new CountDownLatch(1);
    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    Disposable cancellation = coordinator.cancelAndAwait(1L, "s_1").subscribe(ignored -> {}, ignored -> {});

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    cancellation.dispose();
  }

  @Test
  void destroyStopsLocalExecutionsAndDestroysListenerContainer() throws Exception {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    CountDownLatch stopped = new CountDownLatch(1);
    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    coordinator.destroy();

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    verify(listenerContainer).destroy();
  }

  @Test
  void executionStopsBeforeLeaseExpiresWhenCancellationCheckNeverCompletes() throws Exception {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        Duration.ofMillis(200),
        Duration.ofMillis(20),
        Duration.ofMillis(1),
        Duration.ofMillis(300));
    String cancellationKey = "myagent:agent-state:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey))
        .thenReturn(Mono.empty(), Mono.empty(), Mono.never());
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(stubActiveCounter()).hasValue(1L);
  }

  @Test
  void executionStopsBeforeLeaseExpiresWhenRenewalNeverCompletes() throws Exception {
    coordinator.destroy();
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        Duration.ofMillis(200),
        Duration.ofMillis(20),
        Duration.ofMillis(1),
        Duration.ofMillis(300));
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.never());
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(stubActiveCounter()).hasValue(1L);
  }

  @Test
  void destroyStopsExecutionWhileSecondCancellationCheckIsPending() throws Exception {
    String cancellationKey = "myagent:agent-state:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey)).thenReturn(Mono.empty(), Mono.never());
    AtomicBoolean sourceSubscribed = new AtomicBoolean();
    CountDownLatch terminated = new CountDownLatch(1);
    coordinator.track(1L, "s_1", () -> {
          sourceSubscribed.set(true);
          return Flux.never();
        })
        .doFinally(ignored -> terminated.countDown())
        .subscribe();

    coordinator.destroy();

    assertThat(terminated.await(200, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(sourceSubscribed).isFalse();
    assertThat(stubActiveCounter()).hasValue(0L);
  }

  @Test
  void cancellationMessageDoesNotCancelSameSessionOwnedByDifferentUser() {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    Disposable matching = coordinator.track(1L, "s_1", Flux::<Integer>never).subscribe();
    Disposable differentUser = coordinator.track(2L, "s_1", Flux::<Integer>never).subscribe();

    messages.tryEmitNext(message("{\"userId\":1,\"sessionId\":\"s_1\"}"));

    assertThat(matching.isDisposed()).isTrue();
    assertThat(differentUser.isDisposed()).isFalse();
    differentUser.dispose();
  }

  @Test
  void firstSuccessfulRenewalKeepsDefaultRatioExecutionRunning() throws Exception {
    coordinator.destroy();
    Duration activeTtl = Duration.ofMillis(300);
    Duration refreshInterval = Duration.ofMillis(100);
    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        activeTtl,
        refreshInterval,
        Duration.ofMillis(1),
        Duration.ofMillis(500));
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(redisTemplate.expire(anyString(), eq(activeTtl)))
        .thenReturn(Mono.delay(Duration.ofMillis(20)).thenReturn(true));
    CountDownLatch stopped = new CountDownLatch(1);

    Disposable subscription = coordinator.track(
        1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    verify(redisTemplate, timeout(500).atLeastOnce()).expire(
        "myagent:agent-state:session-execution:1:s_1:active-count", activeTtl);
    assertThat(stopped.await(150, TimeUnit.MILLISECONDS)).isFalse();
    subscription.dispose();
  }

  private void assertCancellationTimeout(Throwable error) {
    assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class, status -> {
      assertThat(status.getStatusCode().value()).isEqualTo(409);
      assertThat(status.getReason()).isEqualTo("Session cancellation is still in progress");
    });
  }

  private AtomicLong stubActiveCounter() {
    return activeCounts.computeIfAbsent(
        "myagent:agent-state:session-execution:1:s_1:active-count",
        ignored -> new AtomicLong());
  }

  private void stubActiveCountersByUser(AtomicLong userOneCount, AtomicLong userTwoCount) {
    activeCounts.put(
        "myagent:agent-state:session-execution:1:s_1:active-count", userOneCount);
    activeCounts.put(
        "myagent:agent-state:session-execution:2:s_1:active-count", userTwoCount);
  }

  private ReactiveSubscription.Message<String, String> message(String payload) {
    return new ReactiveSubscription.Message<>() {
      @Override
      public String getChannel() {
        return RedisSessionExecutionCoordinator.CANCELLATION_CHANNEL;
      }

      @Override
      public String getMessage() {
        return payload;
      }
    };
  }
}
