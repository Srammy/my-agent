package com.example.myagent.knowledge;

import com.example.myagent.knowledge.document.KnowledgeDocumentEntity;
import com.example.myagent.knowledge.document.KnowledgeDocumentMapper;
import com.example.myagent.knowledge.document.KnowledgeDocumentStatus;
import com.example.myagent.knowledge.document.KnowledgeDocumentStorage;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.example.myagent.knowledge.etl.KnowledgeDocumentEtlException;
import com.example.myagent.knowledge.etl.KnowledgeDocumentReader;
import com.example.myagent.knowledge.messaging.KnowledgeDocumentProcessMessage;
import com.example.myagent.knowledge.search.KnowledgeIndexService;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentEtlProcessor {

  private final KnowledgeDocumentMapper documentMapper;
  private final KnowledgeDocumentStorage storage;
  private final KnowledgeDocumentReader reader;
  private final KnowledgeIndexService indexService;
  private final KnowledgeDocumentCleanupService cleanupService;

  public KnowledgeDocumentEtlProcessor(
      KnowledgeDocumentMapper documentMapper,
      KnowledgeDocumentStorage storage,
      KnowledgeDocumentReader reader,
      KnowledgeIndexService indexService,
      KnowledgeDocumentCleanupService cleanupService) {
    this.documentMapper = documentMapper;
    this.storage = storage;
    this.reader = reader;
    this.indexService = indexService;
    this.cleanupService = cleanupService;
  }

  public void process(KnowledgeDocumentProcessMessage message) {
    KnowledgeDocumentEntity document = loadOwned(message);
    if (document.getStatus() == KnowledgeDocumentStatus.READY) return;
    try {
      cleanupService.cleanup(message.userId(), message.documentId());
      KnowledgeDocumentContent content =
          reader.read(
              Path.of(document.getStorageKey()),
              document.getUserId(),
              document.getId(),
              document.getOriginalFilename(),
              document.getContentType());
      indexService.index(content);
      KnowledgeDocumentEntity ready = new KnowledgeDocumentEntity();
      ready.setId(document.getId());
      ready.setUserId(document.getUserId());
      ready.setStatus(KnowledgeDocumentStatus.READY);
      ready.setParentCount(content.parents().size());
      ready.setChildCount(content.parents().stream().mapToInt(parent -> parent.children().size()).sum());
      ready.setErrorMessage(null);
      ready.setUpdatedAt(LocalDateTime.now());
      documentMapper.updateById(ready);
      storage.deleteIfExists(Path.of(document.getStorageKey()));
    } catch (RuntimeException error) {
      cleanupService.cleanup(message.userId(), message.documentId());
      throw new KnowledgeDocumentEtlException("Knowledge document ETL failed", error);
    }
  }

  public void markFailed(KnowledgeDocumentProcessMessage message, Throwable error) {
    KnowledgeDocumentEntity document = loadOwned(message);
    cleanupService.cleanup(message.userId(), message.documentId());
    KnowledgeDocumentEntity failed = new KnowledgeDocumentEntity();
    failed.setId(document.getId());
    failed.setUserId(document.getUserId());
    failed.setStatus(KnowledgeDocumentStatus.FAILED);
    failed.setErrorMessage(shortMessage(error));
    failed.setUpdatedAt(LocalDateTime.now());
    documentMapper.updateById(failed);
  }

  private KnowledgeDocumentEntity loadOwned(KnowledgeDocumentProcessMessage message) {
    if (message == null || message.documentId() == null || message.userId() == null) {
      throw new IllegalArgumentException("Knowledge document message is incomplete");
    }
    KnowledgeDocumentEntity document = documentMapper.findOwnedById(message.userId(), message.documentId());
    if (document == null || !message.userId().equals(document.getUserId())) {
      throw new IllegalArgumentException("Knowledge document ownership does not match");
    }
    return document;
  }

  private static String shortMessage(Throwable error) {
    String message = error == null || error.getMessage() == null ? "Unknown ETL failure" : error.getMessage();
    return message.length() > 2000 ? message.substring(0, 2000) : message;
  }
}
