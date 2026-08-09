package com.example.myagent.knowledge.document;

import com.example.myagent.config.KnowledgeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class KnowledgeDocumentStorage {

  private final Path root;

  public KnowledgeDocumentStorage(KnowledgeProperties properties) {
    this.root = Path.of(properties.storage().root()).toAbsolutePath().normalize();
  }

  public Path sourcePath(Long userId, String documentId, String filename) {
    String safeFilename = sanitizeFilename(filename);
    Path path = root.resolve(userId.toString()).resolve(documentId).resolve("source").resolve(safeFilename);
    if (!path.normalize().startsWith(root)) {
      throw new IllegalArgumentException("Invalid knowledge document path");
    }
    return path;
  }

  public Mono<Void> save(FilePart file, Path target) {
    return Mono.fromRunnable(
            () -> {
              try {
                Files.createDirectories(target.getParent());
              } catch (IOException error) {
                throw new IllegalStateException("Unable to create knowledge document directory", error);
              }
            })
        .then(file.transferTo(target));
  }

  public void deleteIfExists(Path target) {
    try {
      Files.deleteIfExists(target);
    } catch (IOException error) {
      throw new IllegalStateException("Unable to delete knowledge document source", error);
    }
  }

  private static String sanitizeFilename(String filename) {
    String value = filename == null ? "document" : filename.replace('\\', '/');
    String base = Path.of(value).getFileName().toString();
    String normalized = base.replaceAll("[^a-zA-Z0-9._-]", "_");
    return normalized.isBlank() ? UUID.randomUUID() + ".bin" : normalized;
  }
}
