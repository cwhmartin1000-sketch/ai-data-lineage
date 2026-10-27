package com.example.lineage.assistant;

import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.callgraph.ProcedureCallEdge;

import java.util.List;

/** Provider-neutral interface for answering from retrieved PL/SQL evidence. */
public interface LineageAssistantModel {

    String answer(String question, String column, List<ChunkRetrievalResult> evidence);

    /** Merge model drafts only; adapters use a separate prompt that cannot invent new rules. */
    default String summarizeFieldAnalysis(String column, String notes) {
        return notes;
    }

    default String answer(String question, String column, List<ChunkRetrievalResult> evidence,
                          List<ProcedureCallEdge> procedureCalls) {
        return answer(question, column, evidence);
    }
}
