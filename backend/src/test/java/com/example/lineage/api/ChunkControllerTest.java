package com.example.lineage.api;

import com.example.lineage.chunk.PlsqlChunk;
import com.example.lineage.chunk.PlsqlChunkingService;
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

class ChunkControllerTest {

    @TempDir
    Path packagesDirectory;

    private MockMvc mockMvc;
    private PlsqlChunkingService chunkingService;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(packagesDirectory.resolve("PKG_DEMO.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_DEMO AS
                    PROCEDURE LOAD_ROWS IS
                    BEGIN
                        NULL;
                    END LOAD_ROWS;
                END PKG_DEMO;
                /
                """);
        chunkingService = new PlsqlChunkingService(packagesDirectory.toString(), 120);
        mockMvc = MockMvcBuilders.standaloneSetup(new ChunkController(chunkingService)).build();
    }

    @Test
    void listsChunkSummariesAndReturnsChunkDetails() throws Exception {
        PlsqlChunk chunk = chunkingService.loadChunks().stream()
                .filter(candidate -> candidate.procedureName().equals("LOAD_ROWS"))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(get("/api/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.chunkId == '%s')]".formatted(chunk.chunkId())).exists())
                .andExpect(jsonPath("$[?(@.chunkId == '%s')].content".formatted(chunk.chunkId())).doesNotExist());

        mockMvc.perform(get("/api/chunks/{chunkId}", chunk.chunkId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkId").value(chunk.chunkId()))
                .andExpect(jsonPath("$.packageName").value("PKG_DEMO"))
                .andExpect(jsonPath("$.procedureName").value("LOAD_ROWS"))
                .andExpect(jsonPath("$.content").value(chunk.content()));
    }

    @Test
    void returnsNotFoundForUnknownChunkId() throws Exception {
        mockMvc.perform(get("/api/chunks/{chunkId}", "does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
