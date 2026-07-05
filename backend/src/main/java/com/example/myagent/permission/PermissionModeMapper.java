package com.example.myagent.permission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PermissionModeMapper extends BaseMapper<PermissionModeEntity> {

  default PermissionModeEntity findBySessionId(String sessionId) {
    return selectOne(
        Wrappers.<PermissionModeEntity>lambdaQuery()
            .eq(PermissionModeEntity::getSessionId, sessionId)
            .last("limit 1"));
  }

  @Insert(
      """
      insert into session_permission_modes (session_id, mode, updated_at)
      values (#{sessionId}, #{mode}, now())
      on duplicate key update mode = values(mode), updated_at = values(updated_at)
      """)
  int upsert(@Param("sessionId") String sessionId, @Param("mode") String mode);
}
