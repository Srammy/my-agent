package com.example.myagent.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

  default UserEntity findByUsername(String username) {
    return selectOne(
        Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username).last("limit 1"));
  }
}
