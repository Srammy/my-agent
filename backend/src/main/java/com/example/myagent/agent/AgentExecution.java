package com.example.myagent.agent;

import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An event stream paired with the actual completion of its underlying execution. */
public record AgentExecution<T>(Flux<T> events, Mono<Void> completion) {

  public AgentExecution {
    Objects.requireNonNull(events, "events");
    Objects.requireNonNull(completion, "completion");
  }
}
