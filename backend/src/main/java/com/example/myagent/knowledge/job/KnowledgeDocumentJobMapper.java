package com.example.myagent.knowledge.job;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KnowledgeDocumentJobMapper extends BaseMapper<KnowledgeDocumentJobEntity> {

  default List<KnowledgeDocumentJobEntity> findClaimable(LocalDateTime now, int limit) {
    return selectList(
        Wrappers.<KnowledgeDocumentJobEntity>lambdaQuery()
            .eq(KnowledgeDocumentJobEntity::getStatus, KnowledgeDocumentJobStatus.PENDING)
            .and(
                query ->
                    query.isNull(KnowledgeDocumentJobEntity::getClaimedUntil)
                        .or()
                        .lt(KnowledgeDocumentJobEntity::getClaimedUntil, now))
            .orderByAsc(KnowledgeDocumentJobEntity::getCreatedAt)
            .last("limit " + Math.max(1, Math.min(limit, 100))));
  }

  default int claim(String id, Long userId, String claimToken, LocalDateTime claimedUntil) {
    KnowledgeDocumentJobEntity update = new KnowledgeDocumentJobEntity();
    update.setClaimedUntil(claimedUntil);
    update.setClaimToken(claimToken);
    return update(
        update,
        Wrappers.<KnowledgeDocumentJobEntity>lambdaUpdate()
            .eq(KnowledgeDocumentJobEntity::getId, id)
            .eq(KnowledgeDocumentJobEntity::getUserId, userId)
            .eq(KnowledgeDocumentJobEntity::getStatus, KnowledgeDocumentJobStatus.PENDING)
            .and(
                query ->
                    query.isNull(KnowledgeDocumentJobEntity::getClaimedUntil)
                        .or()
                        .lt(
                            KnowledgeDocumentJobEntity::getClaimedUntil,
                            claimedUntil.minusMinutes(2))));
  }

  default int markSent(String id, Long userId, String claimToken, LocalDateTime updatedAt) {
    KnowledgeDocumentJobEntity update = new KnowledgeDocumentJobEntity();
    update.setStatus(KnowledgeDocumentJobStatus.SENT);
    update.setClaimedUntil(null);
    update.setUpdatedAt(updatedAt);
    return update(
        update,
        Wrappers.<KnowledgeDocumentJobEntity>lambdaUpdate()
            .eq(KnowledgeDocumentJobEntity::getId, id)
            .eq(KnowledgeDocumentJobEntity::getUserId, userId)
            .eq(KnowledgeDocumentJobEntity::getStatus, KnowledgeDocumentJobStatus.PENDING)
            .eq(KnowledgeDocumentJobEntity::getClaimToken, claimToken));
  }

  default int markFailure(
      String id,
      Long userId,
      String claimToken,
      int attempts,
      String error,
      boolean terminal,
      LocalDateTime updatedAt) {
    KnowledgeDocumentJobEntity update = new KnowledgeDocumentJobEntity();
    update.setStatus(terminal ? KnowledgeDocumentJobStatus.FAILED : KnowledgeDocumentJobStatus.PENDING);
    update.setAttempts(attempts);
    update.setLastError(error);
    update.setClaimedUntil(null);
    update.setUpdatedAt(updatedAt);
    return update(
        update,
        Wrappers.<KnowledgeDocumentJobEntity>lambdaUpdate()
            .eq(KnowledgeDocumentJobEntity::getId, id)
            .eq(KnowledgeDocumentJobEntity::getUserId, userId)
            .eq(KnowledgeDocumentJobEntity::getStatus, KnowledgeDocumentJobStatus.PENDING)
            .eq(KnowledgeDocumentJobEntity::getClaimToken, claimToken));
  }

  default KnowledgeDocumentJobEntity findOwnedByDocumentId(Long userId, String documentId) {
    return selectOne(
        Wrappers.<KnowledgeDocumentJobEntity>lambdaQuery()
            .eq(KnowledgeDocumentJobEntity::getUserId, userId)
            .eq(KnowledgeDocumentJobEntity::getDocumentId, documentId)
            .last("limit 1"));
  }
}
