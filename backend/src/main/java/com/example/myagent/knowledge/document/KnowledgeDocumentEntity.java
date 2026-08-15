package com.example.myagent.knowledge.document;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("knowledge_documents")
public class KnowledgeDocumentEntity {

  @TableId private String id;
  private Long userId;
  private String originalFilename;
  private String contentType;
  private Long sizeBytes;
  private String storageKey;
  private KnowledgeDocumentStatus status;
  private Integer parentCount;
  private Integer childCount;
  private Integer chunkCount;
  private String errorMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public String getOriginalFilename() { return originalFilename; }
  public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }
  public Long getSizeBytes() { return sizeBytes; }
  public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
  public String getStorageKey() { return storageKey; }
  public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
  public KnowledgeDocumentStatus getStatus() { return status; }
  public void setStatus(KnowledgeDocumentStatus status) { this.status = status; }
  public Integer getParentCount() { return parentCount; }
  public void setParentCount(Integer parentCount) { this.parentCount = parentCount; }
  public Integer getChildCount() { return childCount; }
  public void setChildCount(Integer childCount) { this.childCount = childCount; }
  public Integer getChunkCount() { return chunkCount; }
  public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
  public String getErrorMessage() { return errorMessage; }
  public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
