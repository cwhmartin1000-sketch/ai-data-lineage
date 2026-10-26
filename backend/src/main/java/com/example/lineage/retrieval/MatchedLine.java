package com.example.lineage.retrieval;

/** A source line containing an exact occurrence of the requested identifier. */
public record MatchedLine(int lineNumber, String code) {
}
