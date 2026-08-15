package com.example.myagent.knowledge.document;

import com.example.myagent.knowledge.job.KnowledgeDocumentJobEntity;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobMapper;
import com.example.myagent.knowledge.job.KnowledgeDocumentJobStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDocumentJobService {

  private final KnowledgeDocumentMapper documentMapper;
  private final KnowledgeDocumentJobMapper jobMapper;

  public KnowledgeDocumentJobService(
      KnowledgeDocumentMapper documentMapper, KnowledgeDocumentJobMapper jobMapper) {
    this.documentMapper = documentMapper;
    this.jobMapper = jobMapper;
  }

  @Transactional
  public KnowledgeDocumentDto createProcessingDocument(KnowledgeDocumentEntity document) {
    documentMapper.insert(document);
    KnowledgeDocumentJobEntity job = new KnowledgeDocumentJobEntity();
    job.setId("job_" + document.getId());
    job.setDocumentId(document.getId());
    job.setUserId(document.getUserId());
    job.setStatus(KnowledgeDocumentJobStatus.PENDING);
    job.setAttempts(0);
    job.setCreatedAt(document.getCreatedAt());
    job.setUpdatedAt(document.getUpdatedAt());
    jobMapper.insert(job);
    return KnowledgeDocumentDto.fromEntity(document);
  }

  @Transactional
  public void delete(Long userId, String documentId) {
    KnowledgeDocumentJobEntity job = jobMapper.findOwnedByDocumentId(userId, documentId);
    if (job != null) {
      jobMapper.deleteById(job.getId());
    }
  }

  @Transactional
  public void requeue(Long userId, String documentId, LocalDateTime updatedAt) {
    KnowledgeDocumentJobEntity job = jobMapper.findOwnedByDocumentId(userId, documentId);
    if (job == null || jobMapper.requeue(job.getId(), userId, updatedAt) == 0) {
      throw new IllegalStateException("Knowledge document job is not retryable");
    }
  }
}
