package com.example.lineage.assistant;

import com.example.lineage.callgraph.ProcedureCallAnalyzer;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import com.example.lineage.retrieval.MatchedLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineageAssistantServiceTest {

    @Test
    void sendsBoundedVerifiedCallEdgesToModelAlongsideLineageAndReturnsThemSeparately() {
        Path packages = Path.of("..", "packages").toAbsolutePath().normalize();
        PlsqlChunkingService chunking = new PlsqlChunkingService(packages.toString(), 120);
        ChunkRetrievalService retrieval = new ChunkRetrievalService(chunking);
        ProcedureCallGraphService callGraph = new ProcedureCallGraphService(chunking, new ProcedureCallAnalyzer());
        List<com.example.lineage.callgraph.ProcedureCallEdge> passedCalls = new java.util.ArrayList<>();
        LineageAssistantModel model = new LineageAssistantModel() {
            @Override
            public String answer(String question, String column, List<ChunkRetrievalResult> evidence) {
                return "legacy overload";
            }

            @Override
            public String answer(String question, String column, List<ChunkRetrievalResult> evidence,
                                 List<com.example.lineage.callgraph.ProcedureCallEdge> procedureCalls) {
                passedCalls.addAll(procedureCalls);
                return "已依兩類證據分析";
            }
        };
        LineageAssistantService service = new LineageAssistantService(
                retrieval, model, callGraph, 8, 4, 12);

        AskResponse response = service.ask(new AskRequest(
                "TGT_POLICY.EFFECTIVE_DATE 的資料流與實際呼叫關係為何？", "TGT_POLICY.EFFECTIVE_DATE"));

        assertThat(response.sources()).extracting(AssistantSource::procedureName)
                .contains("LOAD_TARGET", "BUILD_TRANSFORMED_ROWS", "LOAD_SOURCE_TO_STAGE");
        assertThat(passedCalls).hasSize(5);
        assertThat(response.procedureCalls()).containsExactlyElementsOf(passedCalls);
    }

    @Test
    void retrievesEvidenceAndBuildsSourcesOnTheServer() {
        ChunkRetrievalService retrieval = mock(ChunkRetrievalService.class);
        ChunkRetrievalResult chunk = new ChunkRetrievalResult(
                "chunk-abc", "PKG_DEMO", "CALCULATE", "PKG_DEMO.pkb", 12, 18, 20.0,
                List.of(new MatchedLine(15, "SELECT SOURCE_DATE AS OUTPUT_DATE")),
                "SELECT SOURCE_DATE AS OUTPUT_DATE\n"
        );
        when(retrieval.search("OUTPUT_DATE", 8)).thenReturn(List.of(chunk));
        LineageAssistantService service = new LineageAssistantService(
                retrieval, (question, column, evidence) -> "輸出日期取自來源日期。[S1]"
        );

        AskResponse response = service.ask(new AskRequest("輸出日期從哪裡來？", "OUTPUT_DATE"));

        verify(retrieval).search("OUTPUT_DATE", 8);
        assertThat(response.answer()).contains("[S1]");
        assertThat(response.sources()).containsExactly(new AssistantSource(
                "S1", "chunk-abc", "PKG_DEMO", "CALCULATE", "PKG_DEMO.pkb", 12, 18,
                List.of(new MatchedLine(15, "SELECT SOURCE_DATE AS OUTPUT_DATE"))
        ));
        assertThat(response.limitations()).anyMatch(text -> text.contains("不支援自然語言語意檢索"));
    }

    @Test
    void doesNotPretendToRetrieveNaturalLanguageWhenColumnIsOmitted() {
        ChunkRetrievalService retrieval = mock(ChunkRetrievalService.class);
        LineageAssistantService service = new LineageAssistantService(
                retrieval,
                (question, column, evidence) -> evidence.isEmpty() ? "目前檢索證據不足。" : "有證據"
        );

        AskResponse response = service.ask(new AskRequest("生效日期從哪裡來？", null));

        assertThat(response.answer()).contains("證據不足");
        assertThat(response.sources()).isEmpty();
        assertThat(response.limitations()).anyMatch(text -> text.contains("未提供 column"));
        verify(retrieval, org.mockito.Mockito.never()).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
    @Test
    void warnsWhenRetrievedEvidenceContainsReplacementCharacters() {
        ChunkRetrievalService retrieval = mock(ChunkRetrievalService.class);
        ChunkRetrievalResult chunk = new ChunkRetrievalResult(
                "damaged", "PKG_DEMO", "CALCULATE", "damaged.pck", 1, 1, 10.0,
                List.of(), "SELECT '客\ufffd' AS OUTPUT_DATE FROM DUAL");
        when(retrieval.search("OUTPUT_DATE", 8)).thenReturn(List.of(chunk));
        LineageAssistantService service = new LineageAssistantService(retrieval,
                (question, column, evidence) -> "需核對來源");

        AskResponse response = service.ask(new AskRequest("欄位用途？", "OUTPUT_DATE"));

        assertThat(response.sources()).hasSize(1);
        assertThat(response.limitations()).anySatisfy(text ->
                assertThat(text).contains("替代字元", "核對原始檔案"));
    }

}
