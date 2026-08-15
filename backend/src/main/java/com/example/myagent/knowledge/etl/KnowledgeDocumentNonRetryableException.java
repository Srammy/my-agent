package com.example.myagent.knowledge.etl;

public class KnowledgeDocumentNonRetryableException extends RuntimeException {
  public KnowledgeDocumentNonRetryableException(String message, Throwable cause) {
    super(message, cause);
  }
}
