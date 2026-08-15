package com.example.myagent.knowledge.etl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.myagent.config.KnowledgeProperties;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
public class SpringAiKnowledgeDocumentReader implements KnowledgeDocumentReader {

  private final ChatModel chatModel;
  private final ObjectMapper objectMapper;
  private final KnowledgeChunkingService chunkingService;

  public SpringAiKnowledgeDocumentReader(ChatModel chatModel, ObjectMapper objectMapper) {
    this(chatModel, objectMapper, new KnowledgeChunkingService(defaultProperties()));
  }

  @Autowired
  public SpringAiKnowledgeDocumentReader(
      @Qualifier("knowledgeMultimodalChatModel") ChatModel chatModel,
      ObjectMapper objectMapper,
      KnowledgeChunkingService chunkingService) {
    this.chatModel = chatModel;
    this.objectMapper = objectMapper;
    this.chunkingService = chunkingService;
  }

  private static KnowledgeProperties defaultProperties() {
    return new KnowledgeProperties(
        new KnowledgeProperties.Embedding("test", "embedding", 2, "KEY"),
        new KnowledgeProperties.Multimodal("test", "vision", "KEY"),
        new KnowledgeProperties.Elasticsearch("http://localhost:9200", "", "", "chunks"),
        new KnowledgeProperties.Kafka("topic", "group", "localhost:9092"),
        new KnowledgeProperties.Storage("target"));
  }

  @Override
  public KnowledgeDocumentContent read(
      Path source, Long userId, String documentId, String sourceFilename, String contentType) {
    try {
      if (isImage(contentType)) {
        Media media = new Media(MimeTypeUtils.parseMimeType(contentType), new FileSystemResource(source));
        return new KnowledgeDocumentContent(
            documentId,
            userId,
            sourceFilename,
            contentType,
            chunkingService.chunk(
                documentId, userId, sourceFilename, contentType, extractMultimodal(media), 1, 0));
      }
      List<KnowledgeDocumentContent.ChunkDocument> chunks = new ArrayList<>();
      int nextChunkIndex = 0;
      for (TextSection section : readText(source, sourceFilename)) {
        List<KnowledgeDocumentContent.ChunkDocument> pageChunks =
            chunkingService.chunk(
                documentId,
                userId,
                sourceFilename,
                contentType,
                section.text(),
                section.pageNumber(),
                nextChunkIndex);
        chunks.addAll(pageChunks);
        nextChunkIndex += pageChunks.size();
      }
      return new KnowledgeDocumentContent(documentId, userId, sourceFilename, contentType, chunks);
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
      return readPdf(source);
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

  private List<TextSection> readPdf(Path source) {
    try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
      List<Document> documents = new PagePdfDocumentReader(new FileSystemResource(source)).get();
      PDFRenderer renderer = new PDFRenderer(pdf);
      List<TextSection> sections = new ArrayList<>();
      Map<Integer, Document> textPages = new HashMap<>();
      for (int index = 0; index < documents.size(); index++) {
        Integer page = pageNumber(documents.get(index));
        if (page == null) page = index + 1;
        textPages.put(page, documents.get(index));
      }
      for (int index = 0; index < pdf.getNumberOfPages(); index++) {
        int page = index + 1;
        Document document = textPages.get(page);
        if (document != null && document.getText() != null && !document.getText().isBlank()) {
          sections.add(new TextSection(document.getText().strip(), page));
          continue;
        }
        sections.add(new TextSection(extractPdfPage(renderer, index), page));
      }
      if (sections.isEmpty()) {
        throw new IllegalArgumentException("Document has no readable pages");
      }
      return sections;
    } catch (KnowledgeDocumentEtlException error) {
      throw error;
    } catch (Exception error) {
      throw new KnowledgeDocumentEtlException("Unable to OCR PDF pages", error);
    }
  }

  private String extractPdfPage(PDFRenderer renderer, int pageIndex) throws IOException {
    java.awt.image.BufferedImage image = renderer.renderImageWithDPI(pageIndex, 150, ImageType.RGB);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", output);
    Media media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(output.toByteArray()));
    return extractMultimodal(media);
  }

  private String extractMultimodal(Media media) {
    try {
      UserMessage message =
          UserMessage.builder()
              .text(
                  "请对图片执行 OCR、图片内容理解和表格识别。只返回 JSON："
                      + "{ocrText,imageDescription,tables:[{headers,rows,page,confidence}]}。"
                      + "没有表格时 tables 返回空数组。")
              .media(media)
              .build();
      ChatResponse response = chatModel.call(new Prompt(message));
      String json = normalizeJson(response.getResult().getOutput().getText());
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

  static String normalizeJson(String response) {
    String normalized = response == null ? "" : response.strip();
    if (normalized.startsWith("```") && normalized.endsWith("```")) {
      int firstLineBreak = normalized.indexOf('\n');
      normalized = firstLineBreak >= 0
          ? normalized.substring(firstLineBreak + 1, normalized.length() - 3)
          : normalized.substring(3, normalized.length() - 3);
    }
    return normalized.strip();
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
