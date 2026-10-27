package com.example.lineage.analysis;

import com.example.lineage.assistant.*;
import com.example.lineage.chunk.*;
import com.example.lineage.callgraph.*;
import com.example.lineage.retrieval.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FieldAnalysisServiceTest {
    @TempDir Path directory;

    @Test
    void includesOuterConditionsAndRecordWriterEvenWithoutFieldNameInWriter() throws Exception {
        Files.writeString(directory.resolve("rules.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_RULES AS
                  PROCEDURE RUN_CHECK(p_enabled VARCHAR2) IS
                    l_row PODT%ROWTYPE;
                  BEGIN
                    IF p_enabled = 'Y' THEN
                      SELECT * INTO l_row FROM PODT WHERE POLICY_NO = 'TEST';
                      IF l_row.CHECK_CODE IS NOT NULL THEN
                        l_row.OTHER_NOTE := 'checked';
                        PKG_STORE.SAVE_ROW(l_row);
                      ELSE
                        l_row.CHECK_CODE := 'PENDING';
                      END IF;
                    END IF;
                  END RUN_CHECK;
                END PKG_RULES;
                """);
        Files.writeString(directory.resolve("store.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_STORE AS
                  PROCEDURE SAVE_ROW(p_row IN PODT%ROWTYPE) IS
                  BEGIN
                    INSERT INTO PODT VALUES p_row;
                  END SAVE_ROW;
                END PKG_STORE;
                """);
        PlsqlChunkingService chunks = new PlsqlChunkingService(directory.toString(), 5);
        FieldEvidenceCollector collector = new FieldEvidenceCollector(chunks, new ChunkRetrievalService(chunks),
                new ProcedureCallGraphService(chunks, new ProcedureCallAnalyzer()));
        var evidence = collector.collect("PODT.CHECK_CODE");
        String code = String.join("\n", evidence.chunks().stream().map(ChunkRetrievalResult::content).toList());
        assertThat(code).contains("IF p_enabled = 'Y'", "IF l_row.CHECK_CODE IS NOT NULL", "ELSE",
                "PODT%ROWTYPE", "INSERT INTO PODT VALUES p_row", "PKG_STORE.SAVE_ROW(l_row)");
        assertThat(evidence.calls()).anySatisfy(edge -> {
            assertThat(edge.callerProcedure()).isEqualTo("RUN_CHECK");
            assertThat(edge.calleeProcedure()).isEqualTo("SAVE_ROW");
        });
        List<String> prompts = new ArrayList<>();
        LineageAssistantModel model = (question, column, sources) -> {
            prompts.add(question);
            return "已有值時沿用，需核對整筆 record 映射。[S1]";
        };
        var report = new FieldAnalysisService(collector, chunks, model, 24000, 8, 16000)
                .analyze(new AskRequest("整理用途及檢核條件", "PODT.CHECK_CODE"));
        assertThat(report.coverage().analyzedChunks()).isEqualTo(evidence.chunks().size());
        assertThat(report.coverage().omittedChunks()).isZero();
        assertThat(prompts).allSatisfy(p -> assertThat(p).contains("IF/ELSIF/ELSE", "WHERE/JOIN", "NVL",
                "來源代號", "只讀 SELECT COUNT(*)", "不能把不同分支合併", "整筆 record"));
        assertThat(report.answer()).contains("情境總結", "規則明細與檢核 SQL 草稿");
        assertThat(report.sources()).allSatisfy(source ->
                assertThat(chunks.getChunk(source.chunkId())).isPresent());
    }

    @Test
    void batchesBeyondTopEightAndRemapsCitationsAcrossBatches() {
        List<ChunkRetrievalResult> evidence = new ArrayList<>();
        for (int i = 0; i < 12; i++) evidence.add(chunk(i, "-- context\n".repeat(350)));
        FieldEvidenceCollector collector = mock(FieldEvidenceCollector.class);
        when(collector.collect("PODT.CODE")).thenReturn(new FieldEvidenceCollector.Evidence(evidence, List.of(), 12, List.of()));
        PlsqlChunkingService chunks = stats();
        LineageAssistantModel model = (question, column, batch) -> "本批規則 [S1]；無效引用 [S9999999999999999]";
        var report = new FieldAnalysisService(collector, chunks, model, 12000, 16, 8000)
                .analyze(new AskRequest("規格", "PODT.CODE"));
        assertThat(report.coverage().analyzedChunks()).isEqualTo(12);
        assertThat(report.coverage().completedBatches()).isGreaterThan(1);
        assertThat(report.sources()).hasSize(12);
        assertThat(report.batches().get(1).answer()).contains("[" + report.batches().get(1).sourceIds().getFirst() + "]");
        assertThat(report.answer()).contains("無效引用，待確認").doesNotContain("[S9999999999999999]");
        assertThat(report.limitations()).anyMatch(s -> s.contains("無效引用"));
    }

    @Test
    void marksOmittedRoutinesWithoutCroppingThemAndDoesNotCallModelWhenNothingFits() {
        FieldEvidenceCollector collector = mock(FieldEvidenceCollector.class);
        when(collector.collect("CODE")).thenReturn(new FieldEvidenceCollector.Evidence(
                List.of(chunk(1, "X".repeat(30000))), List.of(), 1, List.of()));
        LineageAssistantModel model = mock(LineageAssistantModel.class);
        var report = new FieldAnalysisService(collector, stats(), model, 12000, 2, 8000)
                .analyze(new AskRequest("條件", "CODE"));
        assertThat(report.coverage().omittedChunks()).isEqualTo(1);
        assertThat(report.sources()).isEmpty();
        assertThat(report.limitations()).anyMatch(s -> s.contains("未裁切送出"));
        verifyNoInteractions(model);
    }

    @Test
    void preservesCompletedDetailsWhenLaterBatchFailsAndSummaryBudgetIsExceeded() {
        FieldEvidenceCollector collector = mock(FieldEvidenceCollector.class);
        when(collector.collect("CODE")).thenReturn(new FieldEvidenceCollector.Evidence(
                List.of(chunk(1, "A".repeat(6000)), chunk(2, "B".repeat(6000))), List.of(), 2, List.of()));
        LineageAssistantModel model = new LineageAssistantModel() {
            int n;
            public String answer(String q, String c, List<ChunkRetrievalResult> b) {
                if (++n == 2) throw new AssistantProviderException("provider failed", null);
                return "規則 [S1] " + "A".repeat(2000);
            }
        };
        var report = new FieldAnalysisService(collector, stats(), model, 12000, 2, 1000)
                .analyze(new AskRequest("條件", "CODE"));
        assertThat(report.coverage().completedBatches()).isEqualTo(1);
        assertThat(report.coverage().failedBatches()).isEqualTo(1);
        assertThat(report.coverage().summaryComplete()).isFalse();
        assertThat(report.batches().getFirst().answer()).contains("規則 [S1]");
        assertThat(report.coverage().omittedChunks()).isEqualTo(1);
    }

    @Test
    void rejectsMissingOrInvalidColumnAndReturnsNoInventedRulesForNoMatches() {
        PlsqlChunkingService chunks = mock(PlsqlChunkingService.class);
        when(chunks.loadChunks()).thenReturn(List.of());
        var retrieval = mock(ChunkRetrievalService.class);
        when(retrieval.search("PODT.CODE", Integer.MAX_VALUE)).thenReturn(List.of());
        var collector = new FieldEvidenceCollector(chunks, retrieval, mock(ProcedureCallGraphService.class));
        assertThatThrownBy(() -> collector.collect("")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> collector.collect("CODE; DROP TABLE T")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(collector.collect("PODT.CODE").chunks()).isEmpty();
    }

    @Test
    void summaryCannotCiteSourcesThatWereNotInItsInputNotes() {
        var collector = mock(FieldEvidenceCollector.class);
        when(collector.collect("CODE")).thenReturn(new FieldEvidenceCollector.Evidence(
                List.of(chunk(1, "CODE := 'A';"), chunk(2, "CODE := 'B';")), List.of(), 2, List.of()));
        LineageAssistantModel model = new LineageAssistantModel() {
            public String answer(String q, String c, List<ChunkRetrievalResult> b) { return "草稿只引用 [S1]"; }
            public String summarizeFieldAnalysis(String c, String notes) { return "臆造跨來源總結 [S2]"; }
        };
        var report = new FieldAnalysisService(collector, stats(), model, 24000, 8, 16000)
                .analyze(new AskRequest("規格", "CODE"));
        assertThat(report.answer()).contains("臆造跨來源總結 [無效引用，待確認]");
        assertThat(report.limitations()).anyMatch(s -> s.contains("總結含未提供的引用"));
    }

    private ChunkRetrievalResult chunk(int n, String content) {
        return new ChunkRetrievalResult("c" + n, "PKG", "P" + n, "file" + n + ".pkb", 1, 100,
                1, List.of(), content);
    }
    private PlsqlChunkingService stats() {
        PlsqlChunkingService service = mock(PlsqlChunkingService.class);
        when(service.statistics()).thenReturn(new PackageScanStatistics("fixture", 20, 19, 30, 1, 1, List.of(), 1, List.of()));
        return service;
    }
}
