package com.example.lineage.chunk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PlsqlChunkingServiceTest {

    @Test
    void chunksAllThreePackagesAndPreservesEverySourceCharacter() throws IOException {
        Path packagesDirectory = Path.of("..", "packages").toAbsolutePath().normalize();
        PlsqlChunkingService service = new PlsqlChunkingService(packagesDirectory, 120);

        List<PlsqlChunk> firstRun = service.loadChunks();
        List<PlsqlChunk> secondRun = service.loadChunks();

        assertThat(firstRun).isNotEmpty();
        assertThat(firstRun.stream().map(PlsqlChunk::packageName).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("PKG_POLICY_EXTRACT", "PKG_POLICY_TRANSFORM", "PKG_POLICY_LOAD");
        assertThat(firstRun.stream().map(PlsqlChunk::fileName).collect(Collectors.toSet()))
                .contains("PKG_POLICY_EXTRACT.pks", "PKG_POLICY_EXTRACT.pkb",
                        "PKG_POLICY_TRANSFORM.pks", "PKG_POLICY_TRANSFORM.pkb",
                        "PKG_POLICY_LOAD.pks", "PKG_POLICY_LOAD.pkb");
        assertThat(firstRun.stream().map(PlsqlChunk::chunkId).toList())
                .containsExactlyElementsOf(secondRun.stream().map(PlsqlChunk::chunkId).toList());

        Set<String> fileNames = firstRun.stream().map(PlsqlChunk::fileName).collect(Collectors.toSet());
        for (String fileName : fileNames) {
            String source = Files.readString(packagesDirectory.resolve(fileName));
            String reconstructed = firstRun.stream()
                    .filter(chunk -> chunk.fileName().equals(fileName))
                    .map(PlsqlChunk::content)
                    .collect(Collectors.joining());
            assertThat(reconstructed).isEqualTo(source);
        }
    }

    @Test
    void reportsExactLineRangesAndKeepsCaseJoinAndMergeStatementsWholeWhenSplitting() throws IOException {
        Path packagesDirectory = Files.createTempDirectory("plsql-chunks");
        String source = """
                CREATE OR REPLACE PACKAGE BODY PKG_LONG AS
                    PROCEDURE LOAD_LONG IS
                    BEGIN
                        DELETE FROM STG_POLICY;
                        INSERT INTO STG_RESULT (EFFECTIVE_DATE)
                        SELECT CASE
                                   WHEN s.POLICY_STATUS = 'ACTIVE' THEN s.START_DATE
                                   ELSE s.START_DATE + 30
                               END AS EFFECTIVE_DATE
                          FROM STG_POLICY s
                          JOIN SRC_POLICY p ON p.POLICY_ID = s.POLICY_NO
                         WHERE p.POLICY_STATUS IS NOT NULL;
                        MERGE INTO TGT_POLICY t
                        USING STG_RESULT s ON (s.POLICY_NO = t.POLICY_NO)
                        WHEN MATCHED THEN UPDATE SET t.EFFECTIVE_DATE = s.EFFECTIVE_DATE
                        WHEN NOT MATCHED THEN INSERT (POLICY_NO, EFFECTIVE_DATE)
                        VALUES (s.POLICY_NO, s.EFFECTIVE_DATE);
                    END LOAD_LONG;
                END PKG_LONG;
                /
                """;
        Files.writeString(packagesDirectory.resolve("PKG_LONG.pkb"), source);
        PlsqlChunkingService service = new PlsqlChunkingService(packagesDirectory, 8);

        List<PlsqlChunk> allChunks = service.loadChunks();
        List<PlsqlChunk> chunks = allChunks.stream()
                .filter(chunk -> chunk.procedureName().equals("LOAD_LONG"))
                .toList();

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.startLine()).isPositive();
            assertThat(chunk.endLine()).isGreaterThanOrEqualTo(chunk.startLine());
            assertThat(chunk.content().split("\\R", -1).length - (chunk.content().endsWith("\n") ? 1 : 0))
                    .isEqualTo(chunk.endLine() - chunk.startLine() + 1);
        });
        assertThat(chunks.stream().filter(chunk -> chunk.content().contains("CASE"))).singleElement()
                .satisfies(chunk -> {
                    assertThat(chunk.content()).contains("WHEN s.POLICY_STATUS", "END AS EFFECTIVE_DATE",
                            "JOIN SRC_POLICY", "WHERE p.POLICY_STATUS");
                });
        assertThat(chunks.stream().filter(chunk -> chunk.content().contains("MERGE INTO"))).singleElement()
                .satisfies(chunk -> assertThat(chunk.content()).contains("WHEN MATCHED", "WHEN NOT MATCHED",
                        "VALUES (s.POLICY_NO, s.EFFECTIVE_DATE);"));
        assertThat(allChunks.stream().map(PlsqlChunk::content).collect(Collectors.joining())).isEqualTo(source);
    }

    @Test
    void marksUnrecognizedPackageAndRoutineNamesUnknown() throws IOException {
        Path packagesDirectory = Files.createTempDirectory("plsql-unknown");
        Files.writeString(packagesDirectory.resolve("mystery.pkb"), "BEGIN\n  NULL;\nEND;\n");

        List<PlsqlChunk> chunks = new PlsqlChunkingService(packagesDirectory, 20).loadChunks();

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.packageName()).isEqualTo("UNKNOWN");
            assertThat(chunk.procedureName()).isEqualTo("UNKNOWN");
            assertThat(chunk.content()).isEqualTo("BEGIN\n  NULL;\nEND;\n");
        });
    }
}
