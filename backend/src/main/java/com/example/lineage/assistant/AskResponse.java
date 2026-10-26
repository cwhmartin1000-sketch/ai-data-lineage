package com.example.lineage.assistant;

import com.example.lineage.callgraph.ProcedureCallEdge;

import java.util.List;

public record AskResponse(String answer, List<AssistantSource> sources, List<String> limitations,
                          List<ProcedureCallEdge> procedureCalls) {
}
