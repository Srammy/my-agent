package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import com.example.myagent.knowledge.document.KnowledgeDocumentEntity;
import com.example.myagent.knowledge.document.KnowledgeDocumentMapper;
import com.example.myagent.knowledge.document.KnowledgeDocumentStatus;
import com.example.myagent.knowledge.document.KnowledgeDocumentStorage;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.example.myagent.knowledge.etl.KnowledgeDocumentEtlException;
import com.example.myagent.knowledge.etl.KnowledgeDocumentNonRetryableException;
import com.example.myagent.knowledge.KnowledgeDocumentCleanupService;
import com.example.myagent.knowledge.KnowledgeDocumentEtlProcessor;
import com.example.myagent.knowledge.etl.KnowledgeDocumentReader;
import com.example.myagent.knowledge.search.KnowledgeIndexService;
import com.example.myagent.knowledge.messaging.KnowledgeDocumentProcessMessage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KnowledgeDocumentEtlProcessorTest {

  @Test
  void marksReadyWithCountsAndDeletesSourceOnlyAfterIndexing() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentReader reader = mock(KnowledgeDocumentReader.class);
    KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentEntity document = document(7L, "doc-1");
    KnowledgeDocumentContent content = content(7L, "doc-1");
    when(documentMapper.findOwnedById(7L, "doc-1")).thenReturn(document);
    when(documentMapper.updateById(any(KnowledgeDocumentEntity.class))).thenReturn(1);
    when(reader.read(
            Path.of(document.getStorageKey()),
            7L,
            "doc-1",
            "source.pdf",
            "application/pdf"))
        .thenReturn(content);

    KnowledgeDocumentEtlProcessor processor =
        new KnowledgeDocumentEtlProcessor(
            documentMapper, storage, reader, indexService, cleanupService);

    processor.process(new KnowledgeDocumentProcessMessage("doc-1", 7L));

    InOrder order = inOrder(cleanupService, indexService, documentMapper, storage);
    order.verify(cleanupService).cleanup(7L, "doc-1");
    order.verify(indexService).index(content);
    order.verify(documentMapper)
        .updateById(
            argThat(
                (KnowledgeDocumentEntity updated) ->
                    updated.getId().equals("doc-1")
                        && updated.getUserId().equals(7L)
                        && updated.getStatus() == KnowledgeDocumentStatus.READY
                        && updated.getParentCount() == 0
                        && updated.getChildCount() == 0
                        && updated.getChunkCount() == 2
                        && updated.getErrorMessage() == null));
    order.verify(storage).deleteIfExists(Path.of(document.getStorageKey()));
  }

  @Test
  void cleansExactDocumentAndLeavesSourceWhenIndexingFails() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentReader reader = mock(KnowledgeDocumentReader.class);
    KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentEntity document = document(7L, "doc-1");
    KnowledgeDocumentContent content = content(7L, "doc-1");
    when(documentMapper.findOwnedById(7L, "doc-1")).thenReturn(document);
    when(reader.read(any(), eq(7L), eq("doc-1"), eq("source.pdf"), eq("application/pdf")))
        .thenReturn(content);
    doThrow(new IllegalStateException("elasticsearch unavailable"))
        .when(indexService)
        .index(content);

    KnowledgeDocumentEtlProcessor processor =
        new KnowledgeDocumentEtlProcessor(
            documentMapper, storage, reader, indexService, cleanupService);

    assertThatThrownBy(() -> processor.process(new KnowledgeDocumentProcessMessage("doc-1", 7L)))
        .isInstanceOf(KnowledgeDocumentEtlException.class)
        .hasMessageContaining("Knowledge document ETL failed");

    verify(cleanupService, org.mockito.Mockito.times(2)).cleanup(7L, "doc-1");
    verify(documentMapper, never()).updateById(any(KnowledgeDocumentEntity.class));
    verify(storage, never()).deleteIfExists(any());
  }

  @Test
  void marksMultimodalQuotaFailureAsNonRetryable() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentReader reader = mock(KnowledgeDocumentReader.class);
    KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentEntity document = document(7L, "doc-1");
    when(documentMapper.findOwnedById(7L, "doc-1")).thenReturn(document);
    when(reader.read(any(), eq(7L), eq("doc-1"), eq("source.pdf"), eq("application/pdf")))
        .thenThrow(
            new IllegalStateException(
                "403 AllocationQuota.FreeTierOnly: Free quota exhausted"));

    KnowledgeDocumentEtlProcessor processor =
        new KnowledgeDocumentEtlProcessor(
            documentMapper, storage, reader, indexService, cleanupService);

    assertThatThrownBy(() -> processor.process(new KnowledgeDocumentProcessMessage("doc-1", 7L)))
        .isInstanceOf(KnowledgeDocumentNonRetryableException.class)
        .hasMessageContaining("AllocationQuota.FreeTierOnly");
  }

  @Test
  void marksFailedAfterDltAndKeepsSourceFile() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentReader reader = mock(KnowledgeDocumentReader.class);
    KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentEntity document = document(7L, "doc-1");
    when(documentMapper.findOwnedById(7L, "doc-1")).thenReturn(document);

    KnowledgeDocumentEtlProcessor processor =
        new KnowledgeDocumentEtlProcessor(
            documentMapper, storage, reader, indexService, cleanupService);

    processor.markFailed(
        new KnowledgeDocumentProcessMessage("doc-1", 7L),
        new IllegalStateException("model service unavailable"));

    verify(cleanupService).cleanup(7L, "doc-1");
    verify(documentMapper)
        .updateById(
            argThat(
                (KnowledgeDocumentEntity updated) ->
                    updated.getStatus() == KnowledgeDocumentStatus.FAILED
                        && updated.getErrorMessage().equals("model service unavailable")));
    verify(storage, never()).deleteIfExists(any());
  }

  @Test
  void rejectsMessageWhenPersistentOwnershipDoesNotMatch() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentReader reader = mock(KnowledgeDocumentReader.class);
    KnowledgeIndexService indexService = mock(KnowledgeIndexService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    when(documentMapper.findOwnedById(99L, "doc-1")).thenReturn(null);

    KnowledgeDocumentEtlProcessor processor =
        new KnowledgeDocumentEtlProcessor(
            documentMapper, storage, reader, indexService, cleanupService);

    assertThatThrownBy(() -> processor.process(new KnowledgeDocumentProcessMessage("doc-1", 99L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ownership");

    verifyNoInteractions(storage, reader, indexService, cleanupService);
    verify(documentMapper).findOwnedById(99L, "doc-1");
  }

  private static KnowledgeDocumentEntity document(Long userId, String documentId) {
    KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
    document.setId(documentId);
    document.setUserId(userId);
    document.setOriginalFilename("source.pdf");
    document.setContentType("application/pdf");
    document.setStorageKey("C:/knowledge/" + userId + "/" + documentId + "/source/source.pdf");
    document.setStatus(KnowledgeDocumentStatus.PROCESSING);
    return document;
  }

  private static KnowledgeDocumentContent content(Long userId, String documentId) {
    KnowledgeDocumentContent.ChunkDocument first =
        new KnowledgeDocumentContent.ChunkDocument("doc-1:0", 0, 1, "first", Map.of());
    KnowledgeDocumentContent.ChunkDocument second =
        new KnowledgeDocumentContent.ChunkDocument("doc-1:1", 1, 1, "second", Map.of());
    return new KnowledgeDocumentContent(
        documentId, userId, "source.pdf", "application/pdf", List.of(first, second));
  }
}
