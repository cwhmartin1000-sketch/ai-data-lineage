package com.example.lineage.callgraph;

import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureCallAnalyzerTest {

    @Test
    void findsOnlyTheFiveDirectCallsInTheMockPackagesWithExactSourceLines() {
        Path packagesDirectory = Path.of("..", "packages").toAbsolutePath().normalize();
        ProcedureCallAnalysis analysis = new ProcedureCallAnalyzer()
                .analyze(new PlsqlChunkingService(packagesDirectory.toString(), 120).loadChunks());

        assertThat(analysis.edges()).hasSize(5);
        assertThat(analysis.unresolvedCalls()).isEmpty();
        assertThat(analysis.edges()).extracting(ProcedureCallEdge::callerPackage,
                        ProcedureCallEdge::callerProcedure, ProcedureCallEdge::calleePackage,
                        ProcedureCallEdge::calleeProcedure, ProcedureCallEdge::fileName,
                        ProcedureCallEdge::lineNumber, ProcedureCallEdge::evidenceCode,
                        ProcedureCallEdge::crossPackage)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PKG_POLICY_EXTRACT", "RUN_EXTRACT",
                                "PKG_POLICY_EXTRACT", "LOAD_SOURCE_TO_STAGE", "PKG_POLICY_EXTRACT.pkb",
                                5, "LOAD_SOURCE_TO_STAGE;", false),
                        org.assertj.core.groups.Tuple.tuple("PKG_POLICY_LOAD", "RUN_FULL_LOAD",
                                "PKG_POLICY_TRANSFORM", "RUN_TRANSFORM", "PKG_POLICY_LOAD.pkb",
                                6, "PKG_POLICY_TRANSFORM.RUN_TRANSFORM;", true),
                        org.assertj.core.groups.Tuple.tuple("PKG_POLICY_LOAD", "RUN_FULL_LOAD",
                                "PKG_POLICY_LOAD", "LOAD_TARGET", "PKG_POLICY_LOAD.pkb",
                                7, "LOAD_TARGET;", false),
                        org.assertj.core.groups.Tuple.tuple("PKG_POLICY_TRANSFORM", "RUN_TRANSFORM",
                                "PKG_POLICY_EXTRACT", "RUN_EXTRACT", "PKG_POLICY_TRANSFORM.pkb",
                                6, "PKG_POLICY_EXTRACT.RUN_EXTRACT;", true),
                        org.assertj.core.groups.Tuple.tuple("PKG_POLICY_TRANSFORM", "RUN_TRANSFORM",
                                "PKG_POLICY_TRANSFORM", "BUILD_TRANSFORMED_ROWS",
                                "PKG_POLICY_TRANSFORM.pkb", 7, "BUILD_TRANSFORMED_ROWS;", false)
                );
    }

    @Test
    void ignoresCommentsStringsDeclarationsAndUnresolvedCalls() throws IOException {
        Path packagesDirectory = Files.createTempDirectory("call-graph-decoys");
        Files.writeString(packagesDirectory.resolve("PKG_SAMPLE.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_SAMPLE AS
                    PROCEDURE RUNNER IS
                    BEGIN
                        -- TARGET_PROC;
                        /*
                           TARGET_PROC;
                        */
                        v_text := 'TARGET_PROC;';
                        UNKNOWN_PROC;
                        TARGET_PROC;
                    END RUNNER;

                    PROCEDURE TARGET_PROC IS
                    BEGIN
                        NULL;
                    END TARGET_PROC;
                END PKG_SAMPLE;
                /
                """);

        ProcedureCallAnalysis analysis = new ProcedureCallAnalyzer()
                .analyze(new PlsqlChunkingService(packagesDirectory.toString(), 120).loadChunks());

        assertThat(analysis.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.callerProcedure()).isEqualTo("RUNNER");
            assertThat(edge.calleeProcedure()).isEqualTo("TARGET_PROC");
            assertThat(edge.lineNumber()).isEqualTo(10);
            assertThat(edge.evidenceCode()).isEqualTo("TARGET_PROC;");
        });
        assertThat(analysis.unresolvedCalls()).singleElement().satisfies(call -> {
            assertThat(call.referencedProcedure()).isEqualTo("UNKNOWN_PROC");
            assertThat(call.lineNumber()).isEqualTo(9);
            assertThat(call.reason()).contains("not declared");
        });
    }

    @Test
    void doesNotResolveSamePackageOverloadsByGuessing() throws IOException {
        Path packagesDirectory = Files.createTempDirectory("call-graph-overload");
        Files.writeString(packagesDirectory.resolve("PKG_SAMPLE.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_SAMPLE AS
                    PROCEDURE RUNNER IS
                    BEGIN
                        TARGET_PROC;
                    END RUNNER;

                    PROCEDURE TARGET_PROC IS
                    BEGIN
                        NULL;
                    END TARGET_PROC;

                    PROCEDURE TARGET_PROC(p_value NUMBER) IS
                    BEGIN
                        NULL;
                    END TARGET_PROC;
                END PKG_SAMPLE;
                /
                """);

        ProcedureCallAnalysis analysis = new ProcedureCallAnalyzer()
                .analyze(new PlsqlChunkingService(packagesDirectory.toString(), 120).loadChunks());

        assertThat(analysis.edges()).isEmpty();
        assertThat(analysis.unresolvedCalls()).singleElement().satisfies(call -> {
            assertThat(call.referencedProcedure()).isEqualTo("TARGET_PROC");
            assertThat(call.reason()).contains("Multiple declarations");
        });
    }
}
