package com.example.lineage.chunk;

import com.example.lineage.callgraph.ProcedureCallAnalyzer;
import com.example.lineage.callgraph.ProcedureCallGraphService;
import com.example.lineage.callgraph.ProcedureReference;
import com.example.lineage.retrieval.ChunkRetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ExternalPackagesLoadingTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recursivelyLoadsExternalSourcesAndPreservesRelativePathsAndOriginalLines() throws IOException {
        Path fixture = temporaryDirectory.resolve("external-pck");
        copyMockPackages(fixture);
        PlsqlChunkingService chunking = new PlsqlChunkingService(fixture, 120);

        List<PlsqlChunk> chunks = chunking.loadChunks();
        PackageScanStatistics stats = chunking.statistics();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.fileName()).isEqualTo("nested/deep/PKG_POLICY_LOAD.pkb");
            assertThat(chunk.procedureName()).isEqualTo("LOAD_TARGET");
            assertThat(chunk.startLine()).isEqualTo(10);
            assertThat(chunk.content()).contains("MERGE INTO TGT_POLICY");
        });
        assertThat(stats.rootDirectory()).isEqualTo(fixture.toRealPath().toString());
        assertThat(stats.scannedFiles()).isEqualTo(6);
        assertThat(stats.readFiles()).isEqualTo(6);
        assertThat(stats.chunkCount()).isEqualTo(chunks.size());
        assertThat(stats.failedFiles()).isZero();

        ChunkRetrievalService retrieval = new ChunkRetrievalService(chunking);
        assertThat(retrieval.search("TGT_POLICY.EFFECTIVE_DATE", 8))
                .extracting(result -> result.fileName())
                .contains("nested/deep/PKG_POLICY_LOAD.pkb", "nested/deep/PKG_POLICY_TRANSFORM.pkb",
                        "nested/deep/PKG_POLICY_EXTRACT.pkb");

        ProcedureCallGraphService callGraph = new ProcedureCallGraphService(chunking, new ProcedureCallAnalyzer());
        assertThat(callGraph.findRelatedEdges(List.of(new ProcedureReference("PKG_POLICY_LOAD", "LOAD_TARGET")), 4, 12))
                .hasSize(5)
                .allSatisfy(edge -> assertThat(edge.fileName()).startsWith("nested/deep/"));
    }

    @Test
    void supportsAllRequiredExtensionsWithoutCaseSensitivity() throws IOException {
        Path fixture = temporaryDirectory.resolve("extensions");
        Files.createDirectories(fixture.resolve("nested"));
        String body = "CREATE OR REPLACE PACKAGE BODY PKG_EXT AS\n"
                + "  PROCEDURE RUN_IT IS\n  BEGIN\n    NULL;\n  END RUN_IT;\nEND PKG_EXT;\n/\n";
        for (String name : List.of("one.PCK", "two.PKB", "three.PKS", "four.SQL")) {
            Files.writeString(fixture.resolve("nested").resolve(name), body);
        }
        Files.writeString(fixture.resolve("ignored.txt"), body);

        PlsqlChunkingService service = new PlsqlChunkingService(fixture, 120);

        assertThat(service.statistics().scannedFiles()).isEqualTo(4);
        assertThat(service.statistics().readFiles()).isEqualTo(4);
        assertThat(service.loadChunks()).extracting(PlsqlChunk::fileName)
                .contains("nested/one.PCK", "nested/two.PKB", "nested/three.PKS", "nested/four.SQL");
    }

    @Test
    void reportsInvalidRootInsteadOfSilentlyFallingBack() {
        Path missingRoot = temporaryDirectory.resolve("missing");
        PlsqlChunkingService service = new PlsqlChunkingService(missingRoot, 120);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(service::loadChunks)
                .withMessageContaining("does not exist or is not readable")
                .withMessageContaining(missingRoot.toString());
    }

    @Test
    void resolvesDefaultPackagesDirectoryWhenStartedFromBackendDirectory() {
        PlsqlChunkingService service = new PlsqlChunkingService("", 120);

        assertThat(service.statistics().scannedFiles()).isEqualTo(6);
        assertThat(service.statistics().readFiles()).isEqualTo(6);
    }

    @Test
    void skipsAnOversizedFileAndContinuesReadingOtherFiles() throws IOException {
        Path fixture = temporaryDirectory.resolve("partial-failure");
        Files.createDirectories(fixture);
        String valid = "CREATE OR REPLACE PACKAGE BODY P AS\nEND P;\n";
        Files.writeString(fixture.resolve("valid.pkb"), valid);
        Files.writeString(fixture.resolve("too-large.pkb"), valid + " ".repeat(500));

        PlsqlChunkingService service = new PlsqlChunkingService(fixture, 120, 10, 128, 1024);

        assertThat(service.loadChunks()).isNotEmpty();
        assertThat(service.statistics().scannedFiles()).isEqualTo(2);
        assertThat(service.statistics().readFiles()).isEqualTo(1);
        assertThat(service.statistics().failedFiles()).isEqualTo(1);
        assertThat(service.statistics().errorSummary()).anySatisfy(message ->
                assertThat(message).contains("too-large.pkb", "size limit"));
    }

    private void copyMockPackages(Path destination) throws IOException {
        Path source = Path.of("..", "packages").toAbsolutePath().normalize();
        Path nested = destination.resolve("nested").resolve("deep");
        Files.createDirectories(nested);
        for (String packageName : List.of("PKG_POLICY_EXTRACT", "PKG_POLICY_TRANSFORM", "PKG_POLICY_LOAD")) {
            for (String extension : List.of(".pkb", ".pks")) {
                Files.copy(source.resolve(packageName + extension), nested.resolve(packageName + extension));
            }
        }
    }
}
