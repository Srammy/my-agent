package com.example.myagent.knowledge.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.example.myagent.config.KnowledgeRetrievalProperties;
import com.example.myagent.config.KnowledgeProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSearchService {

  private static final double RRF_RANK_CONSTANT = 60.0;

  private final ElasticsearchClient client;
  private final KnowledgeProperties properties;
  private final KnowledgeEmbeddingService embeddingService;
  private final KnowledgeRetrievalProperties retrievalProperties;

  public KnowledgeSearchService(
      ElasticsearchClient client,
      KnowledgeProperties properties,
      KnowledgeEmbeddingService embeddingService) {
        this(client, properties, embeddingService, new KnowledgeRetrievalProperties(0.0, 8));
  }

  @Autowired
  public KnowledgeSearchService(
      ElasticsearchClient client,
      KnowledgeProperties properties,
      KnowledgeEmbeddingService embeddingService,
      KnowledgeRetrievalProperties retrievalProperties) {
    this.client = client;
    this.properties = properties;
    this.embeddingService = embeddingService;
    this.retrievalProperties = retrievalProperties;
  }

  public List<KnowledgeSearchHit> search(Long userId, String question) {
    return search(userId, question, retrievalProperties.topK(), List.of());
  }

  public List<KnowledgeSearchHit> search(
      Long userId, String question, int topK, Collection<String> documentIds) {
    if (userId == null || question == null || question.isBlank()) return List.of();
    int limit = Math.max(1, Math.min(topK, 50));
    try {
      float[] vector = embeddingService.embed(question);
      SearchResponse<Map> keywordResponse =
          client.search(
              buildKeywordRequest(userId, question, limit, documentIds), Map.class);
      SearchResponse<Map> vectorResponse =
          client.search(buildVectorRequest(userId, vector, limit, documentIds), Map.class);
      List<Hit<Map>> keywordHits = keywordResponse.hits().hits();
      List<Hit<Map>> vectorHits = vectorResponse.hits().hits();
      if (keywordHits.isEmpty() && vectorHits.isEmpty()) return List.of();

      List<MergeScore> childHits = mergeChildScores(keywordHits, vectorHits, limit);
      if (childHits.isEmpty()) return List.of();

      LinkedHashMap<String, Double> parentScores = new LinkedHashMap<>();
      for (MergeScore child : childHits) {
        Hit<Map> hit = child.hit;
        String parentId = stringValue(hit.source(), "parentId");
        if (parentId != null) {
          parentScores.merge(parentId, child.score, Double::sum);
        }
      }
      if (parentScores.isEmpty()) return List.of();
    List<Map.Entry<String, Double>> rankedParentScores =
        parentScores.entrySet().stream()
            .filter(entry -> entry.getValue() >= retrievalProperties.minRrfScore())
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .toList();
    if (rankedParentScores.isEmpty()) return List.of();
      SearchResponse<Map> parentResponse =
          client.search(
              buildParentRequest(
                  userId,
                  rankedParentScores.stream().map(Map.Entry::getKey).toList(),
                  documentIds),
              Map.class);
      Map<String, Hit<Map>> parents = new LinkedHashMap<>();
      for (Hit<Map> hit : parentResponse.hits().hits()) parents.put(hit.id(), hit);

      List<KnowledgeSearchHit> results = new ArrayList<>();
      for (Map.Entry<String, Double> entry : rankedParentScores) {
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

  SearchRequest buildKeywordRequest(
      Long userId, String question, int limit, Collection<String> documentIds) {
    Query filters = ownershipFilter(userId, documentIds);
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
                                    .filter(filters))));
  }

  SearchRequest buildVectorRequest(
      Long userId, float[] vector, int limit, Collection<String> documentIds) {
    Query filters = ownershipFilter(userId, documentIds);
    List<Float> queryVector = new ArrayList<>(vector.length);
    for (float value : vector) queryVector.add(value);
    return SearchRequest.of(
        request ->
            request
                .index(properties.elasticsearch().childIndex())
                .size(limit)
                .knn(
                    KnnSearch.of(
                        knn ->
                            knn.field("embedding")
                                .queryVector(queryVector)
                                .k((long) limit)
                                .numCandidates((long) Math.max(limit * 4, 50))
                                .filter(filters))));
  }

  List<Hit<Map>> mergeChildHits(
      List<Hit<Map>> keywordHits, List<Hit<Map>> vectorHits, int limit) {
    return mergeChildScores(keywordHits, vectorHits, limit).stream()
        .map(merge -> merge.hit)
        .toList();
  }

  private List<MergeScore> mergeChildScores(
      List<Hit<Map>> keywordHits, List<Hit<Map>> vectorHits, int limit) {
    LinkedHashMap<String, MergeScore> merged = new LinkedHashMap<>();
    mergeHits(keywordHits, merged, 0);
    mergeHits(vectorHits, merged, keywordHits.size());
    return merged.values().stream()
        .sorted(
            (left, right) -> {
              int scoreCompare = Double.compare(right.score, left.score);
              return scoreCompare != 0 ? scoreCompare : Integer.compare(left.firstSeenOrder, right.firstSeenOrder);
            })
        .limit(limit)
        .toList();
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

  private static void mergeHits(
      List<Hit<Map>> hits, Map<String, MergeScore> merged, int startOrder) {
    int rank = 1;
    int order = startOrder;
    for (Hit<Map> hit : hits) {
      String id = hit.id();
      if (id == null) {
        rank++;
        order++;
        continue;
      }
      double score = 1.0 / (RRF_RANK_CONSTANT + rank);
      MergeScore existing = merged.get(id);
      if (existing == null) {
        merged.put(id, new MergeScore(hit, score, order));
      } else {
        existing.score += score;
      }
      rank++;
      order++;
    }
  }

  private static final class MergeScore {
    private final Hit<Map> hit;
    private double score;
    private final int firstSeenOrder;

    private MergeScore(Hit<Map> hit, double score, int firstSeenOrder) {
      this.hit = hit;
      this.score = score;
      this.firstSeenOrder = firstSeenOrder;
    }
  }
}
