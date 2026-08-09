package com.example.myagent.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.myagent.auth.CurrentUser;
import com.example.myagent.knowledge.KnowledgeDocumentCleanupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Mono;

class KnowledgeDocumentServiceTest {

  @TempDir Path tempDir;

  @Test
  void recordsActualStoredSizeWhenMultipartContentLengthIsZero() throws Exception {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentJobService jobService = mock(KnowledgeDocumentJobService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentService service =
        new KnowledgeDocumentService(documentMapper, storage, jobService, cleanupService);
    FilePart file = mock(FilePart.class);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentLength(0);
    Path target = tempDir.resolve("guide.md");

    when(file.filename()).thenReturn("guide.md");
    when(file.headers()).thenReturn(headers);
    when(storage.sourcePath(eq(7L), any(), eq("guide.md"))).thenReturn(target);
    when(storage.save(file, target))
        .thenAnswer(
            invocation ->
                Mono.fromRunnable(
                    () -> {
                      try {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, "hello knowledge");
                      } catch (Exception error) {
                        throw new IllegalStateException(error);
                      }
                    }));
    when(jobService.createProcessingDocument(any()))
        .thenAnswer(invocation -> KnowledgeDocumentDto.fromEntity(invocation.getArgument(0)));

    KnowledgeDocumentDto result = service.upload(new CurrentUser(7L, "user", "USER"), file).block();

    assertThat(result).isNotNull();
    assertThat(result.sizeBytes()).isEqualTo(15L);
  }

  @Test
  void repairsZeroSizeFromTheStoredSourceFileWhenListingDocuments() throws Exception {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentJobService jobService = mock(KnowledgeDocumentJobService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentService service =
        new KnowledgeDocumentService(documentMapper, storage, jobService, cleanupService);
    Path source = tempDir.resolve("guide.md");
    Files.writeString(source, "hello knowledge");
    KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
    document.setId("doc-1");
    document.setUserId(7L);
    document.setOriginalFilename("guide.md");
    document.setStorageKey(source.toString());
    document.setSizeBytes(0L);
    document.setStatus(KnowledgeDocumentStatus.READY);
    document.setParentCount(1);
    document.setChildCount(1);
    document.setCreatedAt(LocalDateTime.now());
    document.setUpdatedAt(LocalDateTime.now());
    when(documentMapper.findByUserId(7L)).thenReturn(List.of(document));

    List<KnowledgeDocumentDto> result = service.list(new CurrentUser(7L, "user", "USER"));

    assertThat(result).singleElement().extracting(KnowledgeDocumentDto::sizeBytes).isEqualTo(15L);
  }

  @Test
  void deletesOnlyTheAuthenticatedUsersDocumentAndItsSearchData() {
    KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    KnowledgeDocumentStorage storage = mock(KnowledgeDocumentStorage.class);
    KnowledgeDocumentJobService jobService = mock(KnowledgeDocumentJobService.class);
    KnowledgeDocumentCleanupService cleanupService = mock(KnowledgeDocumentCleanupService.class);
    KnowledgeDocumentService service =
        new KnowledgeDocumentService(documentMapper, storage, jobService, cleanupService);
    KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
    document.setId("doc-1");
    document.setUserId(7L);
    document.setStorageKey("C:/knowledge/7/doc-1/source/guide.md");
    when(documentMapper.findOwnedById(7L, "doc-1")).thenReturn(document);

    service.delete(new CurrentUser(7L, "user", "USER"), "doc-1");

    org.mockito.Mockito.verify(cleanupService).cleanup(7L, "doc-1");
    org.mockito.Mockito.verify(storage).deleteIfExists(Path.of(document.getStorageKey()));
    org.mockito.Mockito.verify(jobService).delete(7L, "doc-1");
    org.mockito.Mockito.verify(documentMapper).deleteById("doc-1");
  }
}
