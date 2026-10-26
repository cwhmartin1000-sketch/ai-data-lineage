package com.example.lineage.retrieval;

import com.example.lineage.chunk.PlsqlChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts explicit column mappings from the INSERT/SELECT and MERGE forms in the sample packages. */
@Component
public class SqlColumnDependencyAnalyzer {

    private static final Pattern INSERT_SELECT = Pattern.compile(
            "(?is)INSERT\\s+INTO\\s+([A-Z][A-Z0-9_$#]*)\\s*\\((.*?)\\)\\s*SELECT\\s+(.*?)\\s+FROM\\s+(.*?);"
    );
    private static final Pattern MERGE_TARGET = Pattern.compile(
            "(?is)MERGE\\s+INTO\\s+([A-Z][A-Z0-9_$#]*)\\s+([A-Z][A-Z0-9_$#]*)\\s+USING\\s*\\((.*?)\\)\\s*([A-Z][A-Z0-9_$#]*)\\s+ON\\s*\\(.*?\\)(.*?);"
    );
    private static final Pattern UPDATE = Pattern.compile(
            "(?is)UPDATE\\s+([A-Z][A-Z0-9_$#]*)(?:\\s+([A-Z][A-Z0-9_$#]*))?\\s+SET\\s+(.*?);"
    );
    private static final Pattern TABLE_ALIAS = Pattern.compile(
            "(?i)(?:^|\\bFROM\\s+|\\bJOIN\\s+|,)\\s*([A-Z][A-Z0-9_$#]*)(?:\\s+(?:AS\\s+)?([A-Z][A-Z0-9_$#]*))?"
    );
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile(
            "(?i)(?<![A-Z0-9_$#])([A-Z][A-Z0-9_$#]*)\\.([A-Z][A-Z0-9_$#]*)(?![A-Z0-9_$#])"
    );
    private static final Pattern MERGE_INSERT = Pattern.compile(
            "(?is)WHEN\\s+NOT\\s+MATCHED\\s+THEN\\s+INSERT\\s*\\((.*?)\\)\\s*VALUES\\s*\\((.*?)\\)"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("(?i)(?<![A-Z0-9_$#])([A-Z][A-Z0-9_$#]*)(?![A-Z0-9_$#])");
    private static final Set<String> SQL_WORDS = Set.of("AS", "CASE", "WHEN", "THEN", "ELSE", "END", "NULL", "IS",
            "NOT", "AND", "OR", "TO_DATE", "TO_CHAR", "NVL", "FROM", "SELECT", "DISTINCT", "CAST");

    /** Returns only mappings established by a parsed SQL write statement, never name-only matches. */
    public List<ColumnDependency> analyze(PlsqlChunk chunk) {
        String sql = chunk.content();
        List<ColumnDependency> dependencies = new ArrayList<>();

        Matcher insertMatcher = INSERT_SELECT.matcher(sql);
        while (insertMatcher.find()) {
            String targetTable = normalize(insertMatcher.group(1));
            List<String> targetColumns = splitTopLevel(insertMatcher.group(2));
            List<String> expressions = splitTopLevel(insertMatcher.group(3));
            Map<String, String> aliases = tableAliases(insertMatcher.group(4));
            for (int index = 0; index < Math.min(targetColumns.size(), expressions.size()); index++) {
                addReferences(dependencies, chunk, targetTable, normalize(targetColumns.get(index)),
                        expressions.get(index), aliases, Map.of());
            }
        }

        Matcher mergeMatcher = MERGE_TARGET.matcher(sql);
        while (mergeMatcher.find()) {
            String targetTable = normalize(mergeMatcher.group(1));
            String targetAlias = normalize(mergeMatcher.group(2));
            String sourceQuery = mergeMatcher.group(3);
            String sourceAlias = normalize(mergeMatcher.group(4));
            Map<String, String> aliases = tableAliases(sourceQuery);
            Map<String, Set<String>> inlineViewColumns = inlineViewColumns(sourceQuery, aliases);
            String actions = mergeMatcher.group(5);

            Matcher updateMatcher = Pattern.compile(
                    "(?is)WHEN\\s+MATCHED\\s+THEN\\s+UPDATE\\s+SET\\s+(.*?)(?=WHEN\\s+NOT\\s+MATCHED|$)"
            ).matcher(actions);
            if (updateMatcher.find()) {
                for (String assignment : splitTopLevel(updateMatcher.group(1))) {
                    int equals = assignment.indexOf('=');
                    if (equals < 0) continue;
                    String targetColumn = assignment.substring(0, equals).trim();
                    int dot = targetColumn.lastIndexOf('.');
                    if (dot >= 0) {
                        if (!normalize(targetColumn.substring(0, dot)).equals(targetAlias)) continue;
                        targetColumn = targetColumn.substring(dot + 1);
                    }
                    addReferences(dependencies, chunk, targetTable, normalize(targetColumn),
                            assignment.substring(equals + 1), aliases, Map.of(sourceAlias, inlineViewColumns));
                }
            }

            Matcher insertMatcher2 = MERGE_INSERT.matcher(actions);
            if (insertMatcher2.find()) {
                List<String> targetColumns = splitTopLevel(insertMatcher2.group(1));
                List<String> values = splitTopLevel(insertMatcher2.group(2));
                for (int index = 0; index < Math.min(targetColumns.size(), values.size()); index++) {
                    addReferences(dependencies, chunk, targetTable, normalize(targetColumns.get(index)),
                            values.get(index), aliases, Map.of(sourceAlias, inlineViewColumns));
                }
            }
        }

        Matcher updateMatcher = UPDATE.matcher(sql);
        while (updateMatcher.find()) {
            String targetTable = normalize(updateMatcher.group(1));
            String targetAlias = updateMatcher.group(2) == null ? targetTable : normalize(updateMatcher.group(2));
            Map<String, String> aliases = tableAliases(updateMatcher.group(3));
            aliases.put(targetAlias, targetTable);
            for (String assignment : splitTopLevel(updateMatcher.group(3))) {
                int equals = assignment.indexOf('=');
                if (equals < 0) continue;
                String targetColumn = assignment.substring(0, equals).trim();
                int dot = targetColumn.lastIndexOf('.');
                if (dot >= 0) targetColumn = targetColumn.substring(dot + 1);
                addReferences(dependencies, chunk, targetTable, normalize(targetColumn),
                        assignment.substring(equals + 1), aliases, Map.of());
            }
        }

        return dependencies.stream().distinct().toList();
    }

    private Map<String, String> tableAliases(String fromClause) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE_ALIAS.matcher(fromClause);
        while (matcher.find()) {
            String table = normalize(matcher.group(1));
            String candidate = matcher.group(2) == null ? null : normalize(matcher.group(2));
            if (candidate != null && !Set.of("ON", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "GROUP", "ORDER")
                    .contains(candidate)) {
                aliases.put(candidate, table);
            } else {
                aliases.put(table, table);
            }
        }
        return aliases;
    }

