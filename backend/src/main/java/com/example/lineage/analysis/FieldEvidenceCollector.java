package com.example.lineage.analysis;

import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.callgraph.ProcedureReference;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import com.example.lineage.retrieval.MatchedLine;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;
import java.util.regex.Pattern;

/** Collects candidates, not proven lineage. Whole routines retain surrounding conditions and record arguments. */
@Service
public class FieldEvidenceCollector {
    private final PlsqlChunkingService chunking;
    private final ChunkRetrievalService retrieval;
    private final ProcedureCallGraphService calls;

    public FieldEvidenceCollector(PlsqlChunkingService chunking, ChunkRetrievalService retrieval,
                                  ProcedureCallGraphService calls) {
        this.chunking = chunking;
        this.retrieval = retrieval;
        this.calls = calls;
    }

    public Evidence collect(String column) {
        if (column == null || !column.matches("(?i)[A-Z][A-Z0-9_$#]*(\\.[A-Z][A-Z0-9_$#]*)?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "請提供欄位名稱或 TABLE.COLUMN。");
        }
        String field = column.substring(column.lastIndexOf('.') + 1);
        Pattern name = Pattern.compile("(?i)(?<![A-Z0-9_$#])" + Pattern.quote(field) + "(?![A-Z0-9_$#])");
        List<PlsqlChunk> all = chunking.loadChunks();
        Map<String, ChunkRetrievalResult> candidates = new LinkedHashMap<>();
        // Dependency retrieval remains strict for TABLE.COLUMN. Lexical hits below are explicitly only candidates.
        retrieval.search(column, Integer.MAX_VALUE).forEach(c -> candidates.put(c.chunkId(), c));
        int directMatches = 0;
        for (PlsqlChunk c : all) {
            if (name.matcher(c.content()).find()) {
                directMatches++;
                candidates.putIfAbsent(c.chunkId(), result(c, name));
            }
        }
        if (candidates.isEmpty()) return new Evidence(List.of(), List.of(), 0, List.of());
        List<ProcedureReference> seeds = candidates.values().stream()
                .filter(c -> !c.procedureName().equals("UNKNOWN"))
                .map(c -> new ProcedureReference(c.packageName(), c.procedureName())).distinct().toList();
        // Retrieve one sentinel edge beyond the limit so truncation is visible.
        List<ProcedureCallEdge> expanded = calls.findRelatedEdges(seeds, 2, 257);
        List<String> gaps = new ArrayList<>();
        if (expanded.size() > 256) gaps.add("相關呼叫邊超過 256 條，已截斷。");
        List<ProcedureCallEdge> edges = expanded.stream().limit(256).toList();
        Set<String> routines = new HashSet<>();
        candidates.values().stream().filter(c -> !c.procedureName().equals("UNKNOWN"))
                .forEach(c -> routines.add(key(c.packageName(), c.procedureName())));
        for (ProcedureCallEdge edge : edges) {
            routines.add(key(edge.callerPackage(), edge.callerProcedure()));
            routines.add(key(edge.calleePackage(), edge.calleeProcedure()));
        }
        // Restore all pieces of a long routine, not just the piece containing the field.
        Set<String> files = new HashSet<>();
        for (PlsqlChunk c : all) {
            if (!c.procedureName().equals("UNKNOWN") && routines.contains(key(c.packageName(), c.procedureName()))) {
                candidates.putIfAbsent(c.chunkId(), result(c, name));
                files.add(c.fileName());
            }
        }
        // Package declarations can bind record variables / cursor types used inside the selected routines.
        for (PlsqlChunk c : all) {
            if (files.contains(c.fileName()) && c.procedureName().equals("UNKNOWN"))
                candidates.putIfAbsent(c.chunkId(), result(c, name));
        }
        return new Evidence(List.copyOf(candidates.values()), edges, directMatches, List.copyOf(gaps));
    }

    private String key(String pkg, String routine) { return (pkg + "." + routine).toUpperCase(Locale.ROOT); }

    private ChunkRetrievalResult result(PlsqlChunk c, Pattern name) {
        List<MatchedLine> hits = new ArrayList<>();
        String[] lines = c.content().split("\\R", -1);
        for (int i = 0; i < lines.length; i++)
            if (name.matcher(lines[i]).find()) hits.add(new MatchedLine(c.startLine() + i, lines[i]));
        return new ChunkRetrievalResult(c.chunkId(), c.packageName(), c.procedureName(), c.fileName(),
                c.startLine(), c.endLine(), 0, List.copyOf(hits), c.content());
    }

    public record Evidence(List<ChunkRetrievalResult> chunks, List<ProcedureCallEdge> calls,
                           int directMatchChunks, List<String> gaps) { }
}
