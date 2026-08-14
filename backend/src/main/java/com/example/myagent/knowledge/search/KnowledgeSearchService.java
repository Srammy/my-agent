package com.example.myagent.knowledge.search;

import com.example.myagent.config.KnowledgeRetrievalProperties;
import com.example.myagent.knowledge.chunk.KnowledgeChunk;
import com.example.myagent.knowledge.chunk.KnowledgeChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSearchService {

  private final KnowledgeElasticsearchIndexManager elasticsearch;
  private final KnowledgePgVectorService vectorStore;
  private final KnowledgeChunkRepository chunkRepository;
  private final KnowledgeRetrievalProperties retrievalProperties;
  private final ObjectMapper objectMapper;
  private final KnowledgeQueryPlanningService queryPlanningService;

  @Autowired
  public KnowledgeSearchService(
      KnowledgeElasticsearchIndexManager elasticsearch,
      KnowledgePgVectorService vectorStore,
      KnowledgeChunkRepository chunkRepository,
      KnowledgeRetrievalProperties retrievalProperties,
      ObjectMapper objectMapper,
      KnowledgeQueryPlanningService queryPlanningService) {
    this.elasticsearch = elasticsearch;
    this.vectorStore = vectorStore;
    this.chunkRepository = chunkRepository;
    this.retrievalProperties = retrievalProperties;
    this.objectMapper = objectMapper;
    this.queryPlanningService = queryPlanningService;
  }

  public KnowledgeSearchService(
      KnowledgeElasticsearchIndexManager elasticsearch,
      KnowledgePgVectorService vectorStore,
      KnowledgeChunkRepository chunkRepository,
      KnowledgeRetrievalProperties retrievalProperties,
      ObjectMapper objectMapper) {
    this(elasticsearch, vectorStore, chunkRepository, retrievalProperties, objectMapper,
        new KnowledgeQueryPlanningService((org.springframework.ai.chat.model.ChatModel) null, objectMapper));
  }

  public List<KnowledgeSearchHit> search(Long userId, String question) {
    return search(userId, question, retrievalProperties.topK(), List.of());
  }

  public KnowledgeEvidenceBundle searchEvidence(Long userId, String question) {
    return searchEvidence(userId, question, retrievalProperties.topK(), List.of());
  }

  public List<KnowledgeSearchHit> search(
      Long userId, String question, int topK, Collection<String> documentIds) {
    return searchEvidence(userId, question, topK, documentIds).hits();
  }

  public KnowledgeEvidenceBundle searchEvidence(
      Long userId, String question, int topK, Collection<String> documentIds) {
    if (userId == null || question == null || question.isBlank()) return KnowledgeEvidenceBundle.empty();
    int resultLimit = Math.max(1, Math.min(topK, 50));
    List<String> plannedQueries = retrievalProperties.queryPlanningEnabled()
        ? queryPlanningService.plan(question).queries()
        : List.of(question.replaceAll("\\s+", " ").trim());
    Map<String, Candidate> candidates = new LinkedHashMap<>();
    for (String plannedQuery : plannedQueries) {
      addKeywordScores(candidates, elasticsearch.searchKeywords(
          userId, plannedQuery, retrievalProperties.channelTopK(), documentIds));
      addVectorScores(candidates, vectorStore.search(
          userId, plannedQuery, retrievalProperties.channelTopK(), documentIds));
    }
    List<Candidate> ranked = candidates.values().stream()
        .filter(candidate -> candidate.score >= retrievalProperties.minRrfScore())
        .sorted(Comparator.comparingDouble(Candidate::score).reversed())
        .limit(resultLimit)
        .toList();
    if (ranked.isEmpty()) return KnowledgeEvidenceBundle.empty();
    List<KnowledgeSearchHit> hits = expandClustersAndNeighbors(userId, ranked);
    KnowledgeEvidenceLevel level = evaluateEvidence(ranked);
    return new KnowledgeEvidenceBundle(hits, level, guidance(level));
  }

  private void addKeywordScores(Map<String, Candidate> candidates, List<KnowledgeKeywordHit> hits) {
    for (int rank = 0; rank < hits.size(); rank++) {
      KnowledgeKeywordHit hit = hits.get(rank);
      if (hit.chunkId() == null) continue;
      Candidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> Candidate.from(hit));
      candidate.keywordHit = true;
      candidate.score += rrf(rank + 1);
    }
  }

  private void addVectorScores(Map<String, Candidate> candidates, List<KnowledgeVectorHit> hits) {
    for (int rank = 0; rank < hits.size(); rank++) {
      KnowledgeVectorHit hit = hits.get(rank);
      if (hit.chunkId() == null) continue;
      Candidate candidate = candidates.computeIfAbsent(hit.chunkId(), ignored -> Candidate.from(hit));
      candidate.vectorHit = true;
      candidate.score += rrf(rank + 1);
    }
  }

  private double rrf(int rank) {
    return 1.0 / (retrievalProperties.rrfK() + rank);
  }

  private List<KnowledgeSearchHit> expandClustersAndNeighbors(Long userId, List<Candidate> ranked) {
    Map<String, LinkedHashSet<Integer>> anchors = new LinkedHashMap<>();
    for (Candidate candidate : ranked) {
      if (candidate.documentId != null && candidate.chunkIndex != null) {
        anchors.computeIfAbsent(candidate.documentId, ignored -> new LinkedHashSet<>())
            .add(candidate.chunkIndex);
      }
    }
    Map<String, KnowledgeSearchHit> expanded = new LinkedHashMap<>();
    for (Map.Entry<String, LinkedHashSet<Integer>> entry : anchors.entrySet()) {
      List<KnowledgeChunk> chunks = chunkRepository.findByUserAndDocument(userId, entry.getKey());
      Map<Integer, KnowledgeChunk> byIndex = new LinkedHashMap<>();
      for (KnowledgeChunk chunk : chunks) byIndex.put(chunk.chunkIndex(), chunk);
      List<Integer> indexes = entry.getValue().stream().sorted().toList();
      for (int[] cluster : clusters(indexes)) {
        int lower = cluster[0] - Math.max(0, retrievalProperties.neighborWindow());
        int upper = cluster[1] + Math.max(0, retrievalProperties.neighborWindow());
        for (int index = lower; index <= upper; index++) {
          KnowledgeChunk chunk = byIndex.get(index);
          if (chunk == null) continue;
          Candidate source = ranked.stream()
              .filter(candidate -> candidate.chunkId.equals(chunk.chunkId()))
              .findFirst().orElse(null);
          expanded.putIfAbsent(chunk.chunkId(), toSearchHit(chunk, source == null ? 0.0 : source.score));
        }
      }
    }
    return expanded.isEmpty() ? ranked.stream().map(Candidate::toSearchHit).toList()
        : new ArrayList<>(expanded.values());
  }

  private static List<int[]> clusters(List<Integer> indexes) {
    if (indexes.isEmpty()) return List.of();
    List<int[]> result = new ArrayList<>();
    int start = indexes.get(0);
    int end = start;
    for (int index : indexes.subList(1, indexes.size())) {
      if (index == end + 1) {
        end = index;
      } else {
        result.add(new int[] {start, end});
        start = index;
        end = index;
      }
    }
    result.add(new int[] {start, end});
    return result;
  }

  private KnowledgeSearchHit toSearchHit(KnowledgeChunk chunk, double score) {
    Map<String, Object> metadata = readMetadata(chunk.metadataJson());
    return new KnowledgeSearchHit(
        chunk.chunkId(), chunk.documentId(), stringValue(metadata, "sourceFilename"),
        integerValue(metadata, "pageNumber"), chunk.chunkText(), score, chunk.chunkIndex());
  }

  private KnowledgeEvidenceLevel evaluateEvidence(List<Candidate> ranked) {
    if (ranked.isEmpty()) return KnowledgeEvidenceLevel.NONE;
    long both = ranked.stream().filter(candidate -> candidate.keywordHit && candidate.vectorHit).count();
    if (both > 0 && ranked.size() >= 2) return KnowledgeEvidenceLevel.SUFFICIENT;
    if (ranked.size() >= 2) return KnowledgeEvidenceLevel.PARTIAL;
    return KnowledgeEvidenceLevel.WEAK;
  }

  private static String guidance(KnowledgeEvidenceLevel level) {
    return switch (level) {
      case NONE -> "当前没有可用证据，必须拒答。";
      case WEAK -> "当前证据有限，只能谨慎回答，不能给出超出证据的确定性结论。";
      case PARTIAL -> "当前证据只覆盖部分问题，只回答证据明确支持的部分。";
      case SUFFICIENT -> "当前证据较充分，但仍不得超出证据进行推测。";
    };
  }

  private Map<String, Object> readMetadata(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try { return objectMapper.readValue(json, Map.class); }
    catch (Exception ignored) { return Map.of(); }
  }

  private static String stringValue(Map<String, Object> values, String key) {
    Object value = values.get(key);
    return value == null ? null : value.toString();
  }

  private static Integer integerValue(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (value == null) return null;
    return value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString());
  }

  private static final class Candidate {
    private final String chunkId;
    private final String documentId;
    private final Integer chunkIndex;
    private final Integer pageNumber;
    private final String sourceFilename;
    private final String content;
    private double score;
    private boolean keywordHit;
    private boolean vectorHit;

    private Candidate(String chunkId, String documentId, Integer chunkIndex, Integer pageNumber,
        String sourceFilename, String content) {
      this.chunkId = chunkId;
      this.documentId = documentId;
      this.chunkIndex = chunkIndex;
      this.pageNumber = pageNumber;
      this.sourceFilename = sourceFilename;
      this.content = content;
    }

    private static Candidate from(KnowledgeKeywordHit hit) {
      return new Candidate(hit.chunkId(), hit.documentId(), hit.chunkIndex(), hit.pageNumber(),
          hit.sourceFilename(), hit.content());
    }

    private static Candidate from(KnowledgeVectorHit hit) {
      return new Candidate(hit.chunkId(), hit.documentId(), hit.chunkIndex(), hit.pageNumber(),
          hit.sourceFilename(), hit.content());
    }

    private double score() { return score; }

    private KnowledgeSearchHit toSearchHit() {
      return new KnowledgeSearchHit(chunkId, documentId, sourceFilename, pageNumber, content, score, chunkIndex);
    }
  }
}
