package com.example.lineage.search;

public record LineageSearchResult(
        String fileName,
        int lineNumber,
        String code,
        String procedureName
) {
}
