package com.example.myagent.knowledge.document;

import com.example.myagent.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocumentController {

  private final KnowledgeDocumentService service;

  public KnowledgeDocumentController(KnowledgeDocumentService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Mono<KnowledgeDocumentDto> upload(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestPart("file") FilePart file) {
    return service.upload(currentUser, file);
  }

  @GetMapping
  public Mono<List<KnowledgeDocumentDto>> list(
      @AuthenticationPrincipal CurrentUser currentUser) {
    return Mono.fromCallable(() -> service.list(currentUser))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @DeleteMapping("/{documentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> delete(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String documentId) {
    return Mono.fromRunnable(() -> service.delete(currentUser, documentId))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  @PostMapping("/{documentId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Mono<KnowledgeDocumentDto> retry(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable String documentId) {
    return Mono.fromCallable(() -> service.retry(currentUser, documentId))
        .subscribeOn(Schedulers.boundedElastic());
  }
}
