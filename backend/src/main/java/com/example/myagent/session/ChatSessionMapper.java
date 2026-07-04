package com.example.myagent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

  default List<ChatSessionEntity> findByUserId(Long userId) {
    return selectList(
        Wrappers.<ChatSessionEntity>lambdaQuery()
            .eq(ChatSessionEntity::getUserId, userId)
            .orderByDesc(ChatSessionEntity::getUpdatedAt)
            .orderByDesc(ChatSessionEntity::getCreatedAt));
  }

  default ChatSessionEntity findOwnedById(Long userId, String sessionId) {
    return selectOne(
        Wrappers.<ChatSessionEntity>lambdaQuery()
            .eq(ChatSessionEntity::getUserId, userId)
            .eq(ChatSessionEntity::getId, sessionId)
            .last("limit 1"));
  }

  default int deleteOwnedById(Long userId, String sessionId) {
    return delete(
        Wrappers.<ChatSessionEntity>lambdaQuery()
            .eq(ChatSessionEntity::getUserId, userId)
            .eq(ChatSessionEntity::getId, sessionId));
  }
}
