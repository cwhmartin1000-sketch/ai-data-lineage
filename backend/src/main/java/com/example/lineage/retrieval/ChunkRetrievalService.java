package com.example.lineage.retrieval;

import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChunkRetrievalService {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)^\\s*(?:([A-Z][A-Z0-9_$#]*)\\.)?([A-Z][A-Z0-9_$#]*)\\s*$"
    );

    private final PlsqlChunkingService chunkingService;
    private final SqlColumnDependencyAnalyzer dependencyAnalyzer;
    private final int maximumDependencyDepth;

    @Autowired
    public ChunkRetrievalService(
            PlsqlChunkingService chunkingService,
            SqlColumnDependencyAnalyzer dependencyAnalyzer,
            @Value("${lineage.dependency.maximum-depth:8}") int maximumDependencyDepth
    ) {
        if (maximumDependencyDepth < 1) {
            throw new IllegalArgumentException("maximumDependencyDepth must be at least 1");
        }
        this.chunkingService = chunkingService;
        this.dependencyAnalyzer = dependencyAnalyzer;
        this.maximumDependencyDepth = maximumDependencyDepth;
    }

    public ChunkRetrievalService(PlsqlChunkingService chunkingService, SqlColumnDependencyAnalyzer dependencyAnalyzer) {
        this(chunkingService, dependencyAnalyzer, 8);
    }

    public ChunkRetrievalService(PlsqlChunkingService chunkingService) {
        this(chunkingService, new SqlColumnDependencyAnalyzer(), 8);
    }

    public List<ChunkRetrievalResult> search(String query, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be at least 1");
        QueryColumn queryColumn = identifyColumn(query);
        if (queryColumn == null) return List.of();

        List<PlsqlChunk> chunks = chunkingService.loadChunks();
        List<SqlColumnDependencyAnalyzer.ColumnDependency> dependencies = chunks.stream()
                .flatMap(chunk -> dependencyAnalyzer.analyze(chunk).stream())
                .toList();
        Set<Node> roots = findRoots(queryColumn, dependencies);
        // Preserve legacy identifier search for unqualified fields that have no parsed SQL mapping.
        // Qualified lookups never fall back to column-name-only matches, so a similarly named table
        // cannot be presented as lineage without an explicit dependency edge.
        if (roots.isEmpty()) {
            return queryColumn.table() == null
                    ? searchByExactIdentifier(chunks, queryColumn.column(), limit)
                    : List.of();
        }

        Map<String, PlsqlChunk> chunksById = new LinkedHashMap<>();
        chunks.forEach(chunk -> chunksById.put(chunk.chunkId(), chunk));
        Map<String, ResultAccumulator> resultsByChunk = new LinkedHashMap<>();
        Set<String> visitedNodes = new LinkedHashSet<>();
        Set<String> visitedEdges = new LinkedHashSet<>();
        Deque<NodeAtDepth> pending = new ArrayDeque<>();
        roots.forEach(root -> pending.addLast(new NodeAtDepth(root, 0)));

        while (!pending.isEmpty()) {
            NodeAtDepth current = pending.removeFirst();
            if (!visitedNodes.add(current.node().key())) continue;

            for (SqlColumnDependencyAnalyzer.ColumnDependency edge : dependencies) {
                if (sameNode(edge.sourceTable(), edge.sourceColumn(), current.node())) {
                    addEdgeResult(edge, current.depth(), 60.0, chunksById, resultsByChunk, visitedEdges);
                    if (current.depth() < maximumDependencyDepth) {
                        Node target = new Node(edge.targetTable(), edge.targetColumn());
                        if (!visitedNodes.contains(target.key())) {
                            pending.addLast(new NodeAtDepth(target, current.depth() + 1));
                        }
                    }
                }
                if (sameNode(edge.targetTable(), edge.targetColumn(), current.node())) {
                    addEdgeResult(edge, current.depth(), 100.0 - current.depth() * 10.0,
                            chunksById, resultsByChunk, visitedEdges);
                    if (current.depth() < maximumDependencyDepth) {
                        Node source = new Node(edge.sourceTable(), edge.sourceColumn());
                        if (!visitedNodes.contains(source.key())) {
                            pending.addLast(new NodeAtDepth(source, current.depth() + 1));
                        }
                    }
                }
            }
        }

        Pattern exactQueryIdentifier = exactIdentifier(queryColumn.column());
        return resultsByChunk.values().stream()
                .map(accumulator -> accumulator.toResult(exactQueryIdentifier))
                .sorted(Comparator.comparingDouble(ChunkRetrievalResult::score).reversed()
                        .thenComparing(ChunkRetrievalResult::fileName)
                        .thenComparingInt(ChunkRetrievalResult::startLine)
                        .thenComparing(ChunkRetrievalResult::chunkId))
                .limit(limit)
                .toList();
    }

    private List<ChunkRetrievalResult> searchByExactIdentifier(List<PlsqlChunk> chunks, String column, int limit) {
        Pattern identifier = exactIdentifier(column);
        List<ChunkRetrievalResult> results = new ArrayList<>();
        for (PlsqlChunk chunk : chunks) {
            String[] lines = chunk.content().split("\\R", -1);
            List<MatchedLine> matchedLines = new ArrayList<>();
            for (int offset = 0; offset < lines.length; offset++) {
                if (identifier.matcher(lines[offset]).find()) {
                    matchedLines.add(new MatchedLine(chunk.startLine() + offset, lines[offset]));
                }
            }
            if (!matchedLines.isEmpty()) {
                results.add(new ChunkRetrievalResult(chunk.chunkId(), chunk.packageName(), chunk.procedureName(),
                        chunk.fileName(), chunk.startLine(), chunk.endLine(), matchedLines.size() * 10.0,
                        List.copyOf(matchedLines), chunk.content(), List.of()));
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(ChunkRetrievalResult::score).reversed()
                        .thenComparing(ChunkRetrievalResult::fileName)
                        .thenComparingInt(ChunkRetrievalResult::startLine)
                        .thenComparing(ChunkRetrievalResult::chunkId))
                .limit(limit)
                .toList();
    }

    private Set<Node> findRoots(QueryColumn query, List<SqlColumnDependencyAnalyzer.ColumnDependency> dependencies) {
        Set<Node> nodes = new LinkedHashSet<>();
        for (SqlColumnDependencyAnalyzer.ColumnDependency edge : dependencies) {
            addIfMatch(nodes, query, edge.targetTable(), edge.targetColumn());
            addIfMatch(nodes, query, edge.sourceTable(), edge.sourceColumn());
        }
        return nodes;
    }

    private void addIfMatch(Set<Node> nodes, QueryColumn query, String table, String column) {
        if (column.equals(query.column()) && (query.table() == null || query.table().equals(table))) {
            nodes.add(new Node(table, column));
        }
    }

    private void addEdgeResult(
            SqlColumnDependencyAnalyzer.ColumnDependency edge,
            int depth,
            double score,
            Map<String, PlsqlChunk> chunksById,
            Map<String, ResultAccumulator> resultsByChunk,
            Set<String> visitedEdges
    ) {
        String edgeKey = edge.chunkId() + "|" + edge.targetIdentifier() + "|" + edge.sourceIdentifier()
                + "|" + edge.evidenceLine();
        if (!visitedEdges.add(edgeKey)) return;
        PlsqlChunk chunk = chunksById.get(edge.chunkId());
        if (chunk == null) return;
        ResultAccumulator accumulator = resultsByChunk.computeIfAbsent(chunk.chunkId(), ignored -> new ResultAccumulator(chunk));
        accumulator.score = Math.max(accumulator.score, score);
        accumulator.edges.putIfAbsent(edgeKey, edge);
        accumulator.lineNumbers.add(edge.evidenceLine());
    }

    private boolean sameNode(String table, String column, Node node) {
        return table.equals(node.table()) && column.equals(node.column());
    }

    private QueryColumn identifyColumn(String query) {
        if (query == null || query.isBlank()) return null;
        Matcher matcher = IDENTIFIER.matcher(query.trim());
        return matcher.matches()
                ? new QueryColumn(matcher.group(1) == null ? null : matcher.group(1).toUpperCase(Locale.ROOT),
                        matcher.group(2).toUpperCase(Locale.ROOT))
                : null;
    }

    private Pattern exactIdentifier(String column) {
        return Pattern.compile("(?i)(?<![A-Z0-9_$#])" + Pattern.quote(column) + "(?![A-Z0-9_$#])");
    }

    private record QueryColumn(String table, String column) { }
    private record Node(String table, String column) {
        private String key() { return table + "." + column; }
    }
    private record NodeAtDepth(Node node, int depth) { }

    private static final class ResultAccumulator {
        private final PlsqlChunk chunk;
        private final Set<Integer> lineNumbers = new LinkedHashSet<>();
        private final Map<String, SqlColumnDependencyAnalyzer.ColumnDependency> edges = new LinkedHashMap<>();
        private double score;

        private ResultAccumulator(PlsqlChunk chunk) { this.chunk = chunk; }

        private ChunkRetrievalResult toResult(Pattern queryIdentifier) {
            String[] sourceLines = chunk.content().split("\\R", -1);
            List<MatchedLine> matchedLines = new ArrayList<>();
            for (int offset = 0; offset < sourceLines.length; offset++) {
                int lineNumber = chunk.startLine() + offset;
                if (lineNumbers.contains(lineNumber) || queryIdentifier.matcher(sourceLines[offset]).find()) {
                    matchedLines.add(new MatchedLine(lineNumber, sourceLines[offset]));
                }
            }
            return new ChunkRetrievalResult(chunk.chunkId(), chunk.packageName(), chunk.procedureName(), chunk.fileName(),
                    chunk.startLine(), chunk.endLine(), score, List.copyOf(matchedLines), chunk.content(),
                    List.copyOf(edges.values()));
        }
    }
}
