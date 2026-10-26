package com.example.lineage.api;

import com.example.lineage.chunk.PlsqlChunkingService;
import com.example.lineage.retrieval.ChunkRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChunkRetrievalControllerTest {

    @TempDir
    Path packagesDirectory;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(packagesDirectory.resolve("PKG_DEMO.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_DEMO AS
                    PROCEDURE COPY_VALUE IS
                    BEGIN
                        INSERT INTO TARGET_TABLE (OUTPUT_DATE)
                        SELECT INPUT_DATE FROM SOURCE_TABLE;
                    END COPY_VALUE;
                END PKG_DEMO;
                /
                """);
        var chunkingService = new PlsqlChunkingService(packagesDirectory.toString(), 120);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChunkRetrievalController(
                new ChunkRetrievalService(chunkingService))).build();
    }

    @Test
    void returnsChunkEvidenceAndHonorsRequestedLimit() throws Exception {
        mockMvc.perform(get("/api/retrieval/search").param("query", "TARGET_TABLE.OUTPUT_DATE").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].chunkId").isNotEmpty())
                .andExpect(jsonPath("$[0].packageName").value("PKG_DEMO"))
                .andExpect(jsonPath("$[0].procedureName").value("COPY_VALUE"))
                .andExpect(jsonPath("$[0].matchedLines[0].lineNumber").isNumber())
                .andExpect(jsonPath("$[0].matchedLines[0].code").value("        INSERT INTO TARGET_TABLE (OUTPUT_DATE)"))
                .andExpect(jsonPath("$[0].content").isNotEmpty());
    }

    @Test
    void returnsEmptyArrayForMissingColumn() throws Exception {
        mockMvc.perform(get("/api/retrieval/search").param("query", "MISSING_COLUMN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