    private Map<String, Set<String>> inlineViewColumns(String select, Map<String, String> aliases) {
        Matcher projection = Pattern.compile("(?is)SELECT\\s+(.*?)\\s+FROM\\s+").matcher(select);
        if (!projection.find()) return Map.of();
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String expression : splitTopLevel(projection.group(1))) {
            Matcher aliasColumn = QUALIFIED_COLUMN.matcher(expression);
            if (aliasColumn.find()) {
                String column = normalize(aliasColumn.group(2));
                String table = aliases.get(normalize(aliasColumn.group(1)));
                if (table != null) result.computeIfAbsent(column, ignored -> new LinkedHashSet<>()).add(table + "." + column);
            }
        }
        return result;
    }

    private void addReferences(
            List<ColumnDependency> dependencies,
            PlsqlChunk chunk,
            String targetTable,
            String targetColumn,
            String expression,
            Map<String, String> aliases,
            Map<String, Map<String, Set<String>>> inlineViews
    ) {
        Matcher matcher = QUALIFIED_COLUMN.matcher(expression);
        int expressionOffset = chunk.content().indexOf(expression);
        List<int[]> qualifiedRanges = new ArrayList<>();
        while (matcher.find()) {
            qualifiedRanges.add(new int[]{matcher.start(), matcher.end()});
            String qualifier = normalize(matcher.group(1));
            String sourceColumn = normalize(matcher.group(2));
            Set<String> viewReferences = inlineViews.getOrDefault(qualifier, Map.of()).get(sourceColumn);
            if (viewReferences != null) {
                for (String reference : viewReferences) {
                    int dot = reference.indexOf('.');
                    addDependency(dependencies, chunk, targetTable, targetColumn,
                            reference.substring(0, dot), reference.substring(dot + 1), expressionOffset + matcher.start());
                }
            } else {
                String sourceTable = aliases.get(qualifier);
                if (sourceTable != null) {
                    addDependency(dependencies, chunk, targetTable, targetColumn,
                            sourceTable, sourceColumn, expressionOffset + matcher.start());
                }
            }
        }

        Set<String> sourceTables = new LinkedHashSet<>(aliases.values());
        if (sourceTables.size() == 1) {
            String soleSourceTable = sourceTables.iterator().next();
            Matcher bareIdentifier = IDENTIFIER.matcher(maskStringLiterals(expression));
            while (bareIdentifier.find()) {
                int start = bareIdentifier.start();
                if (qualifiedRanges.stream().anyMatch(range -> start >= range[0] && start < range[1])) continue;
                String identifier = normalize(bareIdentifier.group(1));
                if (SQL_WORDS.contains(identifier) || aliases.containsKey(identifier)) continue;
                String prefix = expression.substring(0, start).stripTrailing().toUpperCase(Locale.ROOT);
                if (prefix.endsWith(" AS")) continue;
                int prior = start - 1;
                while (prior >= 0 && Character.isWhitespace(expression.charAt(prior))) prior--;
                int next = bareIdentifier.end();
                while (next < expression.length() && Character.isWhitespace(expression.charAt(next))) next++;
                if ((prior >= 0 && (expression.charAt(prior) == '.' || expression.charAt(prior) == '\''))
                        || (next < expression.length() && expression.charAt(next) == '.')) continue;
                addDependency(dependencies, chunk, targetTable, targetColumn,
                        soleSourceTable, identifier, expressionOffset + start);
            }
        }
    }

    private String maskStringLiterals(String expression) {
        StringBuilder masked = new StringBuilder(expression);
        boolean quoted = false;
        for (int index = 0; index < masked.length(); index++) {
            if (masked.charAt(index) == '\'') {
                if (quoted && index + 1 < masked.length() && masked.charAt(index + 1) == '\'') {
                    masked.setCharAt(index + 1, ' ');
                    index++;
                } else {
                    quoted = !quoted;
                }
                masked.setCharAt(index, ' ');
            } else if (quoted) {
                masked.setCharAt(index, ' ');
            }
        }
        return masked.toString();
    }

    private void addDependency(List<ColumnDependency> dependencies, PlsqlChunk chunk,
                               String targetTable, String targetColumn, String sourceTable,
                               String sourceColumn, int offset) {
        int line = chunk.startLine() + (int) chunk.content().substring(0, Math.max(0, Math.min(offset, chunk.content().length())))
                .chars().filter(character -> character == '\n').count();
        dependencies.add(new ColumnDependency(targetTable, targetColumn, sourceTable, sourceColumn,
                chunk.chunkId(), chunk.packageName(), chunk.procedureName(), chunk.fileName(),
                chunk.startLine(), chunk.endLine(), line));
    }

    private List<String> splitTopLevel(String sql) {
        List<String> values = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'') {
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') index++;
                else quoted = !quoted;
            } else if (!quoted && current == '(') depth++;
            else if (!quoted && current == ')') depth--;
            else if (!quoted && depth == 0 && current == ',') {
                values.add(sql.substring(start, index).trim());
                start = index + 1;
            }
        }
        String last = sql.substring(start).trim();
        if (!last.isEmpty()) values.add(last);
        return values;
    }

    private String normalize(String identifier) {
        return identifier.trim().replaceAll("(?i)^AS\\s+", "")
                .replaceAll("(?i)\\s+AS\\s+[A-Z][A-Z0-9_$#]*$", "")
                .toUpperCase(Locale.ROOT);
    }

    public record ColumnDependency(
            String targetTable,
            String targetColumn,
            String sourceTable,
            String sourceColumn,
            String chunkId,
            String packageName,
            String procedureName,
            String fileName,
            int startLine,
            int endLine,
            int evidenceLine
    ) {
        public String targetIdentifier() { return targetTable + "." + targetColumn; }
        public String sourceIdentifier() { return sourceTable + "." + sourceColumn; }
    }
}
