package com.example.lineage.analysis;

import com.example.lineage.assistant.AssistantSource;
import com.example.lineage.callgraph.ProcedureCallEdge;
import java.util.List;

public record FieldAnalysisResponse(String answer, List<AssistantSource> sources, List<String> limitations,
                                    List<ProcedureCallEdge> procedureCalls, Coverage coverage, List<Batch> batches) {
    public record Coverage(int scannedFiles, int readFiles, int failedFiles, int directMatchChunks,
                           int candidateChunks, int analyzedChunks, int omittedChunks,
                           int completedBatches, int failedBatches, boolean summaryComplete,
                           String status) { }
    public record Batch(int number, String status, String answer, List<String> sourceIds) { }
}
