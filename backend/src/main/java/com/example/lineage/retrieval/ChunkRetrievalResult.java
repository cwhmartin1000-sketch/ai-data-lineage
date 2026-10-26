package com.example.lineage.retrieval;

import java.util.List;

public record ChunkRetrievalResult(
        String chunkId,
        String packageName,
        String procedureName,
        String fileName,
        int startLine,
        int endLine,
        double score,
        List<MatchedLine> matchedLines,
        String content,
        List<SqlColumnDependencyAnalyzer.ColumnDependency> dependencyEdges
) {
    public ChunkRetrievalResult(String chunkId, String packageName, String procedureName, String fileName,
                                int startLine, int endLine, double score, List<MatchedLine> matchedLines,
                                String content) {
        this(chunkId, packageName, procedureName, fileName, startLine, endLine, score, matchedLines, content, List.of());
    }
}
