package com.example.myagent.knowledge.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.example.myagent.config.KnowledgeProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeElasticsearchIndexManager {

  private final ElasticsearchClient client;
  private final KnowledgeProperties properties;

  public KnowledgeElasticsearchIndexManager(
      ElasticsearchClient client, KnowledgeProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  public void ensureIndexes() {
    try {
      createIfMissing(properties.elasticsearch().chunkIndex());
    } catch (IOException error) {
      throw new IllegalStateException("Unable to initialize knowledge Elasticsearch index", error);
    }
  }

  public void writeChunks(List<KnowledgeChunkIndexDocument> documents) {
    if (documents.isEmpty()) return;
    ensureIndexes();
    try {
      var request = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder()
          .operations(documents.stream().map(document -> BulkOperation.of(operation ->
              operation.index(index -> index.index(properties.elasticsearch().chunkIndex())
                  .id(document.chunkId()).document(document)))).toList())
          .build();
      var response = client.bulk(request);
      if (response.errors()) throw new IllegalStateException("Knowledge chunk bulk indexing failed");
    } catch (IOException error) {
      throw new IllegalStateException("Unable to write knowledge chunk documents", error);
    }
  }

  public void deleteByDocument(Long userId, String documentId) {
    try {
      var response = client.deleteByQuery(request -> request
          .index(properties.elasticsearch().chunkIndex())
          .ignoreUnavailable(true)
          .query(query -> query.bool(bool -> bool
              .filter(filter -> filter.term(term -> term.field("userId").value(userId)))
              .filter(filter -> filter.term(term -> term.field("documentId").value(documentId))))));
      if (Boolean.TRUE.equals(response.timedOut())
          || (response.versionConflicts() != null && response.versionConflicts() > 0)
          || (response.failures() != null && !response.failures().isEmpty())) {
        throw new IllegalStateException("Knowledge chunk index cleanup did not complete");
      }
    } catch (IOException error) {
      throw new IllegalStateException("Unable to delete knowledge chunk index records", error);
    }
  }

  public List<KnowledgeKeywordHit> searchKeywords(
      Long userId, String question, int topK, java.util.Collection<String> documentIds) {
    if (userId == null || question == null || question.isBlank() || topK <= 0) return List.of();
    try {
      SearchResponse<Map> response = client.search(request -> request
          .index(properties.elasticsearch().chunkIndex())
          .size(topK)
          .query(query -> query.bool(bool -> {
            bool.filter(filter -> filter.term(term -> term.field("userId").value(userId)));
            bool.filter(filter -> filter.term(term -> term.field("status").value("READY")));
            if (documentIds != null && !documentIds.isEmpty()) {
              bool.filter(filter -> filter.terms(terms -> terms.field("documentId")
                  .terms(values -> values.value(documentIds.stream()
                      .map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
            }
            bool.should(should -> should.matchPhrase(phrase -> phrase.field("content").query(question).boost(3.0f)));
            bool.should(should -> should.match(match -> match.field("content").query(question)));
            bool.minimumShouldMatch("1");
            return bool;
          })), Map.class);
      return response.hits().hits().stream().map(hit -> {
        Map source = hit.source();
        return new KnowledgeKeywordHit(
            hit.id(), stringValue(source, "documentId"), integerValue(source, "chunkIndex"),
            integerValue(source, "pageNumber"), stringValue(source, "sourceFilename"),
            stringValue(source, "content"));
      }).toList();
    } catch (IOException error) {
      throw new IllegalStateException("Knowledge keyword search failed", error);
    }
  }

  private static String stringValue(Map source, String key) {
    return source == null || source.get(key) == null ? null : source.get(key).toString();
  }

  private static Integer integerValue(Map source, String key) {
    if (source == null || source.get(key) == null) return null;
    Object value = source.get(key);
    return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
  }

  private void createIfMissing(String index) throws IOException {
    if (client.indices().exists(ExistsRequest.of(request -> request.index(index))).value()) return;
    client.indices().create(request -> request.index(index)
        .mappings(mapping -> mapping.properties(Map.of(
            "userId", Property.of(property -> property.long_(value -> value)),
            "documentId", Property.of(property -> property.keyword(value -> value)),
            "chunkId", Property.of(property -> property.keyword(value -> value)),
            "chunkIndex", Property.of(property -> property.integer(value -> value)),
            "pageNumber", Property.of(property -> property.integer(value -> value)),
            "sourceFilename", Property.of(property -> property.text(value -> value)),
            "content", Property.of(property -> property.text(value -> value)),
            "status", Property.of(property -> property.keyword(value -> value))))));
  }
}
