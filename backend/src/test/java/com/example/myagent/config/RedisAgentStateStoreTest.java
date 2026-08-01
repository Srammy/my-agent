package com.example.myagent.config;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@ExtendWith(MockitoExtension.class)
class RedisAgentStateStoreTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Test
  void stateLookupIsSafeOnReactorNonBlockingThreads() {
    RedisAgentStateStore store = new RedisAgentStateStore(redisTemplate, "prefix:");
    when(redisTemplate.keys("prefix:" + encode("agent") + ":" + encode("session") + ":*"))
        .thenReturn(Set.of());

    assertThatNoException()
        .isThrownBy(
            () ->
                Mono.fromCallable(() -> store.exists("agent", "session"))
                    .subscribeOn(Schedulers.parallel())
                    .block());
  }

  private static String encode(String value) {
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
