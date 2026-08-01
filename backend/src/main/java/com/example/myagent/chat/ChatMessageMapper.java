package com.example.myagent.chat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

  default List<ChatMessageEntity> findByOwnedSession(Long userId, String sessionId) {
    return selectList(
        Wrappers.<ChatMessageEntity>lambdaQuery()
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .orderByAsc(ChatMessageEntity::getCreatedAt)
            .orderByAsc(ChatMessageEntity::getUpdatedAt));
  }

  default ChatMessageEntity findLatestAssistant(Long userId, String sessionId) {
    return selectOne(
        Wrappers.<ChatMessageEntity>lambdaQuery()
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .eq(ChatMessageEntity::getRole, "assistant")
            .orderByDesc(ChatMessageEntity::getCreatedAt)
            .last("limit 1"));
  }

  default int updateContentEventsAndLoading(
      Long userId,
      String messageId,
      String content,
      String eventsJson,
      boolean loading,
      LocalDateTime updatedAt) {
    ChatMessageEntity message = new ChatMessageEntity();
    message.setContent(content);
    message.setEventsJson(eventsJson);
    message.setLoading(loading);
    message.setUpdatedAt(updatedAt);
    return update(
        message,
        Wrappers.<ChatMessageEntity>lambdaUpdate()
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getId, messageId));
  }

  default int deleteByOwnedSession(Long userId, String sessionId) {
    return delete(
        Wrappers.<ChatMessageEntity>lambdaQuery()
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId));
  }
}
