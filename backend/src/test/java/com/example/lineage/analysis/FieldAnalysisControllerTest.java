package com.example.lineage.analysis;

import com.example.lineage.api.*;
import com.example.lineage.assistant.*;
import com.example.lineage.chunk.*;
import com.example.lineage.callgraph.*;
import com.example.lineage.retrieval.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FieldAnalysisControllerTest {
    @Test
    void returns400ForMissingColumn() throws Exception {
        var chunks = mock(PlsqlChunkingService.class);
        var collector = new FieldEvidenceCollector(chunks, mock(ChunkRetrievalService.class), mock(ProcedureCallGraphService.class));
        endpoint(new FieldAnalysisService(collector, chunks, mock(LineageAssistantModel.class), 24000, 8, 16000))
                .perform(post("/api/assistant/analyze-field").contentType("application/json").content("{\"question\":\"規格\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsStructuredCoverageAndRetainsSourcesForProviderFailure() throws Exception {
        var service = setup((q, c, e) -> { throw new AssistantProviderException("unavailable", null); });
        endpoint(service).perform(post("/api/assistant/analyze-field").contentType("application/json")
                        .content("{\"column\":\"CODE\",\"question\":\"檢核條件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage.failedBatches").value(1))
                .andExpect(jsonPath("$.coverage.analyzedChunks").value(0))
                .andExpect(jsonPath("$.sources[0].citationId").value("S1"))
                .andExpect(jsonPath("$.batches[0].status").value("FAILED"));
    }

    @Test
    void returns503WhenProviderIsNotConfigured() throws Exception {
        var service = setup((q, c, e) -> { throw new AssistantModelUnavailableException("No assistant model is configured"); });
        endpoint(service).perform(post("/api/assistant/analyze-field").contentType("application/json")
                        .content("{\"column\":\"CODE\",\"question\":\"規格\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    private FieldAnalysisService setup(LineageAssistantModel model) {
        var collector = mock(FieldEvidenceCollector.class);
        when(collector.collect("CODE")).thenReturn(new FieldEvidenceCollector.Evidence(List.of(
                new ChunkRetrievalResult("id", "PKG", "RUN", "demo.pkb", 1, 1, 1, List.of(), "CODE := 'A';")),
                List.of(), 1, List.of()));
        var chunks = mock(PlsqlChunkingService.class);
        when(chunks.statistics()).thenReturn(new PackageScanStatistics("fixture", 1, 1, 1, 0, 1, List.of(), 0, List.of()));
        return new FieldAnalysisService(collector, chunks, model, 24000, 8, 16000);
    }
    private MockMvc endpoint(FieldAnalysisService service) {
        return MockMvcBuilders.standaloneSetup(new FieldAnalysisController(service))
                .setControllerAdvice(new AssistantExceptionHandler()).build();
    }
}
