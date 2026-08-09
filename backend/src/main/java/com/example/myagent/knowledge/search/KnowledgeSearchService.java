package com.example.myagent.knowledge.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.example.myagent.config.KnowledgeProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSearchService {

  private static final int DEFAULT_TOP_K = 8;
  private static final long RRF_RANK_CONSTANT = 60L;

  private final ElasticsearchClient client;
  private final KnowledgeProperties properties;
  private final KnowledgeEmbeddingService embeddingService;

  public KnowledgeSearchService(
      ElasticsearchClient client,
      KnowledgeProperties properties,
      KnowledgeEmbeddingService embeddingService) {
    this.client = client;
    this.properties = properties;
    this.embeddingService = embeddingService;
  }

  public List<KnowledgeSearchHit> search(Long userId, String question) {
    return search(userId, question, DEFAULT_TOP_K, List.of());
  }

  public List<KnowledgeSearchHit> search(
      Long userId, String question, int topK, Collection<String> documentIds) {
    if (userId == null || question == null || question.isBlank()) return List.of();
    int limit = Math.max(1, Math.min(topK, 50));
    try {
      float[] vector = embeddingService.embed(question);
      SearchResponse<Map> childResponse =
          client.search(
              buildChildRequest(userId, question, vector, limit, documentIds), Map.class);
      List<Hit<Map>> childHits = childResponse.hits().hits();
      if (childHits.isEmpty()) return List.of();

      LinkedHashMap<String, Double> parentScores = new LinkedHashMap<>();
      for (Hit<Map> hit : childHits) {
        String parentId = stringValue(hit.source(), "parentId");
        if (parentId != null) parentScores.putIfAbsent(parentId, hit.score() == null ? 0.0 : hit.score());
      }
      if (parentScores.isEmpty()) return List.of();
      SearchResponse<Map> parentResponse =
          client.search(buildParentRequest(userId, parentScores.keySet(), documentIds), Map.class);
      Map<String, Hit<Map>> parents = new LinkedHashMap<>();
      for (Hit<Map> hit : parentResponse.hits().hits()) parents.put(hit.id(), hit);

      List<KnowledgeSearchHit> results = new ArrayList<>();
      for (Map.Entry<String, Double> entry : parentScores.entrySet()) {
        Hit<Map> parent = parents.get(entry.getKey());
        if (parent == null) continue;
        Map<String, Object> source = parent.source();
        results.add(
            new KnowledgeSearchHit(
                parent.id(),
                stringValue(source, "documentId"),
                stringValue(source, "sourceFilename"),
                integerValue(source, "pageNumber"),
                stringValue(source, "content"),
                entry.getValue()));
      }
      return results;
    } catch (IOException error) {
      throw new IllegalStateException("Knowledge search failed", error);
    }
  }

  SearchRequest buildChildRequest(
      Long userId, String question, float[] vector, int limit, Collection<String> documentIds) {
    Query filters = ownershipFilter(userId, documentIds);
    List<Float> queryVector = new ArrayList<>(vector.length);
    for (float value : vector) queryVector.add(value);
    return SearchRequest.of(
        request ->
            request
                .index(properties.elasticsearch().childIndex())
                .size(limit)
                .query(
                    query ->
                        query.bool(
                            bool ->
                                bool.must(
                                        must ->
                                            must.match(
                                                match ->
                                                    match.field("content").query(question)))
                                    .filter(filters)))
                .knn(
                    KnnSearch.of(
                        knn ->
                            knn.field("embedding")
                                .queryVector(queryVector)
                                .k((long) limit)
                                .numCandidates((long) Math.max(limit * 4, 50))
                                .filter(filters)))
                .rank(
                    rank ->
                        rank.rrf(
                            rrf ->
                                rrf.rankConstant(RRF_RANK_CONSTANT).windowSize((long) limit))));
  }

  private co.elastic.clients.elasticsearch.core.SearchRequest buildParentRequest(
      Long userId, Collection<String> parentIds, Collection<String> documentIds) {
    Query ownership = ownershipFilter(userId, documentIds);
    return co.elastic.clients.elasticsearch.core.SearchRequest.of(
        request ->
            request
                .index(properties.elasticsearch().parentIndex())
                .size(parentIds.size())
                .query(
                    query ->
                        query.bool(
                            bool ->
                                bool.filter(
                                        ownership)
                                    .filter(
                                        filter ->
                                            filter.ids(ids -> ids.values(parentIds.stream().toList()))))));
  }

  private static Query ownershipFilter(Long userId, Collection<String> documentIds) {
    return Query.of(
        query ->
            query.bool(
                bool -> {
                  bool.filter(
                      filter -> filter.term(term -> term.field("userId").value(userId)));
                  bool.filter(
                      filter -> filter.term(term -> term.field("status").value("READY")));
                  if (documentIds != null && !documentIds.isEmpty()) {
                    bool.filter(
                        filter ->
                            filter.terms(
                                terms ->
                                    terms.field("documentId")
                                        .terms(values -> values.value(documentIds.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList()))));
                  }
                  return bool;
                }));
  }

  private static String stringValue(Map<String, Object> source, String key) {
    if (source == null || source.get(key) == null) return null;
    return source.get(key).toString();
  }

  private static Integer integerValue(Map<String, Object> source, String key) {
    if (source == null || source.get(key) == null) return null;
    Object value = source.get(key);
    return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
  }
}
