package com.example.myagent.knowledge.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentJobMapper extends BaseMapper<KnowledgeDocumentJobEntity> {

  default KnowledgeDocumentJobEntity findOwnedByDocumentId(Long userId, String documentId) {
    return selectOne(
        Wrappers.<KnowledgeDocumentJobEntity>lambdaQuery()
            .eq(KnowledgeDocumentJobEntity::getUserId, userId)
            .eq(KnowledgeDocumentJobEntity::getDocumentId, documentId)
            .last("limit 1"));
  }
}
