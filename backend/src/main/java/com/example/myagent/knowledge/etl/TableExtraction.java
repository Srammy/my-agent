package com.example.myagent.knowledge.etl;

import java.util.List;

public record TableExtraction(
    List<String> headers, List<List<String>> rows, int page, double confidence) {

  public String toMarkdown() {
    if (headers == null || headers.isEmpty()) {
      return "";
    }
    StringBuilder markdown = new StringBuilder("| ");
    markdown.append(String.join(" | ", headers)).append(" |\n| ");
    markdown.append("--- | ".repeat(headers.size())).append("\n");
    for (List<String> row : rows == null ? List.<List<String>>of() : rows) {
      markdown.append("| ").append(String.join(" | ", row)).append(" |\n");
    }
    return markdown.toString();
  }
}
