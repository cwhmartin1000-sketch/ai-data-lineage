package com.example.lineage.callgraph;

public record ProcedureCallEdge(
        String callerPackage,
        String callerProcedure,
        String calleePackage,
        String calleeProcedure,
        String fileName,
        int lineNumber,
        String evidenceCode,
        boolean crossPackage
) {
}
