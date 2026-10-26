package com.example.lineage.callgraph;

import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ProcedureCallGraphService {

    private final PlsqlChunkingService chunkingService;
    private final ProcedureCallAnalyzer analyzer;

    public ProcedureCallGraphService(PlsqlChunkingService chunkingService, ProcedureCallAnalyzer analyzer) {
        this.chunkingService = chunkingService;
        this.analyzer = analyzer;
    }

    public ProcedureCallAnalysis findGraph(String packageName, String procedureName) {
        List<PlsqlChunk> chunks = chunkingService.loadChunks();
        ProcedureCallAnalysis analysis = analyzer.analyze(chunks);
        return new ProcedureCallAnalysis(
                analysis.edges().stream()
                        .filter(edge -> packageName == null || edge.callerPackage().equalsIgnoreCase(packageName))
                        .filter(edge -> procedureName == null || edge.callerProcedure().equalsIgnoreCase(procedureName))
                        .toList(),
                analysis.unresolvedCalls().stream()
                        .filter(call -> packageName == null || call.callerPackage().equalsIgnoreCase(packageName))
                        .filter(call -> procedureName == null || call.callerProcedure().equalsIgnoreCase(procedureName))
                        .toList()
        );
    }

    /** Expands around retrieved procedures in either call direction, bounded by depth and edge count. */
    public List<ProcedureCallEdge> findRelatedEdges(
            List<ProcedureReference> seeds,
            int maximumDepth,
            int maximumEdges
    ) {
        if (seeds.isEmpty() || maximumEdges == 0) return List.of();

        List<ProcedureCallEdge> allEdges = analyzer.analyze(chunkingService.loadChunks()).edges();
        Map<String, List<ProcedureCallEdge>> adjacency = new LinkedHashMap<>();
        for (ProcedureCallEdge edge : allEdges) {
            adjacency.computeIfAbsent(key(edge.callerPackage(), edge.callerProcedure()), ignored -> new java.util.ArrayList<>())
                    .add(edge);
            adjacency.computeIfAbsent(key(edge.calleePackage(), edge.calleeProcedure()), ignored -> new java.util.ArrayList<>())
                    .add(edge);
        }

        ArrayDeque<Visit> queue = new ArrayDeque<>();
        Set<String> visitedProcedures = new LinkedHashSet<>();
        for (ProcedureReference seed : seeds) {
            String seedKey = key(seed.packageName(), seed.procedureName());
            if (visitedProcedures.add(seedKey)) queue.add(new Visit(seedKey, 0));
        }

        Map<String, ProcedureCallEdge> selected = new LinkedHashMap<>();
        while (!queue.isEmpty() && selected.size() < maximumEdges) {
            Visit visit = queue.removeFirst();
            if (visit.depth() >= maximumDepth) continue;

            for (ProcedureCallEdge edge : adjacency.getOrDefault(visit.procedureKey(), List.of())) {
                String edgeKey = edge.fileName() + ":" + edge.lineNumber();
                selected.putIfAbsent(edgeKey, edge);
                if (selected.size() >= maximumEdges) break;

                String caller = key(edge.callerPackage(), edge.callerProcedure());
                String callee = key(edge.calleePackage(), edge.calleeProcedure());
                String other = caller.equals(visit.procedureKey()) ? callee : caller;
                if (visitedProcedures.add(other)) queue.addLast(new Visit(other, visit.depth() + 1));
            }
        }
        return List.copyOf(selected.values());
    }

    private String key(String packageName, String procedureName) {
        return packageName.toUpperCase() + "." + procedureName.toUpperCase();
    }

    private record Visit(String procedureKey, int depth) {
    }
}
