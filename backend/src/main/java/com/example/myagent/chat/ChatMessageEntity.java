package com.example.myagent.chat;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("chat_messages")
public class ChatMessageEntity {

  @TableId private String id;
  private String sessionId;
  private Long userId;
  private String role;
  private String content;
  private String eventsJson;
  private Boolean loading;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public ChatMessageEntity() {}

  public ChatMessageEntity(
      String id,
      String sessionId,
      Long userId,
      String role,
      String content,
      String eventsJson,
      Boolean loading,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
    this.id = id;
    this.sessionId = sessionId;
    this.userId = userId;
    this.role = role;
    this.content = content;
    this.eventsJson = eventsJson;
    this.loading = loading;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getEventsJson() {
    return eventsJson;
  }

  public void setEventsJson(String eventsJson) {
    this.eventsJson = eventsJson;
  }

  public Boolean getLoading() {
    return loading;
  }

  public void setLoading(Boolean loading) {
    this.loading = loading;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
