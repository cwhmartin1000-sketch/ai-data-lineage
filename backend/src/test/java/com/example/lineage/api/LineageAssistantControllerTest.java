package com.example.lineage.api;

import com.example.lineage.assistant.LineageAssistantModel;
import com.example.lineage.assistant.AssistantModelUnavailableException;
import com.example.lineage.assistant.AssistantProviderException;
import com.example.lineage.assistant.LineageAssistantService;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.ChunkRetrievalService;
import com.example.lineage.retrieval.MatchedLine;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LineageAssistantControllerTest {

    @Test
    void returnsModelAnswerAndBackendCreatedSources() throws Exception {
        ChunkRetrievalService retrieval = mock(ChunkRetrievalService.class);
        ChunkRetrievalResult chunk = new ChunkRetrievalResult(
                "chunk-test", "PKG_DEMO", "READ_ROWS", "PKG_DEMO.pkb", 4, 8, 10.0,
                List.of(new MatchedLine(6, "OUTPUT_DATE")), "OUTPUT_DATE\n"
        );
        when(retrieval.search("OUTPUT_DATE", 8)).thenReturn(List.of(chunk));
        LineageAssistantModel fakeModel = (question, column, evidence) -> "欄位由檢索到的程式碼產生。[S1]";
        LineageAssistantService service = new LineageAssistantService(retrieval, fakeModel);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LineageAssistantController(service))
                .setControllerAdvice(new AssistantExceptionHandler())
                .build();

        mockMvc.perform(post("/api/assistant/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"欄位從哪裡來？\",\"column\":\"OUTPUT_DATE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("欄位由檢索到的程式碼產生。[S1]"))
                .andExpect(jsonPath("$.sources[0].citationId").value("S1"))
                .andExpect(jsonPath("$.sources[0].fileName").value("PKG_DEMO.pkb"))
                .andExpect(jsonPath("$.sources[0].matchedLines[0].lineNumber").value(6))
                .andExpect(jsonPath("$.limitations[0]").value("檢索目前只支援欄位名稱或 TABLE.COLUMN 的識別字比對，不支援自然語言語意檢索。"));
    }

    @Test
    void returnsClearServiceUnavailableWhenNoModelIsConfigured() throws Exception {
        LineageAssistantModel unavailableModel = (question, column, evidence) -> {
            throw new AssistantModelUnavailableException("No assistant model is configured");
        };
        LineageAssistantService service = new LineageAssistantService(mock(ChunkRetrievalService.class), unavailableModel);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LineageAssistantController(service))
                .setControllerAdvice(new AssistantExceptionHandler())
                .build();

        mockMvc.perform(post("/api/assistant/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"欄位從哪裡來？\",\"column\":\"OUTPUT_DATE\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("No assistant model is configured"));
    }

    @Test
    void providerFailureDoesNotExposeCredentialOrVendorDetails() throws Exception {
        LineageAssistantModel failingModel = (question, column, evidence) -> {
            throw new AssistantProviderException(
                    "The selected assistant provider could not complete the request. Check its credentials, endpoint, and model configuration.",
                    new IllegalStateException("credential-value-must-not-appear")
            );
        };
        LineageAssistantService service = new LineageAssistantService(mock(ChunkRetrievalService.class), failingModel);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LineageAssistantController(service))
                .setControllerAdvice(new AssistantExceptionHandler())
                .build();

        mockMvc.perform(post("/api/assistant/ask")
                        .contentType("application/json")
                        .content("{\"question\":\"欄位從哪裡來？\",\"column\":\"OUTPUT_DATE\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("credentials, endpoint")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("credential-value-must-not-appear"))));
    }
}
