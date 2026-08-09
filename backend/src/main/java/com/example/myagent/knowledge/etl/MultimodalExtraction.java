package com.example.myagent.knowledge.etl;

import java.util.List;

public record MultimodalExtraction(
    String ocrText, String imageDescription, List<TableExtraction> tables) {}
