package com.example.lineage.retrieval;

import com.example.lineage.chunk.PlsqlChunkingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkRetrievalServiceTest {

    @TempDir
    Path temporaryPackagesDirectory;

    private final Path packagesDirectory = Path.of("..", "packages").toAbsolutePath().normalize();
    private ChunkRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new ChunkRetrievalService(new PlsqlChunkingService(packagesDirectory.toString(), 120));
    }

    @Test
    void retrievesExactIdentifierAcrossTransformAndLoadChunks() {
        List<ChunkRetrievalResult> results = service.search("CALCULATED_EFFECTIVE_DATE", 5);

        assertThat(results).extracting(ChunkRetrievalResult::packageName)
                .contains("PKG_POLICY_TRANSFORM", "PKG_POLICY_LOAD");
        assertThat(results).allSatisfy(result -> assertThat(result.matchedLines()).isNotEmpty());
    }

    @Test
    void acceptsQualifiedColumnAndDoesNotMatchIdentifierSubstrings() {
        List<ChunkRetrievalResult> qualifiedResults = service.search("TGT_POLICY.EFFECTIVE_DATE", 5);
        List<ChunkRetrievalResult> effectiveDateResults = service.search("EFFECTIVE_DATE", 5);

        assertThat(qualifiedResults).extracting(ChunkRetrievalResult::packageName)
                .contains("PKG_POLICY_LOAD", "PKG_POLICY_TRANSFORM");
        assertThat(effectiveDateResults).extracting(ChunkRetrievalResult::packageName)
                .contains("PKG_POLICY_EXTRACT", "PKG_POLICY_LOAD", "PKG_POLICY_TRANSFORM");
        assertThat(effectiveDateResults).filteredOn(result -> result.packageName().equals("PKG_POLICY_TRANSFORM"))
                .allSatisfy(result -> assertThat(result.content()).contains("CALCULATED_EFFECTIVE_DATE"));
        assertThat(effectiveDateResults).allSatisfy(result -> assertThat(result.procedureName()).isNotBlank());
    }

    @Test
    void recursivelyFollowsTargetThroughTransformAndExtractToSourcePolicy() {
        List<ChunkRetrievalResult> results = service.search("TGT_POLICY.EFFECTIVE_DATE", 10);

        assertThat(results).extracting(ChunkRetrievalResult::packageName)
                .contains("PKG_POLICY_LOAD", "PKG_POLICY_TRANSFORM", "PKG_POLICY_EXTRACT");
        List<SqlColumnDependencyAnalyzer.ColumnDependency> edges = results.stream()
                .flatMap(result -> result.dependencyEdges().stream())
                .toList();
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.targetIdentifier()).isEqualTo("TGT_POLICY.EFFECTIVE_DATE");
            assertThat(edge.sourceIdentifier()).isEqualTo("STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.targetIdentifier()).isEqualTo("STG_POLICY.START_DATE");
            assertThat(edge.sourceIdentifier()).isEqualTo("SRC_POLICY.POLICY_START_DT");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.targetIdentifier()).isEqualTo("STG_POLICY.REQUESTED_START_DATE");
            assertThat(edge.sourceIdentifier()).isEqualTo("SRC_POLICY.REQUESTED_EFFECTIVE_DT");
        });
        assertThat(edges).allSatisfy(edge -> {
            assertThat(edge.packageName()).isNotBlank();
            assertThat(edge.procedureName()).isNotBlank();
            assertThat(edge.fileName()).endsWith(".pkb");
            assertThat(edge.evidenceLine()).isBetween(edge.startLine(), edge.endLine());
        });
    }

    @Test
    void qualifiedArchiveNameDoesNotMatchSourcePolicyNameWithoutDependencyEvidence() {
        assertThat(service.search("SRC_POLICY_ARCHIVE.POLICY_START_DT", 10)).isEmpty();

        List<ChunkRetrievalResult> results = service.search("TGT_POLICY.EFFECTIVE_DATE", 10);
        assertThat(results).flatMap(ChunkRetrievalResult::dependencyEdges)
                .allSatisfy(edge -> assertThat(edge.sourceTable()).isNotEqualTo("SRC_POLICY_ARCHIVE"));
    }

    @Test
    void cycleDetectionDeduplicatesEdgesAndChunks() throws Exception {
        writePackage("PKG_CYCLE.pkb", """
                CREATE OR REPLACE PACKAGE BODY PKG_CYCLE AS
                    PROCEDURE WRITE_A IS
                    BEGIN
                        INSERT INTO A (ID) SELECT b.ID FROM B b;
                    END WRITE_A;
                    PROCEDURE WRITE_B IS
                    BEGIN
                        INSERT INTO B (ID) SELECT a.ID FROM A a;
                    END WRITE_B;
                END PKG_CYCLE;
                /
                """);
        ChunkRetrievalService cyclicService = new ChunkRetrievalService(
                new PlsqlChunkingService(temporaryPackagesDirectory.toString(), 120));

        List<ChunkRetrievalResult> results = cyclicService.search("A.ID", 20);

        assertThat(results).extracting(ChunkRetrievalResult::procedureName).containsExactlyInAnyOrder("WRITE_A", "WRITE_B");
        List<String> edgeKeys = results.stream().flatMap(result -> result.dependencyEdges().stream())
                .map(edge -> edge.targetIdentifier() + "<-" + edge.sourceIdentifier())
                .distinct().toList();
        assertThat(edgeKeys).containsExactlyInAnyOrder("A.ID<-B.ID", "B.ID<-A.ID");
    }

    @Test
    void maximumDependencyDepthStopsTraversalBeyondConfiguredBoundary() throws Exception {
        writePackage("PKG_DEPTH.pkb", """
                CREATE OR REPLACE PACKAGE BODY PKG_DEPTH AS
                    PROCEDURE WRITE_A IS
                    BEGIN
                        INSERT INTO A (ID) SELECT b.ID FROM B b;
                    END WRITE_A;
                    PROCEDURE WRITE_B IS
                    BEGIN
                        INSERT INTO B (ID) SELECT c.ID FROM C c;
                    END WRITE_B;
                    PROCEDURE WRITE_C IS
                    BEGIN
                        INSERT INTO C (ID) SELECT d.ID FROM D d;
                    END WRITE_C;
                END PKG_DEPTH;
                /
                """);
        ChunkRetrievalService depthLimitedService = new ChunkRetrievalService(
                new PlsqlChunkingService(temporaryPackagesDirectory.toString(), 120),
                new SqlColumnDependencyAnalyzer(), 1);

        List<ChunkRetrievalResult> results = depthLimitedService.search("A.ID", 20);

        assertThat(results).extracting(ChunkRetrievalResult::procedureName)
                .contains("WRITE_A", "WRITE_B")
                .doesNotContain("WRITE_C");
    }

    @Test
    void returnsNoResultsForUnknownColumnAndHonorsLimit() {
        assertThat(service.search("COLUMN_THAT_DOES_NOT_EXIST", 5)).isEmpty();
        assertThat(service.search("CALCULATED_EFFECTIVE_DATE", 1)).hasSize(1);
    }

    @Test
    void matchedLineNumbersAndCodeCorrespondToOriginalSource() throws Exception {
        List<ChunkRetrievalResult> results = service.search("CALCULATED_EFFECTIVE_DATE", 5);

        for (ChunkRetrievalResult result : results) {
            List<String> originalLines = Files.readAllLines(packagesDirectory.resolve(result.fileName()));
            for (MatchedLine matchedLine : result.matchedLines()) {
                assertThat(matchedLine.lineNumber()).isBetween(result.startLine(), result.endLine());
                assertThat(matchedLine.code()).isEqualTo(originalLines.get(matchedLine.lineNumber() - 1));
            }
            for (SqlColumnDependencyAnalyzer.ColumnDependency edge : result.dependencyEdges()) {
                assertThat(edge.fileName()).isEqualTo(result.fileName());
                assertThat(edge.evidenceLine()).isBetween(result.startLine(), result.endLine());
                assertThat(originalLines.get(edge.evidenceLine() - 1)).isNotBlank();
            }
        }
    }

    @Test
    void malformedQueryIsNotInterpretedAsAColumnName() {
        assertThat(service.search("find CALCULATED_EFFECTIVE_DATE", 5)).isEmpty();
    }

    private void writePackage(String fileName, String source) throws Exception {
        Files.writeString(temporaryPackagesDirectory.resolve(fileName), source);
    }
}
