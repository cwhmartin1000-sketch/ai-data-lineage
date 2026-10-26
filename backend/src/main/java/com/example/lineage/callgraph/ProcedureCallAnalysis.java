package com.example.lineage.callgraph;

import java.util.List;

public record ProcedureCallAnalysis(
        List<ProcedureCallEdge> edges,
        List<UnresolvedProcedureCall> unresolvedCalls
) {
}
