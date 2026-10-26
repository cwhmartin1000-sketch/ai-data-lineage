package com.example.lineage.api;

import com.example.lineage.callgraph.ProcedureCallAnalyzer;
import com.example.lineage.callgraph.ProcedureCallGraphService;
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

class ProcedureCallGraphControllerTest {

    @TempDir
    Path packagesDirectory;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(packagesDirectory.resolve("PKG_SAMPLE.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_SAMPLE AS
                    PROCEDURE RUNNER IS
                    BEGIN
                        PKG_OTHER.PROCESS;
                        PROCESS;
                    END RUNNER;
                    PROCEDURE PROCESS IS
                    BEGIN
                        NULL;
                    END PROCESS;
                END PKG_SAMPLE;
                /
                """);
        Files.writeString(packagesDirectory.resolve("PKG_OTHER.pkb"), """
                CREATE OR REPLACE PACKAGE BODY PKG_OTHER AS
                    PROCEDURE PROCESS IS
                    BEGIN
                        NULL;
                    END PROCESS;
                END PKG_OTHER;
                /
                """);
        PlsqlChunkingService chunkingService = new PlsqlChunkingService(packagesDirectory.toString(), 120);
        ProcedureCallGraphService service = new ProcedureCallGraphService(chunkingService, new ProcedureCallAnalyzer());
        mockMvc = MockMvcBuilders.standaloneSetup(new ProcedureCallGraphController(service)).build();
    }

    @Test
    void returnsOutgoingCallsForOptionalPackageAndProcedureFilter() throws Exception {
        mockMvc.perform(get("/api/call-graph").queryParam("package", "PKG_SAMPLE")
                        .queryParam("procedure", "RUNNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.unresolvedCalls").isEmpty())
                .andExpect(jsonPath("$.edges[0].callerPackage").value("PKG_SAMPLE"))
                .andExpect(jsonPath("$.edges[0].callerProcedure").value("RUNNER"))
                .andExpect(jsonPath("$.edges[0].calleePackage").value("PKG_OTHER"))
                .andExpect(jsonPath("$.edges[0].calleeProcedure").value("PROCESS"))
                .andExpect(jsonPath("$.edges[0].fileName").value("PKG_SAMPLE.pkb"))
                .andExpect(jsonPath("$.edges[0].lineNumber").value(4))
                .andExpect(jsonPath("$.edges[0].evidenceCode").value("PKG_OTHER.PROCESS;"))
                .andExpect(jsonPath("$.edges[0].crossPackage").value(true))
                .andExpect(jsonPath("$.edges[1].crossPackage").value(false));
    }
}
