package com.example.myagent.knowledge.document;

import com.example.myagent.auth.CurrentUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class KnowledgeDocumentService {

  private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
  private static final List<String> SUPPORTED_EXTENSIONS =
      List.of("txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg");

  private final KnowledgeDocumentMapper documentMapper;
  private final KnowledgeDocumentStorage storage;
  private final KnowledgeDocumentJobService jobService;

  public KnowledgeDocumentService(
      KnowledgeDocumentMapper documentMapper,
      KnowledgeDocumentStorage storage,
      KnowledgeDocumentJobService jobService) {
    this.documentMapper = documentMapper;
    this.storage = storage;
    this.jobService = jobService;
  }

  public Mono<KnowledgeDocumentDto> upload(CurrentUser currentUser, FilePart file) {
    validate(currentUser, file);
    String documentId = "doc_" + UUID.randomUUID().toString().replace("-", "");
    Path source = storage.sourcePath(currentUser.id(), documentId, file.filename());
    LocalDateTime now = LocalDateTime.now();
    KnowledgeDocumentEntity document = new KnowledgeDocumentEntity();
    document.setId(documentId);
    document.setUserId(currentUser.id());
    document.setOriginalFilename(file.filename());
    document.setContentType(file.headers().getContentType() == null ? null : file.headers().getContentType().toString());
    document.setSizeBytes(null);
    document.setStorageKey(source.toString());
    document.setStatus(KnowledgeDocumentStatus.PROCESSING);
    document.setParentCount(0);
    document.setChildCount(0);
    document.setCreatedAt(now);
    document.setUpdatedAt(now);

    return storage.save(file, source)
        .then(
            Mono.fromCallable(
                () -> {
                  long actualSize = Files.size(source);
                  if (actualSize == 0 || actualSize > MAX_FILE_SIZE) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document file size is invalid");
                  }
                  document.setSizeBytes(actualSize);
                  return document;
                }))
        .then(Mono.fromCallable(() -> jobService.createProcessingDocument(document)))
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(
            error ->
                Mono.fromRunnable(() -> storage.deleteIfExists(source))
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.error(error)));
  }

  public List<KnowledgeDocumentDto> list(CurrentUser currentUser) {
    requireUser(currentUser);
    return documentMapper.findByUserId(currentUser.id()).stream()
        .map(this::repairMissingSize)
        .map(KnowledgeDocumentDto::fromEntity)
        .toList();
  }

  private KnowledgeDocumentEntity repairMissingSize(KnowledgeDocumentEntity document) {
    if (document.getSizeBytes() != null
        && document.getSizeBytes() > 0
        || !StringUtils.hasText(document.getStorageKey())) {
      return document;
    }
    try {
      long actualSize = Files.size(Path.of(document.getStorageKey()));
      if (actualSize > 0) {
        document.setSizeBytes(actualSize);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
      }
    } catch (IOException | InvalidPathException ignored) {
      // Keep the stored value when the source file is no longer available.
    }
    return document;
  }

  private static void validate(CurrentUser currentUser, FilePart file) {
    requireUser(currentUser);
    if (file == null || !StringUtils.hasText(file.filename())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document file is required");
    }
    long size = file.headers().getContentLength();
    if (size > MAX_FILE_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document file size is invalid");
    }
    String filename = file.filename();
    int dot = filename.lastIndexOf('.');
    String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
    if (!SUPPORTED_EXTENSIONS.contains(extension)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document type is not supported");
    }
  }

  private static void requireUser(CurrentUser currentUser) {
    if (currentUser == null || currentUser.id() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user is required");
    }
  }
}
