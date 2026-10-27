package com.example.lineage.assistant;

import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import com.example.lineage.callgraph.ProcedureReference;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class LineageAssistantService {

    private static final int DEFAULT_RETRIEVAL_LIMIT = 8;
    private static final int MAXIMUM_RETRIEVAL_LIMIT = 12;
    private static final int MAXIMUM_CALL_GRAPH_DEPTH = 8;
    private static final int MAXIMUM_CALL_GRAPH_EDGES = 32;

    private final ChunkRetrievalService retrievalService;
    private final LineageAssistantModel model;
    private final ProcedureCallGraphService callGraphService;

    @Autowired
    public LineageAssistantService(
            ChunkRetrievalService retrievalService,
            LineageAssistantModel model,
            ProcedureCallGraphService callGraphService,
            @Value("${lineage.assistant.retrieval-limit:8}") int retrievalLimit,
            @Value("${lineage.assistant.call-graph.maximum-depth:4}") int callGraphMaximumDepth,
            @Value("${lineage.assistant.call-graph.maximum-edges:12}") int callGraphMaximumEdges
    ) {
        if (retrievalLimit < 1 || retrievalLimit > MAXIMUM_RETRIEVAL_LIMIT) {
            throw new IllegalArgumentException("lineage.assistant.retrieval-limit must be between 1 and 12");
        }
        if (callGraphMaximumDepth < 0 || callGraphMaximumDepth > MAXIMUM_CALL_GRAPH_DEPTH) {
            throw new IllegalArgumentException("lineage.assistant.call-graph.maximum-depth must be between 0 and 8");
        }
        if (callGraphMaximumEdges < 0 || callGraphMaximumEdges > MAXIMUM_CALL_GRAPH_EDGES) {
            throw new IllegalArgumentException("lineage.assistant.call-graph.maximum-edges must be between 0 and 32");
        }
        this.retrievalService = retrievalService;
        this.model = model;
        this.callGraphService = callGraphService;
        this.retrievalLimit = retrievalLimit;
        this.callGraphMaximumDepth = callGraphMaximumDepth;
        this.callGraphMaximumEdges = callGraphMaximumEdges;
    }

    public LineageAssistantService(ChunkRetrievalService retrievalService, LineageAssistantModel model) {
        this(retrievalService, model, null, DEFAULT_RETRIEVAL_LIMIT, 4, 12);
    }

    private final int retrievalLimit;
    private final int callGraphMaximumDepth;
    private final int callGraphMaximumEdges;

    public AskResponse ask(AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }

        String column = request.column() == null || request.column().isBlank()
                ? null : request.column().trim();
        List<ChunkRetrievalResult> evidence = column == null
                ? List.of()
                : retrievalService.search(column, retrievalLimit);
        List<ProcedureCallEdge> procedureCalls = callGraphService == null || evidence.isEmpty()
                ? List.of()
                : callGraphService.findRelatedEdges(
                        evidence.stream()
                                .map(chunk -> new ProcedureReference(chunk.packageName(), chunk.procedureName()))
                                .distinct()
                                .toList(),
                        callGraphMaximumDepth,
                        callGraphMaximumEdges
                );
        List<AssistantSource> sources = toSources(evidence);
        String answer = model.answer(request.question().trim(), column, evidence, procedureCalls);

        List<String> limitations = new ArrayList<>();
        if (evidence.stream().anyMatch(chunk -> chunk.content().indexOf('\ufffd') >= 0)) {
            limitations.add("檢索證據含有替代字元（�），部分原始文字可能無法正確解碼；請核對原始檔案，尤其是條件字串與識別字。");
        }
        limitations.add("檢索目前只支援欄位名稱或 TABLE.COLUMN 的識別字比對，不支援自然語言語意檢索。");
        if (column == null) {
            limitations.add("未提供 column，未檢索 PL/SQL 程式碼；回答不可視為有程式碼證據的血緣結論。");
        } else if (evidence.isEmpty()) {
            limitations.add("指定欄位沒有找到匹配 Chunk，模型只能回覆證據不足。");
        }
        if (column != null && !evidence.isEmpty() && procedureCalls.isEmpty()) {
            limitations.add("未找到可驗證的相關 Procedure 直接呼叫邊；不可根據資料處理順序推論呼叫關係。");
        }
        limitations.add("請以回應中的 sources 作為檔案與行號依據；模型回答是根據檢索證據生成，仍需人工核對。");
        return new AskResponse(answer, List.copyOf(sources), List.copyOf(limitations), procedureCalls);
    }

    private List<AssistantSource> toSources(List<ChunkRetrievalResult> evidence) {
        List<AssistantSource> sources = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            ChunkRetrievalResult chunk = evidence.get(index);
            sources.add(new AssistantSource(
                    "S" + (index + 1), chunk.chunkId(), chunk.packageName(), chunk.procedureName(),
                    chunk.fileName(), chunk.startLine(), chunk.endLine(), chunk.matchedLines()
            ));
        }
        return List.copyOf(sources);
    }
}
