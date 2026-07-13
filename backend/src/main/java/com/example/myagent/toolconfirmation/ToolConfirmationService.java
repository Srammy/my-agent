package com.example.myagent.toolconfirmation;

import com.example.myagent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Service
public class ToolConfirmationService {
  private static final Duration RECORD_TTL = Duration.ofMinutes(30);
  private static final long LEASE_MILLIS = 30_000L;
  private static final DefaultRedisScript<String> CLAIM_SCRIPT = script("""
      local value = redis.call('GET', KEYS[1])
      local ttl = redis.call('PTTL', KEYS[1])
      if not value or ttl <= 0 then return '__NOT_FOUND__' end
      local data = cjson.decode(value)
      if tostring(data.userId) ~= ARGV[1] or data.sessionId ~= ARGV[2] then return '__NOT_OWNED__' end
      if data.status == 'CONSUMED' then return '__CONFLICT__' end
      if data.status == 'PROCESSING' and data.leaseExpiresAtEpochMs > tonumber(ARGV[3]) then return '__CONFLICT__' end
      data.status = 'PROCESSING'
      data.processingToken = ARGV[4]
      data.leaseExpiresAtEpochMs = tonumber(ARGV[3]) + tonumber(ARGV[5])
      local updated = cjson.encode(data)
      redis.call('SET', KEYS[1], updated, 'PX', ttl)
      return updated
      """);
  private static final DefaultRedisScript<String> CONSUME_SCRIPT = script("""
      local value = redis.call('GET', KEYS[1])
      local ttl = redis.call('PTTL', KEYS[1])
      if not value or ttl <= 0 then return '__NOT_FOUND__' end
      local data = cjson.decode(value)
      if data.status ~= 'PROCESSING' or data.processingToken ~= ARGV[1] then return '__CONFLICT__' end
      data.status = 'CONSUMED'
      data.decisions = cjson.decode(ARGV[2])
      data.processingToken = nil
      data.leaseExpiresAtEpochMs = nil
      redis.call('SET', KEYS[1], cjson.encode(data), 'PX', ttl)
      return '__OK__'
      """);
  private static final DefaultRedisScript<String> RELEASE_SCRIPT = script("""
      local value = redis.call('GET', KEYS[1])
      local ttl = redis.call('PTTL', KEYS[1])
      if not value or ttl <= 0 then return '__NOT_FOUND__' end
      local data = cjson.decode(value)
      if data.status ~= 'PROCESSING' or data.processingToken ~= ARGV[1] then return '__CONFLICT__' end
      data.status = 'PENDING'
      data.processingToken = nil
      data.leaseExpiresAtEpochMs = nil
      redis.call('SET', KEYS[1], cjson.encode(data), 'PX', ttl)
      return '__OK__'
      """);

  private final ReactiveStringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final String keyPrefix;

  public ToolConfirmationService(
      ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper, AgentProperties properties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.keyPrefix = properties.stateStore().redis().keyPrefix();
  }

  public Mono<ToolConfirmationRecord> create(
      Long userId, String sessionId, String replyId, List<ToolUseBlock> toolCalls, ConfirmationKind kind) {
    ToolConfirmationRecord record = new ToolConfirmationRecord(
        UUID.randomUUID().toString(), userId.toString(), sessionId, replyId,
        toolCalls.stream().map(ToolCallSnapshot::from).toList(),
        kind, Instant.now(), ToolConfirmationStatus.PENDING, null, null, null);
    final String json;
    try {
      json = objectMapper.writeValueAsString(record);
    } catch (JsonProcessingException error) {
      return Mono.error(error);
    }
    return redisTemplate.opsForValue().set(key(record.confirmationId()), json, RECORD_TTL)
        .flatMap(stored -> stored ? Mono.just(record) : Mono.error(new IllegalStateException("Failed to store tool confirmation")));
  }

  public Mono<ToolConfirmationClaim> claim(Long userId, String sessionId, String confirmationId) {
    String token = UUID.randomUUID().toString();
    List<Object> args = List.of(userId.toString(), sessionId, Long.toString(System.currentTimeMillis()), token,
        Long.toString(LEASE_MILLIS));
    return redisTemplate.execute(CLAIM_SCRIPT, List.of(key(confirmationId)), args)
        .next()
        .switchIfEmpty(Mono.error(notFound()))
        .flatMap(result -> switch (result) {
          case "__NOT_FOUND__", "__NOT_OWNED__" -> Mono.error(notFound());
          case "__CONFLICT__" -> Mono.error(conflict());
          default -> parseClaim(result, token);
        });
  }

  public Mono<Void> release(String confirmationId, String processingToken) {
    return transition(RELEASE_SCRIPT, confirmationId, List.of(processingToken));
  }

  public Mono<Void> consume(
      String confirmationId, String processingToken, List<ToolConfirmationDecision> decisions) {
    try {
      return transition(CONSUME_SCRIPT, confirmationId,
          List.of(processingToken, objectMapper.writeValueAsString(decisions)));
    } catch (JsonProcessingException error) {
      return Mono.error(error);
    }
  }

  private Mono<Void> transition(DefaultRedisScript<String> script, String confirmationId, List<Object> args) {
    return redisTemplate.execute(script, List.of(key(confirmationId)), args).next()
        .switchIfEmpty(Mono.error(notFound()))
        .flatMap(result -> switch (result) {
          case "__OK__" -> Mono.empty();
          case "__NOT_FOUND__" -> Mono.error(notFound());
          default -> Mono.error(conflict());
        });
  }

  private Mono<ToolConfirmationClaim> parseClaim(String json, String token) {
    try {
      ToolConfirmationRecord record = objectMapper.readValue(json, ToolConfirmationRecord.class);
      return Mono.just(new ToolConfirmationClaim(record, token));
    } catch (JsonProcessingException error) {
      return Mono.error(error);
    }
  }

  private String key(String confirmationId) {
    return keyPrefix + "tool-confirmations:" + confirmationId;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  private static ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT);
  }

  private static DefaultRedisScript<String> script(String source) {
    return new DefaultRedisScript<>(source, String.class);
  }
}
