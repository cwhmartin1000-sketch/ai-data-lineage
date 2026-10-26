package com.example.lineage.callgraph;

import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureCallGraphServiceTest {

    @Test
    void expandsOnlyEdgesConnectedToRetrievedProceduresAndHonorsBounds() {
        Path packages = Path.of("..", "packages").toAbsolutePath().normalize();
        ChunkRetrievalService retrieval = new ChunkRetrievalService(
                new PlsqlChunkingService(packages.toString(), 120));
        ProcedureCallGraphService graph = new ProcedureCallGraphService(
                new PlsqlChunkingService(packages.toString(), 120), new ProcedureCallAnalyzer());
        List<ProcedureReference> seeds = retrieval.search("TGT_POLICY.EFFECTIVE_DATE", 8).stream()
                .map(chunk -> new ProcedureReference(chunk.packageName(), chunk.procedureName()))
                .distinct().toList();

        List<ProcedureCallEdge> allConnected = graph.findRelatedEdges(seeds, 4, 12);
        List<ProcedureCallEdge> shallow = graph.findRelatedEdges(seeds, 1, 12);
        List<ProcedureCallEdge> limited = graph.findRelatedEdges(seeds, 4, 2);

        assertThat(allConnected).hasSize(5);
        assertThat(shallow).hasSize(3);
        assertThat(limited).hasSize(2);
        assertThat(allConnected).allSatisfy(edge -> assertThat(edge.lineNumber()).isPositive());
    }

    @Test
    void terminatesOnCallCyclesAndReturnsEachCallOnce() throws IOException {
        Path packages = Files.createTempDirectory("call-graph-cycle");
        Files.writeString(packages.resolve("PKG_CYCLE.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_CYCLE AS
                    PROCEDURE FIRST_PROC IS
                    BEGIN
                        SECOND_PROC;
                    END FIRST_PROC;
                    PROCEDURE SECOND_PROC IS
                    BEGIN
                        FIRST_PROC;
                    END SECOND_PROC;
                END PKG_CYCLE;
                /
                """);
        ProcedureCallGraphService graph = new ProcedureCallGraphService(
                new PlsqlChunkingService(packages.toString(), 120), new ProcedureCallAnalyzer());

        List<ProcedureCallEdge> edges = graph.findRelatedEdges(
                List.of(new ProcedureReference("PKG_CYCLE", "FIRST_PROC")), 8, 32);

        assertThat(edges).hasSize(2).doesNotHaveDuplicates();
    }
}
