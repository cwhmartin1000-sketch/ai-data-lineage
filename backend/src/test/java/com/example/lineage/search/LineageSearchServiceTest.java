package com.example.lineage.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineageSearchServiceTest {

    @TempDir
    Path packagesDirectory;

    private LineageSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new LineageSearchService(packagesDirectory);
    }

    @Test
    void searchesCaseInsensitivelyAndReturnsProcedureAndLineNumber() throws IOException {
        write("PKG_EXAMPLE.pkb", """
                CREATE OR REPLACE PACKAGE BODY PKG_EXAMPLE AS
                    PROCEDURE LOAD_ROWS IS
                    BEGIN
                        INSERT INTO TGT_POLICY (EFFECTIVE_DATE) VALUES (SYSDATE);
                    END LOAD_ROWS;
                END PKG_EXAMPLE;
                /
                """);

        List<LineageSearchResult> results = searchService.search("effective_date");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().fileName()).isEqualTo("PKG_EXAMPLE.pkb");
        assertThat(results.getFirst().lineNumber()).isEqualTo(4);
        assertThat(results.getFirst().code()).contains("EFFECTIVE_DATE");
        assertThat(results.getFirst().procedureName()).isEqualTo("LOAD_ROWS");
    }

    @Test
    void doesNotMatchSubstringOrCommentsAndReturnsEmptyWhenMissing() throws IOException {
        write("PKG_EXAMPLE.pkb", """
                CREATE OR REPLACE PACKAGE BODY PKG_EXAMPLE AS
                    PROCEDURE LOAD_ROWS IS
                    BEGIN
                        -- EFFECTIVE_DATE is only mentioned in a comment
                        SELECT NOT_EFFECTIVE_DATE FROM DUAL;
                    END LOAD_ROWS;
                END PKG_EXAMPLE;
                /
                """);

        assertThat(searchService.search("EFFECTIVE_DATE")).isEmpty();
        assertThat(searchService.search("NOT_PRESENT")).isEmpty();
    }

    @Test
    void readsPackageSpecsAndLeavesProcedureUnknownWhenItCannotBeInferred() throws IOException {
        write("PKG_EXAMPLE.pks", """
                CREATE OR REPLACE PACKAGE PKG_EXAMPLE AS
                    PROCEDURE GET_EFFECTIVE_DATE;
                    PROCEDURE LOAD_ROWS;
                END PKG_EXAMPLE;
                /
                """);

        List<LineageSearchResult> results = searchService.search("GET_EFFECTIVE_DATE");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().procedureName()).isEqualTo("GET_EFFECTIVE_DATE");
    }

    @Test
    void searchesRecursivelyAndIgnoresNonPackageFiles() throws IOException {
        Path nested = Files.createDirectories(packagesDirectory.resolve("nested"));
        Files.writeString(nested.resolve("PKG_NESTED.pkb"), "SELECT POLICY_NO FROM DUAL;\n");
        Files.writeString(packagesDirectory.resolve("notes.sql"), "SELECT POLICY_NO FROM DUAL;\n");

        List<LineageSearchResult> results = searchService.search("POLICY_NO");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().fileName()).isEqualTo("PKG_NESTED.pkb");
        assertThat(results.getFirst().procedureName()).isNull();
    }

    private void write(String fileName, String content) throws IOException {
        Files.writeString(packagesDirectory.resolve(fileName), content);
    }
}
