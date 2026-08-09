package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.myagent.knowledge.etl.KnowledgeDocumentContent;
import com.example.myagent.knowledge.etl.SpringAiKnowledgeDocumentReader;
import com.example.myagent.knowledge.etl.TableExtraction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class KnowledgeDocumentReaderTest {

  @Test
  void readsMarkdownAndCreatesParentChildChunksWithMetadata() throws Exception {
    Path source = Files.createTempFile("knowledge", ".md");
    Files.writeString(source, "# Project\n\nThe launch checklist contains testing and acceptance details.");

    SpringAiKnowledgeDocumentReader reader =
        new SpringAiKnowledgeDocumentReader(mock(ChatModel.class), new ObjectMapper());
    KnowledgeDocumentContent content =
        reader.read(source, 7L, "doc-1", "project.md", "text/markdown");

    assertThat(content.parents()).hasSize(1);
    assertThat(content.parents().get(0).parentId()).isEqualTo("doc-1_p_0");
    assertThat(content.parents().get(0).text()).contains("acceptance");
    assertThat(content.parents().get(0).children()).isNotEmpty();
    assertThat(content.parents().get(0).children().get(0).parentId()).isEqualTo("doc-1_p_0");
    assertThat(content.parents().get(0).children().get(0).metadata())
        .containsEntry("userId", 7L)
        .containsEntry("documentId", "doc-1")
        .containsEntry("sourceFilename", "project.md");
  }

  @Test
  void usesSpringAiMultimodalMessageForImageAndKeepsTableMarkdown() throws Exception {
    Path source = Files.createTempFile("knowledge", ".png");
    Files.write(source, new byte[] {1, 2, 3});
    ChatModel chatModel = mock(ChatModel.class);
    String json =
        "{\"ocrText\":\"验收标准\",\"imageDescription\":\"流程图\","
            + "\"tables\":[{\"headers\":[\"项目\",\"状态\"],"
            + "\"rows\":[[\"接口\",\"通过\"]],\"page\":1,\"confidence\":0.98}]}";
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));

    SpringAiKnowledgeDocumentReader reader =
        new SpringAiKnowledgeDocumentReader(chatModel, new ObjectMapper());
    KnowledgeDocumentContent content =
    reader.read(source, 7L, "doc-image", "page.png", "image/png");

    assertThat(content.parents().get(0).text()).contains("验收标准", "流程图", "项目", "通过");
    assertThat(content.parents().get(0).pageNumber()).isEqualTo(1);
  }

  @Test
  void escapesMarkdownTableCells() {
    String markdown =
        new TableExtraction(List.of("A|B"), List.of(List.of("line\none")), 1, 1.0).toMarkdown();

    assertThat(markdown).contains("A\\|B", "line one");
  }

  @Test
  void readsPdfPagesWithPageMetadata() throws Exception {
    Path source = Files.createTempFile("knowledge", ".pdf");
    try (PDDocument pdf = new PDDocument()) {
      PDPage page = new PDPage();
      pdf.addPage(page);
      try (PDPageContentStream stream = new PDPageContentStream(pdf, page)) {
        stream.beginText();
        stream.newLineAtOffset(72, 720);
        stream.showText("PDF page content");
        stream.endText();
      }
      pdf.save(source.toFile());
    }

    SpringAiKnowledgeDocumentReader reader =
        new SpringAiKnowledgeDocumentReader(mock(ChatModel.class), new ObjectMapper());
    KnowledgeDocumentContent content =
        reader.read(source, 7L, "doc-pdf", "document.pdf", "application/pdf");

    assertThat(content.parents()).singleElement().satisfies(parent -> {
      assertThat(parent.pageNumber()).isEqualTo(1);
      assertThat(parent.text()).contains("PDF page content");
    });
  }
}
