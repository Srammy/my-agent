package com.example.myagent.session;

import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionExecutionCoordinator {
  <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source);

  /**
   * Tracks an execution whose underlying work may outlive cancellation of its event subscription.
   * The completion signal must terminate only after that underlying work and its resources end.
   */
  <T> Flux<T> track(
      Long userId,
      String sessionId,
      Supplier<Flux<T>> source,
      Supplier<Mono<Void>> completion);

  Mono<Void> cancelAndAwait(Long userId, String sessionId);

  Mono<Void> rejectIfCancelled(Long userId, String sessionId);
}
