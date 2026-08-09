package com.example.myagent.knowledge.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
public class SpringAiKnowledgeDocumentReader implements KnowledgeDocumentReader {

  private static final int PARENT_TOKENS = 1600;
  private static final int CHILD_TOKENS = 400;
  private static final int CHILD_OVERLAP = 60;

  private final ChatModel chatModel;
  private final ObjectMapper objectMapper;

  public SpringAiKnowledgeDocumentReader(ChatModel chatModel, ObjectMapper objectMapper) {
    this.chatModel = chatModel;
    this.objectMapper = objectMapper;
  }

  @Override
  public KnowledgeDocumentContent read(
      Path source, Long userId, String documentId, String sourceFilename, String contentType) {
    try {
      if (isImage(contentType)) {
        return new KnowledgeDocumentContent(
            documentId,
            userId,
            sourceFilename,
            contentType,
            split(documentId, userId, sourceFilename, contentType, extractImage(source, contentType), 1));
      }
      List<KnowledgeDocumentContent.ParentDocument> parents = new ArrayList<>();
      for (TextSection section : readText(source, sourceFilename)) {
        parents.addAll(
            split(documentId, userId, sourceFilename, contentType, section.text(), section.pageNumber()));
      }
      return new KnowledgeDocumentContent(documentId, userId, sourceFilename, contentType, parents);
    } catch (KnowledgeDocumentEtlException error) {
      throw error;
    } catch (Exception error) {
      throw new KnowledgeDocumentEtlException("Unable to parse knowledge document", error);
    }
  }

  private List<TextSection> readText(Path source, String filename) {
    List<Document> documents;
    if (filename != null && filename.toLowerCase().endsWith(".md")) {
      documents =
          new MarkdownDocumentReader(
                  new FileSystemResource(source), MarkdownDocumentReaderConfig.defaultConfig())
              .get();
    } else if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
      documents = new PagePdfDocumentReader(new FileSystemResource(source)).get();
    } else {
      documents = new TikaDocumentReader(new FileSystemResource(source)).get();
    }
    List<TextSection> sections =
        documents.stream()
            .filter(document -> document.getText() != null && !document.getText().isBlank())
            .map(document -> new TextSection(document.getText().strip(), pageNumber(document)))
            .toList();
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("Document has no readable text");
    }
    return sections;
  }

  private String extractImage(Path source, String contentType) {
    try {
      Media media = new Media(MimeTypeUtils.parseMimeType(contentType), new FileSystemResource(source));
      UserMessage message =
          UserMessage.builder()
              .text(
                  "请对图片执行 OCR、图片内容理解和表格识别。只返回 JSON："
                      + "{ocrText,imageDescription,tables:[{headers,rows,page,confidence}]}。"
                      + "没有表格时 tables 返回空数组。")
              .media(media)
              .build();
      ChatResponse response = chatModel.call(new Prompt(message));
      String json = response.getResult().getOutput().getText();
      MultimodalExtraction extraction = objectMapper.readValue(json, MultimodalExtraction.class);
      String tables = extraction.tables() == null
          ? ""
          : extraction.tables().stream().map(TableExtraction::toMarkdown).reduce("", (a, b) -> a + "\n" + b);
      return String.join(
          "\n\n",
          extraction.ocrText() == null ? "" : extraction.ocrText(),
          extraction.imageDescription() == null ? "" : extraction.imageDescription(),
          tables).strip();
    } catch (Exception error) {
      throw new KnowledgeDocumentEtlException("Multimodal document extraction failed", error);
    }
  }

  private List<KnowledgeDocumentContent.ParentDocument> split(
      String documentId,
      Long userId,
      String filename,
      String contentType,
      String text,
      Integer pageNumber) {
    List<String> parentTokens = tokens(text);
    List<KnowledgeDocumentContent.ParentDocument> parents = new ArrayList<>();
    for (int parentStart = 0, parentIndex = 0;
        parentStart < parentTokens.size();
        parentStart += PARENT_TOKENS, parentIndex++) {
      int parentEnd = Math.min(parentTokens.size(), parentStart + PARENT_TOKENS);
      String parentText = join(parentTokens.subList(parentStart, parentEnd));
      String parentId = documentId + "_p_" + parentIndex;
      List<KnowledgeDocumentContent.ChildDocument> children = new ArrayList<>();
      int childIndex = 0;
      for (int childStart = parentStart; childStart < parentEnd; childStart += CHILD_TOKENS - CHILD_OVERLAP) {
        int childEnd = Math.min(parentEnd, childStart + CHILD_TOKENS);
        String childText = join(parentTokens.subList(childStart, childEnd));
        Map<String, Object> metadata =
            metadata(userId, documentId, filename, contentType, parentIndex, childIndex, pageNumber);
        children.add(
            new KnowledgeDocumentContent.ChildDocument(
                parentId + "_c_" + childIndex,
                parentId,
                childIndex,
                pageNumber,
                childText,
                metadata));
        childIndex++;
        if (childEnd == parentEnd) break;
      }
      parents.add(
          new KnowledgeDocumentContent.ParentDocument(
              parentId,
              parentIndex,
              pageNumber,
              parentText,
              metadata(userId, documentId, filename, contentType, parentIndex, null, pageNumber),
              children));
    }
    return parents;
  }

  private static Map<String, Object> metadata(
      Long userId,
      String documentId,
      String filename,
      String contentType,
      int parentIndex,
      Integer childIndex,
      Integer pageNumber) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("userId", userId);
    metadata.put("documentId", documentId);
    metadata.put("sourceFilename", filename);
    metadata.put("contentType", contentType);
    metadata.put("parentIndex", parentIndex);
    if (pageNumber != null) metadata.put("pageNumber", pageNumber);
    if (childIndex != null) metadata.put("childIndex", childIndex);
    return metadata;
  }

  private static List<String> tokens(String text) {
    return List.of(text.replaceAll("\\s+", " ").trim().split(" "));
  }

  private static String join(List<String> tokens) {
    return String.join(" ", tokens).strip();
  }

  private static boolean isImage(String contentType) {
    return contentType != null && contentType.toLowerCase().startsWith("image/");
  }

  private static Integer pageNumber(Document document) {
    for (String key : List.of("pageNumber", "page_number", "page")) {
      Object value = document.getMetadata().get(key);
      if (value instanceof Number number) return number.intValue();
      if (value != null) {
        try {
          return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
          // Ignore non-numeric reader metadata and continue checking known keys.
        }
      }
    }
    return null;
  }

  private record TextSection(String text, Integer pageNumber) {}
}
