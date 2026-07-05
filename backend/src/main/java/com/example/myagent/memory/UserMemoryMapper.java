package com.example.myagent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemoryEntity> {

  default String findSummaryByUserId(Long userId) {
    UserMemoryEntity entity =
        selectOne(
            Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getUserId, userId)
                .isNull(UserMemoryEntity::getMemoryDate)
                .orderByDesc(UserMemoryEntity::getUpdatedAt)
                .last("limit 1"));
    return entity == null ? null : entity.getContent();
  }

  default List<String> findDailyByUserId(Long userId) {
    return selectList(
            Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getUserId, userId)
                .isNotNull(UserMemoryEntity::getMemoryDate)
                .orderByDesc(UserMemoryEntity::getMemoryDate)
                .orderByDesc(UserMemoryEntity::getUpdatedAt))
        .stream()
        .map(UserMemoryEntity::getContent)
        .toList();
  }

  default String findDailyByUserIdAndDate(Long userId, LocalDate date) {
    UserMemoryEntity entity =
        selectOne(
            Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getUserId, userId)
                .eq(UserMemoryEntity::getMemoryDate, date)
                .orderByDesc(UserMemoryEntity::getUpdatedAt)
                .last("limit 1"));
    return entity == null ? null : entity.getContent();
  }
}
