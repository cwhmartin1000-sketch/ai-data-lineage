package com.example.lineage.assistant;

import com.example.lineage.callgraph.ProcedureCallAnalyzer;
import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import com.example.lineage.callgraph.ProcedureReference;
import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import com.example.lineage.retrieval.MatchedLine;
import com.example.lineage.retrieval.SqlColumnDependencyAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractChatClientLineageAssistantModelTest {

    @Test
    void promptContainsVerifiedEdgesAndPreservesChunkCitationAndOriginalLines() {
        SqlColumnDependencyAnalyzer.ColumnDependency edge = new SqlColumnDependencyAnalyzer.ColumnDependency(
                "TGT_POLICY", "EFFECTIVE_DATE", "STG_POLICY_TRANSFORMED", "CALCULATED_EFFECTIVE_DATE",
                "load-chunk", "PKG_POLICY_LOAD", "LOAD_TARGET", "PKG_POLICY_LOAD.pkb",
                20, 31, 27
        );
        ChunkRetrievalResult chunk = new ChunkRetrievalResult(
                "load-chunk", "PKG_POLICY_LOAD", "LOAD_TARGET", "PKG_POLICY_LOAD.pkb", 20, 31,
                100.0, List.of(new MatchedLine(27, "t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE")),
                "t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE\n", List.of(edge)
        );

        String prompt = AbstractChatClientLineageAssistantModel.formatUserPrompt(
                "來源是什麼？", "TGT_POLICY.EFFECTIVE_DATE", List.of(chunk));

        assertThat(prompt).contains("Verified SQL Dependencies")
                .contains("STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE -> TGT_POLICY.EFFECTIVE_DATE [S1]")
                .contains("package=PKG_POLICY_LOAD; procedure=LOAD_TARGET; file=PKG_POLICY_LOAD.pkb; line=27")
                .contains("[S1] file=PKG_POLICY_LOAD.pkb; package=PKG_POLICY_LOAD; procedure=LOAD_TARGET; lines=20-31")
                .contains("20: t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE")
                .doesNotContain("SRC_POLICY.POLICY_START_DT -> TGT_POLICY.EFFECTIVE_DATE");
    }

    @Test
    void q1RetrievalPromptIncludesTheActualConnectedUpstreamEdges() {
        ChunkRetrievalService retrieval = new ChunkRetrievalService(
                new PlsqlChunkingService(Path.of("..", "packages").toAbsolutePath().normalize().toString(), 120));
        List<ChunkRetrievalResult> evidence = retrieval.search("TGT_POLICY.EFFECTIVE_DATE", 8);

        String prompt = AbstractChatClientLineageAssistantModel.formatUserPrompt(
                "TGT_POLICY.EFFECTIVE_DATE 的原始來源欄位是什麼？",
                "TGT_POLICY.EFFECTIVE_DATE", evidence);

        assertThat(evidence).extracting(ChunkRetrievalResult::packageName)
                .contains("PKG_POLICY_LOAD", "PKG_POLICY_TRANSFORM", "PKG_POLICY_EXTRACT");
        assertThat(prompt)
                .contains("STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE -> TGT_POLICY.EFFECTIVE_DATE")
                .contains("STG_POLICY.START_DATE -> STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE")
                .contains("SRC_POLICY.POLICY_START_DT -> STG_POLICY.START_DATE")
                .contains("SRC_POLICY.REQUESTED_EFFECTIVE_DT -> STG_POLICY.REQUESTED_START_DATE")
                .contains("[S1] file=")
                .contains("原始 SQL Chunk 證據（行號為來源檔案行號）");
    }

    @Test
    void formatsCallEdgesSeparatelyFromDataDependenciesWithOriginalFileLinesAndCode() {
        Path packages = Path.of("..", "packages").toAbsolutePath().normalize();
        PlsqlChunkingService chunking = new PlsqlChunkingService(packages.toString(), 120);
        ChunkRetrievalService retrieval = new ChunkRetrievalService(chunking);
        List<ChunkRetrievalResult> data = retrieval.search("TGT_POLICY.EFFECTIVE_DATE", 8);
        ProcedureCallGraphService graph = new ProcedureCallGraphService(chunking, new ProcedureCallAnalyzer());
        List<ProcedureReference> seeds = data.stream()
                .map(chunk -> new ProcedureReference(chunk.packageName(), chunk.procedureName()))
                .distinct().toList();
        List<ProcedureCallEdge> calls = graph.findRelatedEdges(seeds, 4, 12);

        String prompt = AbstractChatClientLineageAssistantModel.formatUserPrompt(
                "說明欄位與 Procedure 呼叫關係", "TGT_POLICY.EFFECTIVE_DATE", data, calls);
        String callSection = prompt.substring(prompt.indexOf("Verified Procedure Calls"),
                prompt.indexOf("原始 SQL Chunk 證據"));

        assertThat(calls).hasSize(5);
        assertThat(prompt).contains("Verified Data Dependencies")
                .contains("Verified Procedure Calls")
                .contains("[S1] file=")
                .contains("PKG_POLICY_EXTRACT.RUN_EXTRACT -> PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE; file=PKG_POLICY_EXTRACT.pkb; line=5; code=LOAD_SOURCE_TO_STAGE;")
                .contains("PKG_POLICY_LOAD.RUN_FULL_LOAD -> PKG_POLICY_TRANSFORM.RUN_TRANSFORM; file=PKG_POLICY_LOAD.pkb; line=6; code=PKG_POLICY_TRANSFORM.RUN_TRANSFORM;")
                .contains("PKG_POLICY_LOAD.RUN_FULL_LOAD -> PKG_POLICY_LOAD.LOAD_TARGET; file=PKG_POLICY_LOAD.pkb; line=7; code=LOAD_TARGET;")
                .contains("PKG_POLICY_TRANSFORM.RUN_TRANSFORM -> PKG_POLICY_EXTRACT.RUN_EXTRACT; file=PKG_POLICY_TRANSFORM.pkb; line=6; code=PKG_POLICY_EXTRACT.RUN_EXTRACT;")
                .contains("PKG_POLICY_TRANSFORM.RUN_TRANSFORM -> PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS; file=PKG_POLICY_TRANSFORM.pkb; line=7; code=BUILD_TRANSFORMED_ROWS;");
        assertThat(callSection)
                .doesNotContain("PKG_POLICY_EXTRACT.LOAD_SOURCE_TO_STAGE -> PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS")
                .doesNotContain("PKG_POLICY_TRANSFORM.BUILD_TRANSFORMED_ROWS -> PKG_POLICY_LOAD.LOAD_TARGET");
    }

    @Test
    void explicitlyGroundsNoCallEvidenceAsUnconfirmed() {
        String prompt = AbstractChatClientLineageAssistantModel.formatUserPrompt(
                "呼叫關係為何？", "UNKNOWN_COLUMN", List.of(), List.of());

        assertThat(prompt).contains("Verified Data Dependencies")
                .contains("Verified Procedure Calls")
                .contains("無法確認直接呼叫關係");
    }
}
