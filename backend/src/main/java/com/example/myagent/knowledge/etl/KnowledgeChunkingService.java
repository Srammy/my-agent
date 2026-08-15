package com.example.myagent.knowledge.etl;

import com.example.myagent.config.KnowledgeProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class KnowledgeChunkingService {

  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.+$");

  private final KnowledgeProperties.Chunking properties;

  public KnowledgeChunkingService(KnowledgeProperties properties) {
    this.properties = properties.chunking();
    if (this.properties.targetTokens() <= 0
        || this.properties.maxTokens() < this.properties.targetTokens()
        || this.properties.overlapTokens() < 0) {
      throw new IllegalArgumentException("Invalid knowledge chunking configuration");
    }
  }

  public List<KnowledgeDocumentContent.ChunkDocument> chunk(
      String documentId,
      Long userId,
      String filename,
      String contentType,
      String text,
      Integer pageNumber,
      int firstChunkIndex) {
    if (!StringUtils.hasText(text)) return List.of();
    String normalized = normalize(text);
    List<TextUnit> units = splitUnits(normalized);
    List<KnowledgeDocumentContent.ChunkDocument> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int currentStart = 0;
    int cursor = 0;
    int chunkIndex = firstChunkIndex;
    for (TextUnit unit : units) {
      if (current.isEmpty()) currentStart = unit.start();
      if (current.length() > 0
          && current.length() + unit.text().length() + 1 > properties.maxTokens()) {
        result.add(build(documentId, userId, filename, contentType, pageNumber, chunkIndex++, currentStart, cursor, current));
        String overlap = tail(current.toString(), properties.overlapTokens());
        current.setLength(0);
        if (!overlap.isBlank()) {
          current.append(overlap).append(' ');
          currentStart = Math.max(0, cursor - overlap.length());
        } else {
          currentStart = unit.start();
        }
      }
      if (unit.text().length() > properties.maxTokens()) {
        if (current.length() > 0) {
          result.add(build(documentId, userId, filename, contentType, pageNumber, chunkIndex++, currentStart, cursor, current));
          current.setLength(0);
        }
        for (String part : hardSplit(unit.text(), properties.maxTokens())) {
          result.add(build(documentId, userId, filename, contentType, pageNumber, chunkIndex++, unit.start(), unit.start() + part.length(), new StringBuilder(part)));
        }
        cursor = unit.end();
        continue;
      }
      if (current.length() > 0) current.append(' ');
      current.append(unit.text());
      cursor = unit.end();
      if (current.length() >= properties.targetTokens()) {
        result.add(build(documentId, userId, filename, contentType, pageNumber, chunkIndex++, currentStart, cursor, current));
        String overlap = tail(current.toString(), properties.overlapTokens());
        current.setLength(0);
        if (!overlap.isBlank()) {
          current.append(overlap);
          currentStart = Math.max(0, cursor - overlap.length());
        }
      }
    }
    if (!current.toString().isBlank()) {
      result.add(build(documentId, userId, filename, contentType, pageNumber, chunkIndex, currentStart, normalized.length(), current));
    }
    return result;
  }

  private KnowledgeDocumentContent.ChunkDocument build(
      String documentId, Long userId, String filename, String contentType, Integer pageNumber,
      int chunkIndex, int charStart, int charEnd, StringBuilder text) {
    String value = text.toString().trim();
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("userId", userId);
    metadata.put("documentId", documentId);
    metadata.put("sourceFilename", filename);
    metadata.put("contentType", contentType);
    metadata.put("chunkIndex", chunkIndex);
    metadata.put("chunkStrategy", "structure-aware-token-budget-v1");
    if (pageNumber != null) metadata.put("pageNumber", pageNumber);
    metadata.put("charStart", Math.max(0, charStart));
    metadata.put("charEnd", Math.max(charStart, charEnd));
    return new KnowledgeDocumentContent.ChunkDocument(
        documentId + ":" + chunkIndex, chunkIndex, pageNumber, value, metadata);
  }

  private List<TextUnit> splitUnits(String text) {
    List<TextUnit> units = new ArrayList<>();
    int cursor = 0;
    for (String paragraph : text.split("\\n\\s*\\n")) {
      String value = paragraph.trim();
      if (value.isBlank()) {
        cursor += paragraph.length() + 1;
        continue;
      }
      if (HEADING.matcher(value).matches() || value.startsWith("```") || value.length() <= properties.maxTokens()) {
        units.add(new TextUnit(value, cursor, cursor + value.length()));
      } else {
        for (String sentence : value.split("(?<=[。！？；!?;])\\s+|\\r?\\n")) {
          if (!sentence.isBlank()) {
            String trimmed = sentence.trim();
            int start = Math.max(cursor, text.indexOf(trimmed, cursor));
            units.add(new TextUnit(trimmed, start, start + trimmed.length()));
          }
        }
      }
      cursor += paragraph.length() + 2;
    }
    return units;
  }

  private static List<String> hardSplit(String value, int maxLength) {
    List<String> parts = new ArrayList<>();
    for (int start = 0; start < value.length(); start += maxLength) {
      parts.add(value.substring(start, Math.min(value.length(), start + maxLength)));
    }
    return parts;
  }

  private static String normalize(String text) {
    return text.replace("\r\n", "\n").replace('\r', '\n')
        .replaceAll("[\\t ]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
  }

  private static String tail(String value, int maxLength) {
    if (maxLength <= 0 || value.length() <= maxLength) return value;
    return value.substring(value.length() - maxLength);
  }

  private record TextUnit(String text, int start, int end) {}
}
