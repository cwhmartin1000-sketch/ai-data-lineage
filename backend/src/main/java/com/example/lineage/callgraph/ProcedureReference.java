package com.example.lineage.callgraph;

public record ProcedureReference(String packageName, String procedureName) {
    public String key() {
        return packageName.toUpperCase() + "." + procedureName.toUpperCase();
    }
}
