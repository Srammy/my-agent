package com.example.myagent.knowledge.etl;

import java.nio.file.Path;

public interface KnowledgeDocumentReader {
  KnowledgeDocumentContent read(
      Path source, Long userId, String documentId, String sourceFilename, String contentType);
}
