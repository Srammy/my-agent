package com.example.myagent.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("user_skill_settings")
public class UserSkillSettingEntity {

  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long skillId;
  private Boolean enabled;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Long getSkillId() {
    return skillId;
  }

  public void setSkillId(Long skillId) {
    this.skillId = skillId;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }
}
