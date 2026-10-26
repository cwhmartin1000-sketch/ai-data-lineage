package com.example.lineage.chunk;

public record ChunkSummary(
        String chunkId,
        String packageName,
        String procedureName,
        String fileName,
        int startLine,
        int endLine
) {
}
