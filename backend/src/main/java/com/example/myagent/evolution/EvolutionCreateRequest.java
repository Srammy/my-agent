package com.example.myagent.evolution;

public record EvolutionCreateRequest(
    String sessionId, EvolutionProposalType type, String title, String summary, String content) {}
