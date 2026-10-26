package com.example.lineage.callgraph;

public record UnresolvedProcedureCall(
        String callerPackage,
        String callerProcedure,
        String referencedPackage,
        String referencedProcedure,
        String fileName,
        int lineNumber,
        String evidenceCode,
        String reason
) {
}
