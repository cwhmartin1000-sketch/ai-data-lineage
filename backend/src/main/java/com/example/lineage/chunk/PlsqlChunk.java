package com.example.lineage.chunk;

public record PlsqlChunk(
        String chunkId,
        String packageName,
        String procedureName,
        String fileName,
        int startLine,
        int endLine,
        String content
) {
    public ChunkSummary toSummary() {
        return new ChunkSummary(chunkId, packageName, procedureName, fileName, startLine, endLine);
    }
}
