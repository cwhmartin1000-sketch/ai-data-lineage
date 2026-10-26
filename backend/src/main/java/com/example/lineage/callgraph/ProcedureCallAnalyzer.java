package com.example.lineage.callgraph;

import com.example.lineage.chunk.PlsqlChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically extracts direct, standalone procedure-call statements from package bodies. */
@Service
public class ProcedureCallAnalyzer {

    private static final String IDENTIFIER = "[A-Z][A-Z0-9_$#]*";
    private static final Pattern ROUTINE_DECLARATION = Pattern.compile(
            "^\\s*(?:PROCEDURE|FUNCTION)\\s+(" + IDENTIFIER + ")\\b.*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CALL_STATEMENT = Pattern.compile(
            "^\\s*(?:((?:" + IDENTIFIER + "))\\s*\\.\\s*)?(" + IDENTIFIER
                    + ")\\s*(?:\\([^;]*\\))?\\s*;\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern END_STATEMENT = Pattern.compile("^\\s*END\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NON_CALL_STATEMENTS = Set.of(
            "NULL", "COMMIT", "ROLLBACK", "RETURN", "RAISE", "EXIT", "CONTINUE", "OPEN", "CLOSE", "FETCH"
    );

    public ProcedureCallAnalysis analyze(List<PlsqlChunk> chunks) {
        Map<String, Integer> procedureCounts = new HashMap<>();
        for (PlsqlChunk chunk : chunks) {
            if (isBodyProcedure(chunk) && beginsRoutine(chunk)) {
                procedureCounts.merge(key(chunk.packageName(), chunk.procedureName()), 1, Integer::sum);
            }
        }

        List<ProcedureCallEdge> edges = new ArrayList<>();
        List<UnresolvedProcedureCall> unresolved = new ArrayList<>();
        for (PlsqlChunk chunk : chunks) {
            if (!isBodyProcedure(chunk)) continue;

            String[] lines = chunk.content().split("\\R", -1);
            boolean inBlockComment = false;
            for (int index = 0; index < lines.length; index++) {
                MaskedLine masked = maskCommentsAndStrings(lines[index], inBlockComment);
                inBlockComment = masked.inBlockComment();
                String code = masked.code();
                if (END_STATEMENT.matcher(code).find()) continue;

                Matcher call = CALL_STATEMENT.matcher(code);
                if (!call.matches()) continue;

                String explicitPackage = call.group(1);
                String calledProcedure = normalize(call.group(2));
                if (explicitPackage == null && NON_CALL_STATEMENTS.contains(calledProcedure)) continue;
                String calleePackage = explicitPackage == null
                        ? normalize(chunk.packageName())
                        : normalize(explicitPackage);
                int candidateCount = procedureCounts.getOrDefault(key(calleePackage, calledProcedure), 0);
                if (candidateCount != 1) {
                    unresolved.add(new UnresolvedProcedureCall(
                            normalize(chunk.packageName()), normalize(chunk.procedureName()),
                            explicitPackage == null ? null : calleePackage, calledProcedure,
                            chunk.fileName(), chunk.startLine() + index, lines[index].strip(),
                            candidateCount == 0
                                    ? "Target Procedure is not declared in the available package body sources."
                                    : "Multiple declarations share this Package and Procedure name; overload resolution is unsupported."
                    ));
                    continue;
                }

                edges.add(new ProcedureCallEdge(
                        normalize(chunk.packageName()),
                        normalize(chunk.procedureName()),
                        calleePackage,
                        calledProcedure,
                        chunk.fileName(),
                        chunk.startLine() + index,
                        lines[index].strip(),
                        !normalize(chunk.packageName()).equals(calleePackage)
                ));
            }
        }

        List<ProcedureCallEdge> sortedEdges = edges.stream()
                .distinct()
                .sorted(Comparator.comparing(ProcedureCallEdge::fileName)
                        .thenComparingInt(ProcedureCallEdge::lineNumber))
                .toList();
        List<UnresolvedProcedureCall> sortedUnresolved = unresolved.stream()
                .distinct()
                .sorted(Comparator.comparing(UnresolvedProcedureCall::fileName)
                        .thenComparingInt(UnresolvedProcedureCall::lineNumber))
                .toList();
        return new ProcedureCallAnalysis(sortedEdges, sortedUnresolved);
    }

    private boolean isBodyProcedure(PlsqlChunk chunk) {
        String fileName = chunk.fileName().toLowerCase(Locale.ROOT);
        return (fileName.endsWith(".pkb") || fileName.endsWith(".pck") || fileName.endsWith(".sql"))
                && !"UNKNOWN".equalsIgnoreCase(chunk.packageName())
                && !"UNKNOWN".equalsIgnoreCase(chunk.procedureName());
    }

    private String key(String packageName, String procedureName) {
        return normalize(packageName) + "." + normalize(procedureName);
    }

    private boolean beginsRoutine(PlsqlChunk chunk) {
        String firstLine = chunk.content().lines().findFirst().orElse("");
        Matcher declaration = ROUTINE_DECLARATION.matcher(firstLine);
        return declaration.matches() && declaration.group(1).equalsIgnoreCase(chunk.procedureName());
    }

    private String normalize(String identifier) {
        return identifier.toUpperCase(Locale.ROOT);
    }

    /** Replaces comments and literals with spaces while preserving line length and quote positions. */
    private MaskedLine maskCommentsAndStrings(String line, boolean inBlockComment) {
        StringBuilder masked = new StringBuilder(line.length());
        boolean inString = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            char next = index + 1 < line.length() ? line.charAt(index + 1) : '\0';

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    masked.append("  ");
                    index++;
                    inBlockComment = false;
                } else {
                    masked.append(' ');
                }
            } else if (inString) {
                masked.append(' ');
                if (current == '\'' && next == '\'') {
                    masked.append(' ');
                    index++;
                } else if (current == '\'') {
                    inString = false;
                }
            } else if (current == '-' && next == '-') {
                masked.append(" ".repeat(line.length() - index));
                break;
            } else if (current == '/' && next == '*') {
                masked.append("  ");
                index++;
                inBlockComment = true;
            } else if (current == '\'') {
                masked.append(' ');
                inString = true;
            } else {
                masked.append(current);
            }
        }
        return new MaskedLine(masked.toString(), inBlockComment);
    }

    private record MaskedLine(String code, boolean inBlockComment) {
    }
}
