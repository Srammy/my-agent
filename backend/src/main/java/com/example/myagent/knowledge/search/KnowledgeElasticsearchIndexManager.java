package com.example.myagent.knowledge.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
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
      createIfMissing(properties.elasticsearch().parentIndex(), parentProperties());
      createIfMissing(properties.elasticsearch().childIndex(), childProperties());
    } catch (IOException error) {
      throw new IllegalStateException("Unable to initialize knowledge Elasticsearch indexes", error);
    }
  }

  public void writeParents(List<KnowledgeParentDocument> documents) {
    if (documents.isEmpty()) return;
    ensureIndexes();
    try {
      var request =
          new co.elastic.clients.elasticsearch.core.BulkRequest.Builder()
              .operations(
                  documents.stream()
                      .map(
                          document ->
                              BulkOperation.of(
                                  operation ->
                                      operation.index(
                                          index ->
                                              index.index(properties.elasticsearch().parentIndex())
                                                  .id(document.id())
                                                  .document(document))))
                      .toList())
              .build();
      var response = client.bulk(request);
      if (response.errors()) {
        throw new IllegalStateException("Knowledge parent bulk indexing failed");
      }
    } catch (IOException error) {
      throw new IllegalStateException("Unable to write knowledge parent documents", error);
    }
  }

  public void writeChildren(List<KnowledgeChildDocument> documents) {
    if (documents.isEmpty()) return;
    ensureIndexes();
    try {
      var request =
          new co.elastic.clients.elasticsearch.core.BulkRequest.Builder()
              .operations(
                  documents.stream()
                      .map(
                          document ->
                              BulkOperation.of(
                                  operation ->
                                      operation.index(
                                          index ->
                                              index.index(properties.elasticsearch().childIndex())
                                                  .id(document.id())
                                                  .document(document))))
                      .toList())
              .build();
      var response = client.bulk(request);
      if (response.errors()) {
        throw new IllegalStateException("Knowledge child bulk indexing failed");
      }
    } catch (IOException error) {
      throw new IllegalStateException("Unable to write knowledge child documents", error);
    }
  }

  public void deleteByDocument(Long userId, String documentId) {
    try {
      deleteByDocument(properties.elasticsearch().parentIndex(), userId, documentId);
      deleteByDocument(properties.elasticsearch().childIndex(), userId, documentId);
    } catch (IOException error) {
      throw new IllegalStateException("Unable to delete knowledge index records", error);
    }
  }

  private void deleteByDocument(String index, Long userId, String documentId) throws IOException {
    client.deleteByQuery(
        request ->
            request.index(index)
                .query(
                    query ->
                        query.bool(
                            bool ->
                                bool.filter(
                                        filter ->
                                            filter.term(
                                                term -> term.field("userId").value(userId)))
                                    .filter(
                                        filter ->
                                            filter.term(
                                                term -> term.field("documentId").value(documentId))))));
  }

  private void createIfMissing(String index, Map<String, Property> properties) throws IOException {
    if (client.indices().exists(ExistsRequest.of(request -> request.index(index))).value()) {
      return;
    }
    client.indices().create(request -> request.index(index).mappings(mapping -> mapping.properties(properties)));
  }

  private Map<String, Property> parentProperties() {
    return Map.of(
        "userId", Property.of(property -> property.long_(longType -> longType)),
        "documentId", Property.of(property -> property.keyword(keyword -> keyword)),
        "sourceFilename", Property.of(property -> property.keyword(keyword -> keyword)),
        "contentType", Property.of(property -> property.keyword(keyword -> keyword)),
        "parentIndex", Property.of(property -> property.integer(integer -> integer)),
        "pageNumber", Property.of(property -> property.integer(integer -> integer)),
        "content", Property.of(property -> property.text(text -> text)),
        "status", Property.of(property -> property.keyword(keyword -> keyword)));
  }

  private Map<String, Property> childProperties() {
    return Map.of(
        "userId", Property.of(property -> property.long_(longType -> longType)),
        "documentId", Property.of(property -> property.keyword(keyword -> keyword)),
        "parentId", Property.of(property -> property.keyword(keyword -> keyword)),
        "sourceFilename", Property.of(property -> property.keyword(keyword -> keyword)),
        "contentType", Property.of(property -> property.keyword(keyword -> keyword)),
        "childIndex", Property.of(property -> property.integer(integer -> integer)),
        "pageNumber", Property.of(property -> property.integer(integer -> integer)),
        "content", Property.of(property -> property.text(text -> text)),
        "embedding",
        Property.of(
            property ->
                property.denseVector(
                    vector ->
                        vector.dims(properties.embedding().dimensions())
                            .index(true)
                            .similarity("cosine"))),
        "status", Property.of(property -> property.keyword(keyword -> keyword)));
  }
}
