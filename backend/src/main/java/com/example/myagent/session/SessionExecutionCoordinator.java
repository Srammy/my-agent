package com.example.myagent.session;

import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionExecutionCoordinator {
  <T> Flux<T> track(Long userId, String sessionId, Supplier<Flux<T>> source);

  Mono<Void> cancelAndAwait(Long userId, String sessionId);

  Mono<Void> rejectIfCancelled(Long userId, String sessionId);
}
