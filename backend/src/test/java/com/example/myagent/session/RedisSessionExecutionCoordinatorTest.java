package com.example.myagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class RedisSessionExecutionCoordinatorTest {
  private static final Duration ACTIVE_TTL = Duration.ofSeconds(30);

  @Mock private ReactiveStringRedisTemplate redisTemplate;
  @Mock private ReactiveValueOperations<String, String> valueOperations;
  @Mock private ReactiveRedisMessageListenerContainer listenerContainer;

  private Sinks.Many<ReactiveSubscription.Message<String, String>> messages;
  private RedisSessionExecutionCoordinator coordinator;

  @BeforeEach
  void setUp() {
    messages = Sinks.many().multicast().onBackpressureBuffer();
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(messages.asFlux());

    coordinator = new RedisSessionExecutionCoordinator(
        redisTemplate,
        listenerContainer,
        new ObjectMapper(),
        ACTIVE_TTL,
        Duration.ofSeconds(10),
        Duration.ofMillis(1),
        Duration.ofMillis(100));
  }

  @AfterEach
  void tearDown() {
    coordinator.destroy();
  }

  @Test
  void cancelAndAwaitCancelsLocalExecutionAndWaitsForCleanup() {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    when(redisTemplate.keys(anyString())).thenReturn(Flux.empty());
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    Sinks.Many<Integer> source = Sinks.many().unicast().onBackpressureBuffer();
    Disposable subscription = coordinator.track(1L, "s_1", () -> source.asFlux()).subscribe();

    coordinator.cancelAndAwait(1L, "s_1").block();

    assertThat(subscription.isDisposed()).isTrue();
    verify(valueOperations).set(
        matches("myagent:session-execution:1:s_1:active:.+"), eq("1"), eq(ACTIVE_TTL));
    verify(redisTemplate).delete(matches("myagent:session-execution:1:s_1:active:.+"));
    verify(redisTemplate).convertAndSend(eq("myagent:session-execution:cancel"), anyString());
  }

  @Test
  void trackRejectsExecutionAfterCancellationWasRecorded() {
    when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    when(redisTemplate.keys(anyString())).thenReturn(Flux.empty());
    coordinator.cancelAndAwait(1L, "s_1").block();
    when(valueOperations.get("myagent:session-execution:1:s_1:cancelled")).thenReturn(Mono.just("1"));

    StepVerifier.create(coordinator.track(1L, "s_1", () -> Flux.just("unexpected")))
        .expectErrorSatisfies(error -> assertThat(error)
            .isInstanceOfSatisfying(ResponseStatusException.class,
                status -> assertThat(status.getStatusCode().value()).isEqualTo(409)))
        .verify();
  }

  @Test
  void cancellationMessageCancelsOnlyExactlyMatchingLocalExecution() {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
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
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));

    Disposable subscription = coordinator.track(1L, "s_1", Flux::<Integer>never).subscribe();

    verify(valueOperations, timeout(500).atLeast(2)).set(
        matches("myagent:session-execution:1:s_1:active:.+"), eq("1"), eq(ACTIVE_TTL));
    subscription.dispose();
  }

  @Test
  void cancelAndAwaitFailsWithConflictWhenActiveExecutionDoesNotDisappear() {
    when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
    when(redisTemplate.keys(anyString())).thenReturn(Flux.just("still-active"));

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
    String cancellationKey = "myagent:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey))
        .thenReturn(Mono.empty(), Mono.empty(), Mono.just("1"));
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
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
    when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(true), Mono.error(new IllegalStateException("redis unavailable")));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(500, TimeUnit.MILLISECONDS)).isTrue();
  }

  @Test
  void cancelAndAwaitTimesOutWhenCancellationMarkerWriteDoesNotComplete() {
    when(valueOperations.set("myagent:session-execution:1:s_1:cancelled", "1"))
        .thenReturn(Mono.never());

    StepVerifier.create(coordinator.cancelAndAwait(1L, "s_1"))
        .expectErrorSatisfies(this::assertCancellationTimeout)
        .verify(Duration.ofSeconds(1));
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
    when(valueOperations.set(anyString(), anyString())).thenReturn(Mono.just(true));
    when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.never());
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    CountDownLatch stopped = new CountDownLatch(1);
    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    Disposable cancellation = coordinator.cancelAndAwait(1L, "s_1").subscribe(ignored -> {}, ignored -> {});

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    cancellation.dispose();
  }

  @Test
  void destroyStopsLocalExecutionsAndDestroysListenerContainer() throws Exception {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
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
    String cancellationKey = "myagent:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey))
        .thenReturn(Mono.empty(), Mono.empty(), Mono.never());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
    verify(redisTemplate, timeout(200)).delete(
        matches("myagent:session-execution:1:s_1:active:.+"));
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
    when(valueOperations.set(anyString(), anyString(), any(Duration.class)))
        .thenReturn(Mono.just(true), Mono.never());
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    CountDownLatch stopped = new CountDownLatch(1);

    coordinator.track(1L, "s_1", () -> Flux.<Integer>never().doOnCancel(stopped::countDown)).subscribe();

    assertThat(stopped.await(200, TimeUnit.MILLISECONDS)).isTrue();
  }

  @Test
  void destroyStopsExecutionWhileSecondCancellationCheckIsPending() throws Exception {
    String cancellationKey = "myagent:session-execution:1:s_1:cancelled";
    when(valueOperations.get(cancellationKey)).thenReturn(Mono.empty(), Mono.never());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
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
    verify(redisTemplate, timeout(200)).delete(
        matches("myagent:session-execution:1:s_1:active:.+"));
  }

  @Test
  void cancellationMessageDoesNotCancelSameSessionOwnedByDifferentUser() {
    when(valueOperations.get(anyString())).thenReturn(Mono.empty());
    when(valueOperations.set(anyString(), anyString(), any(Duration.class))).thenReturn(Mono.just(true));
    when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    Disposable matching = coordinator.track(1L, "s_1", Flux::<Integer>never).subscribe();
    Disposable differentUser = coordinator.track(2L, "s_1", Flux::<Integer>never).subscribe();

    messages.tryEmitNext(message("{\"userId\":1,\"sessionId\":\"s_1\"}"));

    assertThat(matching.isDisposed()).isTrue();
    assertThat(differentUser.isDisposed()).isFalse();
    differentUser.dispose();
  }

  private void assertCancellationTimeout(Throwable error) {
    assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class, status -> {
      assertThat(status.getStatusCode().value()).isEqualTo(409);
      assertThat(status.getReason()).isEqualTo("Session cancellation is still in progress");
    });
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
