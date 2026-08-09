package com.example.myagent.knowledge.job;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("knowledge_document_jobs")
public class KnowledgeDocumentJobEntity {

  @TableId private String id;
  private String documentId;
  private Long userId;
  private KnowledgeDocumentJobStatus status;
  private Integer attempts;
  private String lastError;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime claimedUntil;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getDocumentId() { return documentId; }
  public void setDocumentId(String documentId) { this.documentId = documentId; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public KnowledgeDocumentJobStatus getStatus() { return status; }
  public void setStatus(KnowledgeDocumentJobStatus status) { this.status = status; }
  public Integer getAttempts() { return attempts; }
  public void setAttempts(Integer attempts) { this.attempts = attempts; }
  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
  public LocalDateTime getClaimedUntil() { return claimedUntil; }
  public void setClaimedUntil(LocalDateTime claimedUntil) { this.claimedUntil = claimedUntil; }
}
