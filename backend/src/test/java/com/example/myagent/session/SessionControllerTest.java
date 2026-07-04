package com.example.myagent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

  private static final CurrentUser USER = new CurrentUser(1L, "alice", "USER");

  @Mock private SessionService sessionService;

  @Test
  void createOffloadsBlockingServiceCallToBoundedElasticThread() {
    SessionController controller = new SessionController(sessionService);
    CreateSessionRequest request = new CreateSessionRequest("Sprint planning");
    AtomicReference<String> serviceThreadName = new AtomicReference<>();
    ChatSessionDto response =
        new ChatSessionDto("s_123", "Sprint planning", LocalDateTime.now(), LocalDateTime.now());

    when(sessionService.createSession(USER, "Sprint planning"))
        .thenAnswer(
            (Answer<ChatSessionEntity>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return new ChatSessionEntity(
                      response.id(),
                      USER.id(),
                      response.title(),
                      response.createdAt(),
                      response.updatedAt());
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.createSession(USER, request))
                  .expectNext(response)
                  .verifyComplete();
            },
            "session-create-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("session-create-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
  }

  @Test
  void listOffloadsBlockingServiceCallToBoundedElasticThread() {
    SessionController controller = new SessionController(sessionService);
    ChatSessionEntity session =
        new ChatSessionEntity("s_123", USER.id(), "Plan", LocalDateTime.now(), LocalDateTime.now());
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    when(sessionService.listSessions(USER))
        .thenAnswer(
            (Answer<List<ChatSessionEntity>>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return List.of(session);
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.listSessions(USER))
                  .expectNext(
                      List.of(
                          new ChatSessionDto(
                              session.getId(),
                              session.getTitle(),
                              session.getCreatedAt(),
                              session.getUpdatedAt())))
                  .verifyComplete();
            },
            "session-list-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("session-list-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
  }

  @Test
  void getOffloadsBlockingServiceCallToBoundedElasticThread() {
    SessionController controller = new SessionController(sessionService);
    ChatSessionEntity session =
        new ChatSessionEntity("s_123", USER.id(), "Plan", LocalDateTime.now(), LocalDateTime.now());
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    when(sessionService.requireOwnedSession(USER, "s_123"))
        .thenAnswer(
            (Answer<ChatSessionEntity>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return session;
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.getSession(USER, "s_123"))
                  .expectNext(
                      new ChatSessionDto(
                          session.getId(),
                          session.getTitle(),
                          session.getCreatedAt(),
                          session.getUpdatedAt()))
                  .verifyComplete();
            },
            "session-get-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("session-get-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
  }

  @Test
  void deleteOffloadsBlockingServiceCallToBoundedElasticThread() {
    SessionController controller = new SessionController(sessionService);
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    doAnswer(
            invocation -> {
              serviceThreadName.set(Thread.currentThread().getName());
              return null;
            })
        .when(sessionService)
        .deleteSession(USER, "s_123");

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.deleteSession(USER, "s_123")).verifyComplete();
            },
            "session-delete-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("session-delete-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
  }

  private static void join(Thread thread) {
    try {
      thread.join();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for test thread", exception);
    }
  }
}
