package com.example.myagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

  @Mock private AuthService authService;

  @Test
  void registerOffloadsBlockingServiceCallToBoundedElasticThread() {
    AuthController controller = new AuthController(authService);
    RegisterRequest request = new RegisterRequest("alice", "secret123", "Alice");
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    when(authService.register(request))
        .thenAnswer(
            (Answer<AuthResponse>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return new AuthResponse("jwt-token");
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.register(request))
                  .expectNext(new AuthResponse("jwt-token"))
                  .verifyComplete();
            },
            "register-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("register-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
    assertThat(serviceThreadName.get()).isNotEqualTo(subscriberThreadName.get());
  }

  @Test
  void loginOffloadsBlockingServiceCallToBoundedElasticThread() {
    AuthController controller = new AuthController(authService);
    LoginRequest request = new LoginRequest("alice", "secret123");
    AtomicReference<String> serviceThreadName = new AtomicReference<>();

    when(authService.login(request))
        .thenAnswer(
            (Answer<AuthResponse>)
                invocation -> {
                  serviceThreadName.set(Thread.currentThread().getName());
                  return new AuthResponse("jwt-token");
                });

    AtomicReference<String> subscriberThreadName = new AtomicReference<>();
    Thread subscriberThread =
        new Thread(
            () -> {
              subscriberThreadName.set(Thread.currentThread().getName());
              StepVerifier.create(controller.login(request))
                  .expectNext(new AuthResponse("jwt-token"))
                  .verifyComplete();
            },
            "login-subscriber");

    subscriberThread.start();
    join(subscriberThread);

    assertThat(subscriberThreadName.get()).isEqualTo("login-subscriber");
    assertThat(serviceThreadName.get()).startsWith("boundedElastic-");
    assertThat(serviceThreadName.get()).isNotEqualTo(subscriberThreadName.get());
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
