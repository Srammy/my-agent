package com.example.myagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Testcontainers
class SessionExecutionCoordinatorRedisIntegrationTest {
  private static final long USER_ID = 1L;
  private static final String SESSION_ID = "target";
  private static final String OTHER_SESSION_ID = "other";
  private static final String SESSION_PREFIX =
      "myagent:agent-state:session-execution:" + USER_ID + ":" + SESSION_ID;

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redisTemplate;
  private static ObjectMapper objectMapper;

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
    objectMapper = Jackson2ObjectMapperBuilder.json().build();
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void setUp() {
    redisTemplate.getConnectionFactory().getReactiveConnection().serverCommands().flushAll().block();
  }

  @Test
  void cancellationUsesOnlyTheTargetSessionCounterAndFailsClosedOnPendingCompletion()
      throws Exception {
    RedisSessionExecutionCoordinator cancellingCoordinator = coordinator();
    RedisSessionExecutionCoordinator executionCoordinator = coordinator();
    Sinks.Empty<Void> targetCompletion = Sinks.empty();
    Sinks.Empty<Void> otherCompletion = Sinks.empty();
    CountDownLatch targetStarted = new CountDownLatch(1);
    CountDownLatch targetStopped = new CountDownLatch(1);
    CountDownLatch otherStarted = new CountDownLatch(1);
    CountDownLatch otherStopped = new CountDownLatch(1);
    Disposable targetExecution = null;
    Disposable otherExecution = null;
    CompletableFuture<Void> cancellation = null;
    try {
      awaitCoordinatorSubscribers(2);
      targetExecution = executionCoordinator.track(
              USER_ID,
              SESSION_ID,
              () -> Flux.<Integer>never()
                  .doOnSubscribe(ignored -> targetStarted.countDown())
                  .doOnCancel(targetStopped::countDown),
              targetCompletion::asMono)
          .subscribe();
      otherExecution = executionCoordinator.track(
              USER_ID,
              OTHER_SESSION_ID,
              () -> Flux.<Integer>never()
                  .doOnSubscribe(ignored -> otherStarted.countDown())
                  .doOnCancel(otherStopped::countDown),
              otherCompletion::asMono)
          .subscribe();
      assertThat(targetStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(otherStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitValue(SESSION_PREFIX + ":active-count", "1");
      awaitValue(
          "myagent:agent-state:session-execution:"
              + USER_ID + ":" + OTHER_SESSION_ID + ":active-count",
          "1");

      redisTemplate.opsForValue().set("unrelated:key", "value").block();
      redisTemplate.delete(SESSION_PREFIX + ":active-count").block();
      assertThat(redisTemplate.opsForSet()
          .size(SESSION_PREFIX + ":pending-completion").block()).isEqualTo(1L);
      resetCommandStats();

      cancellation = cancellingCoordinator.cancelAndAwait(USER_ID, SESSION_ID).toFuture();
      assertThat(targetStopped.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(cancellation).isNotDone();
      assertThat(otherStopped.getCount()).isEqualTo(1L);

      assertThatThrownBy(() -> executionCoordinator
              .track(USER_ID, SESSION_ID, () -> Flux.just("unexpected"))
              .blockLast())
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

      targetCompletion.tryEmitEmpty();

      cancellation.get(5, TimeUnit.SECONDS);
      assertThat(redisTemplate.opsForValue().get("unrelated:key").block()).isEqualTo("value");
      assertThat(redisTemplate.opsForValue().get(
          "myagent:agent-state:session-execution:"
              + USER_ID + ":" + OTHER_SESSION_ID + ":active-count").block()).isEqualTo("1");
      assertThat(keysCommandStats()).isNull();
    } finally {
      targetCompletion.tryEmitEmpty();
      otherCompletion.tryEmitEmpty();
      if (cancellation != null) {
        cancellation.cancel(true);
      }
      if (targetExecution != null) {
        targetExecution.dispose();
      }
      if (otherExecution != null) {
        otherExecution.dispose();
      }
      executionCoordinator.destroy();
      cancellingCoordinator.destroy();
    }
  }

  private RedisSessionExecutionCoordinator coordinator() {
    return new RedisSessionExecutionCoordinator(redisTemplate, objectMapper);
  }

  private void awaitCoordinatorSubscribers(long expected) {
    Long subscribers = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
        .concatMap(ignored -> redisTemplate.convertAndSend(
            RedisSessionExecutionCoordinator.CANCELLATION_CHANNEL,
            "{\"userId\":-1,\"sessionId\":\"readiness-probe\"}"))
        .filter(count -> count >= expected)
        .next()
        .block(Duration.ofSeconds(5));
    assertThat(subscribers).isNotNull().isGreaterThanOrEqualTo(expected);
  }

  private void awaitValue(String key, String expected) {
    String value = Flux.interval(Duration.ZERO, Duration.ofMillis(25))
        .concatMap(ignored -> redisTemplate.opsForValue().get(key))
        .filter(expected::equals)
        .next()
        .block(Duration.ofSeconds(5));
    assertThat(value).isEqualTo(expected);
  }

  private void resetCommandStats() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      connection.serverCommands().resetConfigStats();
    }
  }

  private String keysCommandStats() {
    try (RedisConnection connection = connectionFactory.getConnection()) {
      Properties stats = connection.serverCommands().info("commandstats");
      return stats.getProperty("cmdstat_keys");
    }
  }
}
