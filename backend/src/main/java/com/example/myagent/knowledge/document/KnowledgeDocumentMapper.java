package com.example.myagent.knowledge.document;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

  default List<KnowledgeDocumentEntity> findByUserId(Long userId) {
    return selectList(
        Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
            .eq(KnowledgeDocumentEntity::getUserId, userId)
            .orderByDesc(KnowledgeDocumentEntity::getCreatedAt));
  }

  default KnowledgeDocumentEntity findOwnedById(Long userId, String documentId) {
    return selectOne(
        Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
            .eq(KnowledgeDocumentEntity::getUserId, userId)
            .eq(KnowledgeDocumentEntity::getId, documentId)
            .last("limit 1"));
  }
}
