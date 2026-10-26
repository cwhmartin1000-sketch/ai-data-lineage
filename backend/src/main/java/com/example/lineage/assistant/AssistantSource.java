package com.example.lineage.assistant;

import com.example.lineage.retrieval.MatchedLine;

import java.util.List;

/** Source metadata created by this application, not by the language model. */
public record AssistantSource(
        String citationId,
        String chunkId,
        String packageName,
        String procedureName,
        String fileName,
        int startLine,
        int endLine,
        List<MatchedLine> matchedLines
) {
}
